#!/usr/bin/env python3
import argparse
import json
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--state-file", required=True)
    args = parser.parse_args()

    root = Path(args.root).resolve()
    state_file = Path(args.state_file).resolve()

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *handler_args, **handler_kwargs):
            super().__init__(*handler_args, directory=str(root), **handler_kwargs)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path != "/probe-state":
                super().do_GET()
                return
            query = parse_qs(parsed.query)
            try:
                state = {
                    "touch": int(query["touch"][0]),
                    "scroll": int(query["scroll"][0]),
                }
            except (KeyError, IndexError, ValueError):
                self.send_error(400, "touch and scroll must be integers")
                return
            temporary = state_file.with_suffix(".tmp")
            temporary.write_text(json.dumps(state), encoding="utf-8")
            os.replace(temporary, state_file)
            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

        def log_message(self, _format: str, *_args: object) -> None:
            return

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
