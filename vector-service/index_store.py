"""Immutable FAISS snapshots: updates rebuild once, queries reuse a loaded index."""
from __future__ import annotations
import io
import json
import os
import re
import threading
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path

import faiss
import numpy as np


@dataclass(frozen=True)
class Snapshot:
    rows: list[dict]
    index: faiss.Index


class IndexStore:
    """One process owns a data directory. An atomic archive contains BOTH index and metadata."""

    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self._cache: dict[str, Snapshot] = {}
        self._lock = threading.RLock()

    def _path(self, name: str) -> Path:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", name):
            raise ValueError("Invalid collection name")
        return self.root / f"{name}.snapshot"

    def _load(self, name: str) -> Snapshot | None:
        path = self._path(name)
        if name in self._cache:
            return self._cache[name]
        if not path.exists():
            return None
        # Only locally generated snapshots are read. Do not accept arbitrary uploaded FAISS files.
        with zipfile.ZipFile(path) as archive:
            rows = json.loads(archive.read("rows.json"))
            index = faiss.deserialize_index(np.frombuffer(archive.read("index.faiss"), dtype=np.uint8).copy())
        if index.ntotal != len(rows):
            raise RuntimeError("Snapshot index/metadata count mismatch")
        snapshot = Snapshot(rows, index)
        self._cache[name] = snapshot
        return snapshot

    def _publish(self, name: str, rows: list[dict], matrix: np.ndarray):
        index = faiss.IndexFlatIP(matrix.shape[1])
        matrix = np.ascontiguousarray(matrix, dtype=np.float32)
        faiss.normalize_L2(matrix)
        index.add(matrix)
        target = self._path(name)
        temporary = target.with_suffix(".tmp")
        try:
            with open(temporary, "wb") as file:
                with zipfile.ZipFile(file, "w", compression=zipfile.ZIP_STORED) as archive:
                    archive.writestr("rows.json", json.dumps(rows))
                    archive.writestr("index.faiss", faiss.serialize_index(index).tobytes())
                file.flush()
                os.fsync(file.fileno())
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)
        self._cache[name] = Snapshot(rows, index)

    def replace_document(self, name: str, document_id: str, chunks: list[dict]):
        with self._lock:
            snapshot = self._load(name)
            new_rows = [{k: v for k, v in chunk.items() if k != "vector"} for chunk in chunks]
            new_vectors = np.asarray([chunk["vector"] for chunk in chunks], dtype=np.float32)
            if new_vectors.ndim != 2 or not len(new_vectors) or not new_vectors.shape[1]:
                raise ValueError("A document needs nonempty vectors of equal dimension")
            if not np.isfinite(new_vectors).all() or np.any(np.linalg.norm(new_vectors, axis=1) == 0):
                raise ValueError("Vectors must be finite and nonzero")
            if snapshot is not None:
                if snapshot.index.d != new_vectors.shape[1]:
                    raise ValueError("Embedding dimension mismatch; use a separate collection")
                keep = [i for i, row in enumerate(snapshot.rows) if row["documentId"] != document_id]
                old_vectors = snapshot.index.reconstruct_n(0, snapshot.index.ntotal)
                new_vectors = np.concatenate([old_vectors[keep], new_vectors])
                new_rows = [snapshot.rows[i] for i in keep] + new_rows
            if len(new_rows) > 50_000:
                raise ValueError("Collection exceeds the 50,000 chunk learning-project limit")
            self._publish(name, new_rows, new_vectors)
            return len(new_rows)

    def search(self, name: str, vector: list[float], top_k: int, min_score: float):
        started = time.perf_counter()
        with self._lock:
            snapshot = self._load(name)
            if snapshot is None:
                return {"results": [], "searchMs": 0.0, "serviceMs": (time.perf_counter() - started) * 1000}
            query = np.asarray([vector], dtype=np.float32)
            if query.shape[1] != snapshot.index.d:
                raise ValueError("Embedding dimension mismatch")
            if not np.isfinite(query).all() or np.linalg.norm(query) == 0:
                raise ValueError("Query must be finite and nonzero")
            faiss.normalize_L2(query)
            # OpenMP thread settings are thread-local. FastAPI runs sync routes in worker threads.
            faiss.omp_set_num_threads(1)
            before_search = time.perf_counter()
            scores, indices = snapshot.index.search(query, min(top_k, snapshot.index.ntotal))
            search_ms = (time.perf_counter() - before_search) * 1000
            results = [{**snapshot.rows[int(i)], "score": float(score)}
                       for score, i in zip(scores[0], indices[0]) if i >= 0 and score >= min_score]
        return {"results": results, "searchMs": search_ms,
                "serviceMs": (time.perf_counter() - started) * 1000}

    def delete_document(self, name: str, document_id: str):
        with self._lock:
            snapshot = self._load(name)
            if snapshot is None:
                return
            keep = [i for i, row in enumerate(snapshot.rows) if row["documentId"] != document_id]
            if not keep:
                self.delete(name)
            elif len(keep) != len(snapshot.rows):
                self._publish(name, [snapshot.rows[i] for i in keep],
                              snapshot.index.reconstruct_n(0, snapshot.index.ntotal)[keep])

    def delete(self, name: str):
        with self._lock:
            self._path(name).unlink(missing_ok=True)
            self._cache.pop(name, None)
