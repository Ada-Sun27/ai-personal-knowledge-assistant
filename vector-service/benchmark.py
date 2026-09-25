"""Measure complete localhost HTTP vector retrieval, with precomputed synthetic embeddings."""
import argparse
import json
import os
import platform
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
import faiss
import httpx
import numpy as np
from index_store import IndexStore


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--dimension", type=int, default=1024)
    parser.add_argument("--queries", type=int, default=300)
    parser.add_argument("--output", default="../docs/benchmark-results.json")
    args = parser.parse_args()
    rng = np.random.default_rng(42)
    matrix = rng.normal(size=(args.count, args.dimension)).astype("float32")
    queries = rng.normal(size=(args.queries + 31, args.dimension)).astype("float32")
    rows = [{"id": f"synthetic:{i}", "documentId": f"doc-{i // 10}", "filename": "synthetic.txt",
             "chunkIndex": i, "start": 0, "end": 33, "text": "Synthetic benchmark chunk content."} for i in range(args.count)]
    with tempfile.TemporaryDirectory() as temp:
        # Seed via the same atomic snapshot writer; do not include construction in search latency.
        store = IndexStore(Path(temp))
        store._publish("benchmark", rows, matrix)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = {**os.environ, "VECTOR_DATA_DIR": temp, "OMP_NUM_THREADS": "1"}
        server = subprocess.Popen([sys.executable, "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", str(port)],
                                  env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=30, trust_env=False) as client:
                for _ in range(100):
                    try:
                        if client.get("/health").status_code == 200: break
                    except httpx.ConnectError: pass
                    time.sleep(.05)
                http_ms, search_ms, service_ms = [], [], []
                cold_ms = None
                for i, query in enumerate(queries):
                    started = time.perf_counter()
                    response = client.post("/collections/benchmark/search", json={"vector": query.tolist(), "topK": 5})
                    response.raise_for_status()
                    body = response.json()
                    elapsed = (time.perf_counter() - started) * 1000
                    if i == 0: cold_ms = elapsed
                    if i >= 31:
                        http_ms.append(elapsed); search_ms.append(body["searchMs"]); service_ms.append(body["serviceMs"])
                def summary(values):
                    return {"p50": float(np.percentile(values, 50)), "p95": float(np.percentile(values, 95)),
                            "p99": float(np.percentile(values, 99)), "max": max(values)}
                result = {"corpus": "seeded synthetic float32 vectors (not Bedrock embeddings)", "count": args.count,
                          "dimensions": args.dimension, "queries": args.queries, "warmups": 30, "topK": 5,
                          "concurrency": 1, "faissThreads": 1, "faissVersion": faiss.__version__,
                          "python": platform.python_version(), "platform": platform.platform(), "cpuCount": os.cpu_count(),
                          "coldHttpMs": cold_ms, "httpRoundTripMs": summary(http_ms),
                          "serviceMs": summary(service_ms), "faissSearchMs": summary(search_ms),
                          "p95Below100Ms": bool(np.percentile(http_ms, 95) < 100),
                          "scope": "Warm single-client local vector HTTP retrieval; includes request/response serialization, transport, service processing and FAISS; excludes query embedding and LLM generation."}
                output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text(json.dumps(result, indent=2) + "\n")
                print(json.dumps(result, indent=2))
        finally:
            server.terminate()
            try: server.wait(timeout=10)
            except subprocess.TimeoutExpired: server.kill()


if __name__ == "__main__":
    main()
