#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import cgi
import json
import mimetypes
import os
from pathlib import Path
import time
from urllib.parse import unquote


UPLOAD_DIR = Path(os.environ.get("DIAGNOSTICS_UPLOAD_DIR", "/data/uploads"))
MAX_UPLOAD_BYTES = int(os.environ.get("DIAGNOSTICS_MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))
BASIC_USERNAME = os.environ.get("DIAGNOSTICS_BASIC_USERNAME", "shelfdrive")
BASIC_PASSWORD = os.environ.get("DIAGNOSTICS_BASIC_PASSWORD", "diagnostics")


class DiagnosticsHandler(BaseHTTPRequestHandler):
    server_version = "ShelfDriveDiagnostics/0.1"

    def do_GET(self):
        if not self.is_authorized():
            self.send_json(401, {"error": "unauthorized"})
            return
        if self.path == "/health":
            self.send_json(200, {"status": "ok"})
            return
        if self.path == "/":
            uploads = sorted(UPLOAD_DIR.glob("*.zip"), key=lambda path: path.stat().st_mtime, reverse=True)
            self.send_json(
                200,
                {
                    "status": "ok",
                    "uploads": [
                        {
                            "name": path.name,
                            "bytes": path.stat().st_size,
                            "createdAt": int(path.stat().st_mtime),
                            "downloadUrl": f"/uploads/{path.name}",
                        }
                        for path in uploads
                    ],
                },
            )
            return
        if self.path.startswith("/uploads/"):
            self.send_file(self.path.removeprefix("/uploads/"))
            return
        self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        if not self.is_authorized():
            self.send_json(401, {"error": "unauthorized"})
            return
        if self.path != "/upload":
            self.send_json(404, {"error": "not_found"})
            return
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0 or content_length > MAX_UPLOAD_BYTES:
            self.send_json(413, {"error": "upload_too_large"})
            return

        form = cgi.FieldStorage(
            fp=self.rfile,
            headers=self.headers,
            environ={
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE": self.headers.get("Content-Type"),
            },
        )
        upload = form["file"] if "file" in form else None
        if upload is None or not upload.filename:
            self.send_json(400, {"error": "missing_file"})
            return

        UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
        filename = safe_filename(upload.filename)
        output_name = f"{time.strftime('%Y%m%d-%H%M%S')}-{filename}"
        output_path = UPLOAD_DIR / output_name
        with output_path.open("wb") as handle:
            handle.write(upload.file.read())

        self.send_json(
            201,
            {
                "status": "stored",
                "name": output_name,
                "bytes": output_path.stat().st_size,
            },
        )

    def send_json(self, status, payload):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(status)
        if status == 401:
            self.send_header("WWW-Authenticate", 'Basic realm="ShelfDrive Diagnostics"')
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, raw_name):
        filename = safe_filename(unquote(raw_name))
        path = UPLOAD_DIR / filename
        if not path.is_file():
            self.send_json(404, {"error": "not_found"})
            return
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        size = path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Disposition", f'attachment; filename="{path.name}"')
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def is_authorized(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header.removeprefix("Basic ").strip()).decode("utf-8")
        except Exception:
            return False
        username, separator, password = decoded.partition(":")
        return separator == ":" and username == BASIC_USERNAME and password == BASIC_PASSWORD


def safe_filename(name):
    cleaned = "".join(char if char.isalnum() or char in ".-_" else "_" for char in name)
    return cleaned.strip("._") or "diagnostics.zip"


def main():
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), DiagnosticsHandler)
    print(f"Listening on 0.0.0.0:{port}, storing uploads in {UPLOAD_DIR}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
