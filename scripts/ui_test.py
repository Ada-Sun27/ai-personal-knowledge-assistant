#!/usr/bin/env python3
"""Start an isolated demo stack, run Playwright acceptance checks, then clean up."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from integration_test import ROOT, free_port, stop, wait_up


def main():
    processes, handles = [], []
    with tempfile.TemporaryDirectory(prefix='knowledge-ui-') as temp:
        work = Path(temp)
        vector_port, backend_port, web_port = free_port(), free_port(), free_port()
        def start(command, cwd, env, name, health):
            log = work / f'{name}.log'
            handle = log.open('w'); handles.append(handle)
            process = subprocess.Popen(command,cwd=cwd,env={**os.environ,**env},stdout=handle,stderr=handle)
            processes.append(process); wait_up(health,process,log)
        try:
            start([sys.executable,'-m','uvicorn','app:app','--host','127.0.0.1','--port',str(vector_port)], ROOT/'vector-service',
                  {'VECTOR_DATA_DIR':str(work/'vectors')}, 'vector', f'http://127.0.0.1:{vector_port}/health')
            start([os.getenv('JAVA_BIN','java'),'-jar',str(ROOT/'backend/target/knowledge-api-1.0.0.jar')],ROOT,
                  {'APP_DATA_DIR':str(work/'backend'),'SPRING_PROFILES_ACTIVE':'demo','SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(backend_port),
                   'VECTOR_URL':f'http://127.0.0.1:{vector_port}','APP_WEB_ORIGIN':f'http://127.0.0.1:{web_port}'},
                  'backend', f'http://127.0.0.1:{backend_port}/api/health')
            start(['npm','run','dev','--','--port',str(web_port),'--strictPort'], ROOT/'web',
                  {'VITE_API_URL':f'http://127.0.0.1:{backend_port}'}, 'web', f'http://127.0.0.1:{web_port}')
            subprocess.run(['npm','run','test:e2e'],cwd=ROOT/'web',check=True,
                           env={**os.environ,'PLAYWRIGHT_BASE_URL':f'http://127.0.0.1:{web_port}'})
        except BaseException:
            for log in work.glob('*.log'): print(log.read_text()[-10000:], file=sys.stderr)
            raise
        finally:
            for process in reversed(processes): stop(process)
            for handle in handles: handle.close()


if __name__ == '__main__':
    main()
