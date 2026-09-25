#!/usr/bin/env python3
"""Real HTTP integration test: Spring Boot demo provider + FAISS, persistence and SSE.
Run from the repository root after `cd backend && mvn package` and installing vector requirements.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import httpx

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def stop(process):
    if process is not None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def wait_up(url, process, log):
    with httpx.Client(trust_env=False, timeout=2) as client:
        for _ in range(200):
            if process.poll() is not None:
                raise RuntimeError(f'Service exited:\n{Path(log).read_text()}')
            try:
                if client.get(url).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(.15)
    raise RuntimeError(f'Service failed to start:\n{Path(log).read_text()}')


def main():
    jar = ROOT / 'backend/target/knowledge-api-1.0.0.jar'
    if not jar.exists():
        raise RuntimeError('Build the backend first: cd backend && mvn package')
    java = os.getenv('JAVA_BIN', 'java')
    checks = []
    with tempfile.TemporaryDirectory(prefix='knowledge-integration-') as temp:
        directory = Path(temp)
        vport, bport = free_port(), free_port()
        vector_log, backend_log = directory / 'vector.log', directory / 'backend.log'
        handles = []
        def start_vector():
            handle = vector_log.open('a'); handles.append(handle)
            process = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'app:app', '--host', '127.0.0.1', '--port', str(vport)],
                cwd=ROOT / 'vector-service', env={**os.environ, 'VECTOR_DATA_DIR':str(directory / 'vectors'), 'OMP_NUM_THREADS':'1'}, stdout=handle, stderr=handle)
            wait_up(f'http://127.0.0.1:{vport}/health', process, vector_log)
            return process
        def start_backend():
            handle = backend_log.open('a'); handles.append(handle)
            process = subprocess.Popen([java, '-jar', str(jar)], cwd=ROOT,
                env={**os.environ, 'SPRING_PROFILES_ACTIVE':'demo', 'SERVER_PORT':str(bport),
                     'SERVER_ADDRESS':'127.0.0.1', 'VECTOR_URL':f'http://127.0.0.1:{vport}', 'APP_DATA_DIR':str(directory/'backend')}, stdout=handle, stderr=handle)
            wait_up(f'http://127.0.0.1:{bport}/api/health', process, backend_log)
            return process
        vector = backend = None
        try:
            vector = start_vector(); backend = start_backend()
            with httpx.Client(base_url=f'http://127.0.0.1:{bport}/api', trust_env=False, timeout=120) as client:
                assert client.get('/health').json()['mode'] == 'demo'
                assert client.post('/ask', json={'question':'', 'topK':0, 'promptVariant':'bad'}).status_code == 400
                empty = client.post('/ask', json={'question':'refund deadline', 'topK':4, 'promptVariant':'concise'})
                empty.raise_for_status(); assert empty.json()['sources'] == []
                checks.append('request validation and empty-corpus abstention')
                docs = []
                for name, text in [('refunds.md', 'Refunds are available within 30 days of purchase. Contact support with your receipt.\n\nRefund requests require the original order ID.'),
                                   ('security.md', 'Encryption keys rotate every 90 days. Audit logs are retained for one year.\n\nTeam administrators review access every month.')]:
                    response = client.post('/documents', files={'file':(name,text,'text/plain')}, data={'strategy':'paragraph','chunkSize':'100','overlap':'10'})
                    response.raise_for_status(); assert response.json()['state'] == 'READY'; docs.append(response.json())
                assert len(client.get('/documents').json()) == 2
                checks.append('upload, extraction, chunking and FAISS ingestion')
                streamed = client.post('/ask/stream',json={'question':'What is the refund deadline?', 'topK':4,'promptVariant':'concise'})
                streamed.raise_for_status()
                assert streamed.headers['content-type'].startswith('text/event-stream')
                events = []
                for frame in streamed.text.replace('\r\n','\n').split('\n\n'):
                    event, data = None, []
                    for line in frame.splitlines():
                        if line.startswith('event:'): event = line[6:].strip()
                        if line.startswith('data:'): data.append(line[5:].lstrip())
                    if data: events.append((event,json.loads('\n'.join(data))))
                assert events[0][0] == 'sources' and events[-1][0] == 'done', streamed.text
                record = events[-1][1]
                assert ''.join(payload['text'] for event,payload in events if event=='token') == record['answer']
                assert '[S1]' in record['answer'] and not record['citations']['unknownLabels']
                assert record['sources'][0]['documentId'] == docs[0]['id']
                checks.append('actual named SSE frames, token assembly and source citations')
                answer_id = record['id']
                assert client.post(f'/answers/{answer_id}/feedback',json={'rating':0}).status_code == 400
                client.post(f'/answers/{answer_id}/feedback',json={'rating':-1,'comment':'Please summarize more briefly.'}).raise_for_status()
                negative = client.get('/answers?negativeOnly=true').json()
                assert len(negative) == 1 and negative[0]['question'] == record['question']
                assert negative[0]['sources'][0]['text'] and negative[0]['feedback']['rating'] == -1
                checks.append('traceable negative feedback and answer history')
                request = {'cases':[{'question':'What is the refund deadline?','relevantDocumentIds':[docs[0]['id']], 'referenceAnswer':'Refunds are available within 30 days of purchase.'}],
                           'chunkings':[{'strategy':'fixed','size':100,'overlap':10},{'strategy':'paragraph','size':150,'overlap':20}],
                           'topKs':[1,4], 'promptVariants':['concise','detailed']}
                evaluation = client.post('/evaluations',json=request)
                evaluation.raise_for_status(); report = evaluation.json()
                assert len(report['results']) == 8
                assert all(0 <= row['tokenF1'] <= 1 for row in report['results'])
                assert all(row['documentRecall'] == 1 for row in report['results'])
                assert client.get('/evaluations/'+report['id']).json()['id'] == report['id']
                assert not list((directory/'vectors').glob('eval-*.snapshot'))
                checks.append('8 evaluation combinations, persisted results, isolated index cleanup')
                stop(backend); backend = None; stop(vector); vector = None
                vector = start_vector(); backend = start_backend()
                assert len(client.get('/documents').json()) == 2
                assert client.get(f'/answers/{answer_id}').json()['feedback']['rating'] == -1
                response = client.post('/ask',json={'question':'refund deadline', 'topK':4,'promptVariant':'concise'})
                response.raise_for_status(); assert response.json()['sources'][0]['documentId'] == docs[0]['id']
                checks.append('backend and FAISS restart recovery')
                for doc in docs:
                    assert client.delete('/documents/'+doc['id']).status_code == 204
                assert client.get('/documents').json() == []
                response = client.post('/ask',json={'question':'refund deadline','topK':4,'promptVariant':'concise'})
                assert response.json()['sources'] == []
                assert client.get(f'/answers/{answer_id}').json()['sources']
                checks.append('document deletion with retained historical source snapshots')
                sample_report = directory / 'sample-evaluation.json'
                subprocess.run([sys.executable, str(ROOT/'scripts/evaluate.py'), '--base-url', f'http://127.0.0.1:{bport}', '--output', str(sample_report)], check=True)
                sample = json.loads(sample_report.read_text())
                assert len(sample['results']) == 8 and len(sample['results'][0]['cases']) == 3
                checks.append('sample evaluation CLI with three labeled questions')
            print(json.dumps({'passed':len(checks),'checks':checks,'provider':'demo (no AWS inference)'},indent=2))
        except BaseException:
            for log in (vector_log,backend_log):
                if log.exists(): print(log.read_text()[-18000:],file=sys.stderr)
            raise
        finally:
            stop(backend); stop(vector)
            for handle in handles: handle.close()


if __name__ == '__main__':
    main()
