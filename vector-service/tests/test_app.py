from concurrent.futures import ThreadPoolExecutor
import importlib
import numpy as np
import pytest
from fastapi.testclient import TestClient
from index_store import IndexStore


def chunk(doc, i, vector):
    return {"id": f"{doc}:{i}", "documentId": doc, "filename": "notes.txt", "chunkIndex": i,
            "start": i * 5, "end": i * 5 + 5, "text": "notes", "vector": vector}


@pytest.fixture
def client(tmp_path, monkeypatch):
    import app
    monkeypatch.setattr(app, "store", IndexStore(tmp_path))
    return TestClient(app.app)


def test_cosine_rank_and_threshold(client):
    assert client.put("/collections/test/documents/a", json={"chunks": [chunk("a", 0, [9, 0]), chunk("a", 1, [0, 2])]}).status_code == 200
    response = client.post("/collections/test/search", json={"vector": [1, 0], "topK": 2, "minScore": .1})
    assert response.status_code == 200
    assert [r["id"] for r in response.json()["results"]] == ["a:0"]
    assert response.json()["serviceMs"] >= response.json()["searchMs"]


def test_restart_replacement_delete(tmp_path):
    store = IndexStore(tmp_path)
    store.replace_document("test", "a", [chunk("a", 0, [1, 0]), chunk("a", 1, [0, 1])])
    restarted = IndexStore(tmp_path)
    assert restarted.search("test", [1, 0], 2, -1)["results"][0]["id"] == "a:0"
    restarted.replace_document("test", "a", [chunk("a", 2, [1, 1])])
    assert len(restarted.search("test", [1, 0], 10, -1)["results"]) == 1
    restarted.delete_document("test", "a")
    assert IndexStore(tmp_path).search("test", [1, 0], 2, -1)["results"] == []


def test_search_reuses_index(tmp_path, monkeypatch):
    store = IndexStore(tmp_path)
    store.replace_document("test", "a", [chunk("a", 0, [1, 0])])
    monkeypatch.setattr(store, "_publish", lambda *args: pytest.fail("Search must not rebuild the index"))
    index = store._cache["test"].index
    for _ in range(3):
        store.search("test", [1, 0], 1, -1)
        assert store._cache["test"].index is index


@pytest.mark.parametrize("vectors", [[[0, 0]], [[1, 0], [1]], [[float('nan'), 1]]])
def test_bad_vectors_leave_previous_snapshot_intact(tmp_path, vectors):
    store = IndexStore(tmp_path)
    store.replace_document("test", "a", [chunk("a", 0, [1, 0])])
    with pytest.raises(ValueError):
        store.replace_document("test", "a", [chunk("a", i, v) for i, v in enumerate(vectors)])
    assert len(IndexStore(tmp_path).search("test", [1, 0], 5, -1)["results"]) == 1


def test_api_validation(client):
    assert client.put("/collections/test/documents/a", json={"chunks": [chunk("b", 0, [1, 0])]}).status_code == 400
    assert client.post("/collections/test/search", json={"vector": [1, 0], "topK": 0}).status_code == 422
    client.put("/collections/test/documents/a", json={"chunks": [chunk("a", 0, [1, 0])]})
    assert client.post("/collections/test/search", json={"vector": [1], "topK": 1}).status_code == 400
    assert client.post("/collections/test/search", json={"vector": [0, 0], "topK": 1}).status_code == 400


def test_concurrent_reads_and_updates(tmp_path):
    store = IndexStore(tmp_path)
    store.replace_document("test", "a", [chunk("a", 0, [1, 0])])
    def operation(i):
        if i % 3 == 0:
            store.replace_document("test", "a", [chunk("a", i, [1, 0])])
        return len(store.search("test", [1, 0], 10, -1)["results"])
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert all(n == 1 for n in pool.map(operation, range(24)))


def test_failed_publish_does_not_replace_previous_snapshot(tmp_path, monkeypatch):
    import index_store
    store = IndexStore(tmp_path)
    store.replace_document("test", "a", [chunk("a", 0, [1, 0])])
    monkeypatch.setattr(index_store.os, "replace", lambda *args: (_ for _ in ()).throw(OSError("disk full")))
    with pytest.raises(OSError):
        store.replace_document("test", "a", [chunk("a", 1, [0, 1])])
    assert IndexStore(tmp_path).search("test", [1, 0], 1, -1)["results"][0]["id"] == "a:0"
