"""Private FAISS service. Bind to loopback locally; only the backend can reach it in Compose."""
import os
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ConfigDict, field_validator
from index_store import IndexStore

app = FastAPI(title="AI Personal Knowledge Assistant — FAISS", version="1.0.0")
store = IndexStore(Path(os.getenv("VECTOR_DATA_DIR", "./data")))


class Chunk(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    id: str = Field(min_length=1, max_length=200)
    documentId: str = Field(min_length=1, max_length=100)
    filename: str = Field(max_length=200)
    chunkIndex: int = Field(ge=0)
    start: int = Field(ge=0)
    end: int = Field(gt=0)
    text: str = Field(min_length=1, max_length=4000)
    vector: list[float] = Field(min_length=1, max_length=4096)


class ReplaceDocument(BaseModel):
    chunks: list[Chunk] = Field(min_length=1, max_length=2000)


class Query(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    vector: list[float] = Field(min_length=1, max_length=4096)
    topK: int = Field(ge=1, le=100)
    minScore: float = Field(default=-1.0, ge=-1.0, le=1.0)


@app.exception_handler(ValueError)
async def invalid_request(request, error):
    return JSONResponse(status_code=400, content={"error": str(error)})


@app.get("/health")
def health():
    return {"status": "UP", "index": "FAISS IndexFlatIP", "persistence": "atomic snapshot"}


@app.put("/collections/{name}/documents/{document_id}")
def replace_document(name: str, document_id: str, request: ReplaceDocument):
    if any(chunk.documentId != document_id for chunk in request.chunks):
        raise HTTPException(400, "Chunk documentId must match path")
    if len({chunk.id for chunk in request.chunks}) != len(request.chunks):
        raise HTTPException(400, "Chunk IDs must be unique")
    if any(chunk.end <= chunk.start for chunk in request.chunks):
        raise HTTPException(400, "Invalid source offsets")
    count = store.replace_document(name, document_id, [c.model_dump() for c in request.chunks])
    return {"count": count}


@app.post("/collections/{name}/search")
def search(name: str, request: Query):
    return store.search(name, request.vector, request.topK, request.minScore)


@app.delete("/collections/{name}/documents/{document_id}")
def delete_document(name: str, document_id: str):
    store.delete_document(name, document_id)
    return {"deleted": True}


@app.delete("/collections/{name}")
def delete_collection(name: str):
    store.delete(name)
    return {"deleted": True}
