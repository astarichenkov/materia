#!/usr/bin/env python3
"""Real HTTP smoke check for the Lecture Processor UI.

Verifies pages, build identity, lecture card rendering and (optionally) a local
upload. It does not delete data and does not touch cloud providers unless the
running application is configured to do so.

Usage:
  python scripts/smoke-ui.py --base-url http://127.0.0.1:8080
  python scripts/smoke-ui.py --base-url http://127.0.0.1:8080 --upload target/e2e-test.mp4
"""
import argparse
import json
import mimetypes
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path

PAGES = [
    ("/", ["Lecture Processor", "Добавить материал", "Найти видео"]),
    ("/library", ["Библиотека"]),
    ("/add", ["Локальный файл", "Видео по ссылке"]),
    ("/jobs", ["Задачи обработки"]),
    ("/discover", ["Smart Video Discovery"]),
    ("/diagnostics", ["Java runtime", "SQLite"]),
    ("/settings", ["Ollama", "DeepSeek"]),
    ("/assistant", ["Ассистент"]),
    ("/support", ["Support"]),
    ("/file-agent", ["File Agent"]),
    ("/code-review", ["Code Review"]),
    ("/benchmarks", ["Benchmark"]),
]


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def get(self, path, timeout=60):
        request = urllib.request.Request(self.base + path, method="GET")
        return self._open(request, timeout)

    def post(self, path, data, headers=None, timeout=300):
        request = urllib.request.Request(self.base + path, data=data, method="POST",
                                         headers=headers or {})
        return self._open(request, timeout)

    def _open(self, request, timeout):
        try:
            response = self.opener.open(request, timeout=timeout)
            return response.status, response.geturl(), response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.geturl(), error.read(), error.headers


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--upload", help="optional short MP4 to upload")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--json-out", help="write machine-readable report here")
    args = parser.parse_args()
    client = Client(args.base_url)
    report = []

    status, _, body, _ = client.get("/api/app/version", timeout=10)
    check(status == 200, f"/api/app/version -> {status}")
    version = json.loads(body.decode("utf-8"))
    print(f"VERSION {version['version']} commit={version['commitShort']} "
          f"java={version['javaVersion']} pid={version['pid']}")
    report.append({"check": "version", "status": status, "data": version})

    for path, markers in PAGES:
        status, _, body, _ = client.get(path, timeout=90)
        text = body.decode("utf-8", "replace")
        check(status == 200, f"{path} -> HTTP {status}: {text[:200]}")
        missing = [marker for marker in markers if marker not in text]
        check(not missing, f"{path} rendered but missing {missing}")
        print(f"OK {path} ({len(body)} bytes)")
        report.append({"check": path, "status": status, "bytes": len(body)})

    status, _, body, _ = client.get("/api/diagnostics")
    check(status == 200, f"/api/diagnostics -> {status}")
    diagnostics = json.loads(body.decode("utf-8"))
    for item in diagnostics.get("checks", []):
        print(f"DIAG {item['id']}: {item['status']} — {item['detail']}")
    report.append({"check": "/api/diagnostics", "status": status,
                   "ready": diagnostics.get("ready")})

    status, _, body, _ = client.get("/api/lectures")
    lectures = json.loads(body.decode("utf-8")) if status == 200 else []
    if lectures:
        lecture_id = lectures[0]["id"]
        status, _, body, _ = client.get(f"/lectures/{lecture_id}")
        text = body.decode("utf-8", "replace")
        check(status == 200, f"/lectures/{lecture_id} -> {status}: {text[:300]}")
        check("Статус:" in text, "lecture card has no status block")
        print(f"OK /lectures/{lecture_id} status={lectures[0]['status']}")
        status, _, body, _ = client.get(f"/lectures/{lecture_id}/status")
        check(status == 200, f"/lectures/{lecture_id}/status -> {status}")
        print("OK status fragment")
        report.append({"check": "lecture-card", "lectureId": lecture_id,
                       "status": lectures[0]["status"]})
    else:
        print("SKIP lecture card: library is empty")

    if args.upload:
        path = Path(args.upload)
        check(path.is_file(), f"fixture not found: {path}")
        boundary = "----lp-smoke-" + uuid.uuid4().hex
        content_type = mimetypes.guess_type(path.name)[0] or "video/mp4"
        parts = [
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{path.name}\"\r\nContent-Type: {content_type}\r\n\r\n".encode(),
            path.read_bytes(),
            f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"languageMode\"\r\n\r\n"
            "ENGLISH\r\n".encode(),
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"translationMode\"\r\n\r\n"
            "ORIGINAL_ONLY\r\n".encode(),
            f"--{boundary}--\r\n".encode(),
        ]
        status, url, body, _ = client.post(
            "/lectures", b"".join(parts),
            {"Content-Type": f"multipart/form-data; boundary={boundary}"})
        check(status == 200, f"upload follow-up -> {status}: {body[:300].decode('utf-8', 'replace')}")
        check("/lectures/" in url, f"upload did not redirect to a lecture: {url}")
        text = body.decode("utf-8", "replace")
        check("Статус:" in text, "uploaded lecture page has no status block")
        lecture_id = url.rstrip("/").split("/")[-1].split(";")[0]
        print(f"OK upload -> {url}")
        deadline = time.time() + args.timeout
        last = ""
        while time.time() < deadline:
            status, _, body, _ = client.get(f"/lectures/{lecture_id}/status", timeout=30)
            last = body.decode("utf-8", "replace")
            if re.search(r'data-lecture-status="(?:COMPLETED|PARTIAL|FAILED|CANCELLED)"', last):
                break
            time.sleep(3)
        print(f"UPLOAD final fragment: {' '.join(last.split())[:300]}")
        report.append({"check": "upload", "lectureId": lecture_id,
                       "final": last[:2000]})

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
    print("SMOKE OK")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"SMOKE FAILED: {error}", file=sys.stderr)
        sys.exit(1)
