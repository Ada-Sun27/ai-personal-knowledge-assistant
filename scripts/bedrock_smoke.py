#!/usr/bin/env python3
"""Live AWS smoke test against a running Bedrock-mode API. This makes paid inference calls."""
import argparse
from pathlib import Path
import httpx
parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
args = parser.parse_args()
with httpx.Client(base_url=args.base_url+'/api', timeout=180, trust_env=False) as client:
    health = client.get('/health'); health.raise_for_status()
    if health.json()['mode'] != 'bedrock':
        raise SystemExit('Start the API with the bedrock profile before running this live test.')
    sample = Path(__file__).resolve().parents[1]/'examples/refund-policy.md'
    document_id = None
    try:
        with sample.open('rb') as file:
            uploaded = client.post('/documents',files={'file':(sample.name,file,'text/markdown')})
        uploaded.raise_for_status(); document_id=uploaded.json()['id']
        result = client.post('/ask',json={'question':'What is the refund deadline? Cite the source.', 'topK':4,'promptVariant':'concise'})
        result.raise_for_status(); answer=result.json()
        assert answer['mode']=='bedrock' and answer['status']=='COMPLETE'
        assert answer['sources'] and answer['answer'].strip()
        assert not answer['citations']['unknownLabels'] and not answer['citations']['missingCitations']
        print('Bedrock embedding, FAISS retrieval and Converse generation completed with valid source labels.')
        print(answer['answer'])
    finally:
        if document_id: client.delete('/documents/'+document_id).raise_for_status()
