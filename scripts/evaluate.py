#!/usr/bin/env python3
"""Evaluate the bundled sample corpus. Use a clean demo corpus for reproducible comparisons."""
import argparse
import json
from pathlib import Path
import httpx

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--output', default='reports/evaluation.json')
args = parser.parse_args()
with httpx.Client(base_url=args.base_url+'/api', timeout=900, trust_env=False) as client:
    health = client.get('/health'); health.raise_for_status()
    if client.get('/documents').json():
        raise SystemExit('Use an empty collection for the sample evaluation (remove existing documents through the UI).')
    ids = {}
    for filename in ('refund-policy.md','security-handbook.md'):
        with (ROOT/'examples'/filename).open('rb') as file:
            result = client.post('/documents',files={'file':(filename,file,'text/markdown')})
            result.raise_for_status(); ids[filename] = result.json()['id']
    cases = [
        {'question':'What is the refund deadline?','relevantDocumentIds':[ids['refund-policy.md']],
         'referenceAnswer':'Refunds are available within 30 days of purchase.'},
        {'question':'How often do encryption keys rotate?','relevantDocumentIds':[ids['security-handbook.md']],
         'referenceAnswer':'Encryption keys rotate every 90 days.'},
        {'question':'How long are audit logs retained?','relevantDocumentIds':[ids['security-handbook.md']],
         'referenceAnswer':'Audit logs are retained for one year.'},
    ]
    result = client.post('/evaluations',json={'cases':cases,
        'chunkings':[{'strategy':'fixed','size':200,'overlap':30},{'strategy':'paragraph','size':200,'overlap':30}],
        'topKs':[1,4], 'promptVariants':['concise','detailed']})
    result.raise_for_status()
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result.json(),indent=2)+'\n')
    print(f'Saved {len(result.json()["results"])} configurations to {output} (mode: {health.json()["mode"]}).')
