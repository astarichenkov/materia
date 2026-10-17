#!/usr/bin/env python3
"""Real E2E driver for the Day 34 autonomous file agent.

Requires a running backend and a configured admin token (all file-agent API
routes are admin/CSRF protected). The script creates a natural-language task,
polls the orchestrator, prints the plan/tool trace/diff, optionally approves the
proposal, and verifies the files on disk by SHA-256.

Example:
    PYTHONIOENCODING=utf-8 python scripts/dev/day34-file-agent-e2e.py \
        --base-url http://127.0.0.1:18083 \
        --admin-token "$LP_ADMIN_TOKEN" \
        --project-root C:/Projects/lecture-processor-e2e \
        --goal "Найди все места, где используется LlmTaskSettingsService, и обнови документацию." \
        --approve
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

TERMINAL = {"COMPLETED", "FAILED", "REJECTED", "CANCELLED", "CONFLICT"}


def configure_stdout() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def request(base: str, method: str, path: str, token: str, body: dict | None = None) -> dict:
    data = None
    headers = {"X-Admin-Token": token, "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {error.code} for {method} {path}: {detail}")


def wait_for_task(base: str, token: str, task_id: str, timeout_seconds: int) -> dict:
    deadline = time.time() + timeout_seconds
    last = None
    while time.time() < deadline:
        task = request(base, "GET", f"/api/file-agent/tasks/{task_id}", token)
        status = task.get("status")
        if status != last:
            print(f"[poll] {status}")
            last = status
        if status in TERMINAL or status == "AWAITING_APPROVAL":
            return task
        time.sleep(2)
    raise SystemExit(f"Task {task_id} did not finish within {timeout_seconds}s")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    configure_stdout()
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("FILE_AGENT_BASE_URL",
                                                             "http://127.0.0.1:18083"))
    parser.add_argument("--admin-token", default=os.environ.get("LP_ADMIN_TOKEN", ""))
    parser.add_argument("--project-root", default=os.environ.get("FILE_AGENT_PROJECT_ROOT", ""))
    parser.add_argument("--goal", required=True)
    parser.add_argument("--workspace", default="PROJECT_WORKSPACE")
    parser.add_argument("--lecture-id", default=None)
    parser.add_argument("--timeout", type=int, default=360)
    parser.add_argument("--approve", action="store_true")
    parser.add_argument("--reject", action="store_true")
    parser.add_argument("--output", default=None)
    args = parser.parse_args()
    if not args.admin_token:
        raise SystemExit("LP_ADMIN_TOKEN (or --admin-token) is required: file-agent API is admin"
                         " protected and CSRF is bypassed only for the admin token header")

    task = request(args.base_url, "POST", "/api/file-agent/tasks", args.admin_token, {
        "goal": args.goal,
        "workspace": args.workspace,
        "lectureId": args.lecture_id,
    })
    task_id = task["id"]
    print(f"task: {task_id}")
    task = wait_for_task(args.base_url, args.admin_token, task_id, args.timeout)
    result = {
        "task": task,
        "proposals": request(args.base_url, "GET", f"/api/file-agent/tasks/{task_id}/proposals",
                             args.admin_token),
        "trace": request(args.base_url, "GET", f"/api/file-agent/tasks/{task_id}/trace",
                         args.admin_token),
    }
    print(json.dumps({
        key: task.get(key) for key in (
            "id", "status", "provider", "model", "filesSearched", "filesRead",
            "filesProposed", "filesChanged", "toolCalls", "toolFailures",
            "planningMs", "durationMs", "inputTokens", "outputTokens", "error",
            "relevantFiles")
    }, ensure_ascii=False, indent=2))

    if task["status"] != "AWAITING_APPROVAL":
        _write(args.output, result)
        return 1 if task["status"] in TERMINAL else 0

    proposals = result["proposals"]
    if not proposals:
        print("no proposals")
        _write(args.output, result)
        return 1
    proposal = proposals[0]
    print(f"\nproposal #{proposal['id']} version={proposal['version']} "
          f"status={proposal['status']}")
    print(f"summary: {proposal['summary']}")
    print("\n--- DIFF ---")
    print(proposal["diff"])
    print("\n--- TOOL TRACE ---")
    for call in result["trace"]:
        print(f"{call['toolName']} ok={call['ok']} {call['durationMs']}ms "
              f"{call['summary'][:160]}")
    result["changes"] = request(args.base_url, "GET",
                                 f"/api/file-agent/proposals/{proposal['id']}/changes",
                                 args.admin_token)

    if args.reject:
        result["reject"] = request(args.base_url, "POST",
                                   f"/api/file-agent/proposals/{proposal['id']}/reject",
                                   args.admin_token, {"reason": "e2e reject"})
        print("\nrejected")
        _write(args.output, result)
        return 0

    if not args.approve:
        _write(args.output, result)
        print("\n(no approval requested; files were not changed)")
        return 0

    result["apply"] = request(args.base_url, "POST",
                              f"/api/file-agent/proposals/{proposal['id']}/approve",
                              args.admin_token)
    print("\n--- APPLY ---")
    print(json.dumps(result["apply"], ensure_ascii=False, indent=2))
    if not result["apply"].get("applied"):
        _write(args.output, result)
        return 1

    verified = []
    if args.project_root:
        root = Path(args.project_root)
        for change in result["changes"]:
            path = root / change["path"]
            exists = path.exists()
            actual = sha256(path) if exists else None
            ok = actual == change["newHash"]
            verified.append({"path": change["path"], "exists": exists,
                             "sha256": actual, "expected": change["newHash"], "ok": ok})
            print(f"verify {change['path']}: {'OK' if ok else 'MISMATCH'}")
    final_task = request(args.base_url, "GET", f"/api/file-agent/tasks/{task_id}", args.admin_token)
    result["finalTask"] = final_task
    result["verified"] = verified
    print(f"final status: {final_task['status']} verification={final_task.get('verificationStatus')}")
    _write(args.output, result)
    return 0 if final_task["status"] == "COMPLETED" and all(item["ok"] for item in verified) else 1


def _write(output: str | None, value: dict) -> None:
    if not output:
        return
    Path(output).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
