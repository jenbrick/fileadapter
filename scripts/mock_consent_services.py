#!/usr/bin/env python3
"""Loopback-only synthetic demo facade and upsert receiver. Never use for real member data."""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = json.loads((ROOT/'examples/consent/lookup-fixtures.json').read_text())
LOCK = threading.Lock()
SEEN = {}
FAILED = set()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        # Spring's HTTP client can stream JSON using HTTP/1.1 chunked framing.
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            total = 0
            while True:
                size = int(self.rfile.readline(128).split(b';', 1)[0].strip(), 16)
                if size < 0 or total + size > 1024*1024:
                    raise ValueError('body size')
                if size == 0:
                    while self.rfile.readline(8192).strip():
                        pass
                    return b''.join(chunks)
                chunk = self.rfile.read(size)
                if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                    raise ValueError('chunk framing')
                chunks.append(chunk)
                total += size
        length = int(self.headers.get('Content-Length', '0'))
        if length < 1 or length > 1024*1024:
            raise ValueError('body size')
        return self.rfile.read(length)

    def do_POST(self):
        try:
            body = json.loads(self.read_body())
            if self.path == '/members/resolve':
                matches = [f['result'] for f in FIXTURES if all(body.get(k)==v for k,v in f['match'].items())]
                return self.reply(200, matches[0]) if len(matches)==1 else self.reply(409, {'error':'no unique match'})
            if self.path != '/consents/upsert':
                return self.reply(404, {'error':'unknown route'})
            if not isinstance(body, list) or not 1 <= len(body) <= 2:
                return self.reply(400, {'error':'expected one member consent array'})
            targets = [entry.get('consentTarget') for entry in body]
            if len(set(targets)) != len(targets):
                return self.reply(400, {'error':'duplicate consent target'})
            for entry in body:
                target = entry.get('consentTarget')
                if target not in ('PCDS', 'P2PDS') or entry.get('consentStatus') not in ('GRANT', 'RVOKE'):
                    return self.reply(400, {'error':'invalid consent'})
                if target == 'PCDS' and 'coverage' in entry:
                    return self.reply(400, {'error':'provider coverage shape'})
            key = self.headers.get('Idempotency-Key')
            if not key:
                return self.reply(400, {'error':'missing key'})
            with LOCK:
                if key in SEEN:
                    return self.reply(200 if SEEN[key]==body else 409, {'duplicate':True})
                if ARGS.fail_once_target in targets and ARGS.fail_once_target not in FAILED:
                    FAILED.add(ARGS.fail_once_target)
                    return self.reply(503, {'error':'synthetic transient failure'})
                SEEN[key] = body
                ARGS.capture.parent.mkdir(parents=True, exist_ok=True)
                with ARGS.capture.open('a', encoding='utf-8') as f:
                    f.write(json.dumps({'idempotencyKey':key,'request':body})+'\n')
            print(f'Accepted synthetic member request with {len(body)} consent entries', flush=True)
            self.reply(201, {'accepted':True})
        except (ValueError, TypeError, AttributeError):
            self.reply(400, {'error':'invalid JSON request'})

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8082)
    parser.add_argument('--capture',type=Path,default=ROOT/'var-consent-mock/received.jsonl')
    parser.add_argument('--fail-once-target',choices=['PCDS','P2PDS'])
    ARGS = parser.parse_args()
    print(f'Synthetic services at http://127.0.0.1:{ARGS.port}',flush=True)
    print('Idempotency memory lasts only until this demo server exits.',flush=True)
    ThreadingHTTPServer(('127.0.0.1',ARGS.port),Handler).serve_forever()
