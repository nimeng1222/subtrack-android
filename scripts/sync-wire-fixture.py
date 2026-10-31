#!/usr/bin/env python3
"""Local protocol fixture; synthetic setup token, temporary database, no user data."""
import signal
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from app import create_server

with tempfile.TemporaryDirectory(prefix="subtrack-sync-wire-") as directory:
    server = create_server(str(Path(directory) / "store.sqlite"), ("127.0.0.1", 18081), "S" * 40, True)
    def stop(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    try:
        print("isolated sync wire fixture ready", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        server.store.db.close()
