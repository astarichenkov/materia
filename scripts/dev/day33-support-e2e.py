#!/usr/bin/env python3
"""Real E2E driver for the Day 33 support assistant.

Talks to a running Lecture Processor instance over HTTP: opens /support to get a
session + CSRF token, then asks questions through /api/support/ask and prints the
grounded answer, tools and citations as UTF-8 JSON.

Usage: python scripts/dev/day33-support-e2e.py [base_url]
"""
import json
import re
import sys
import urllib.request
import http.cookiejar

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18082"
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf = {"token": None}


def get_csrf():
    with opener.open(BASE + "/support", timeout=30) as response:
        html = response.read().decode("utf-8", "replace")
    match = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    csrf["token"] = match.group(1) if match else None
    return csrf["token"]


def ask(question, ticket_id=None, lecture_id=None):
    body = {"question": question, "ticketId": ticket_id, "lectureId": lecture_id, "sessionId": "e2e"}
    request = urllib.request.Request(
        BASE + "/api/support/ask",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Support-Csrf": csrf["token"] or ""},
        method="POST",
    )
    try:
        with opener.open(request, timeout=180) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"httpStatus": e.code, "body": e.read().decode("utf-8", "replace")}


def summarise(label, answer):
    print("=" * 70)
    print(label)
    print("-" * 70)
    if "answer" not in answer:
        print(json.dumps(answer, ensure_ascii=False, indent=2))
        return
    print("Q:", label)
    print("A:", answer["answer"])
    print("grounded:", answer.get("grounded"), "| provider:", answer.get("provider"),
          "/", answer.get("model"), "| cloud:", answer.get("cloud"))
    print("ticketId:", answer.get("ticketId"), "| durationMs:", answer.get("durationMs"),
          "| tokens:", answer.get("inputTokens"), "/", answer.get("outputTokens"))
    print("tools:", [(t["name"], t["ok"]) for t in answer.get("toolsUsed", [])])
    print("citations:", [(c["kind"], c["source"]) for c in answer.get("citations", [])])
    print("limitations:", answer.get("limitations"))


def main():
    get_csrf()
    print("csrf:", "ok" if csrf["token"] else "MISSING")

    faq = ask("Как подключить DeepSeek и выбрать его для обработки лекций?")
    summarise("SCENARIO 1 FAQ (RAG)", faq)

    diag = ask("Почему обработка этой лекции остановилась?", lecture_id="e2e-day33-failed")
    summarise("SCENARIO 2 failed lecture (diagnostics)", diag)

    if diag.get("ticketId"):
        continuation = ask("А Whisper придётся запускать заново?", ticket_id=diag["ticketId"])
        summarise("SCENARIO 3 ticket continuation", continuation)
    else:
        print("SCENARIO 3 skipped: no ticket id from scenario 2")

    missing = ask("Почему задача e2e-nonexistent не обработалась?")
    summarise("SCENARIO 5 insufficient evidence", missing)

    metrics_req = urllib.request.Request(BASE + "/api/support/metrics")
    with opener.open(metrics_req, timeout=30) as response:
        metrics = json.loads(response.read().decode("utf-8"))
    print("=" * 70)
    print("METRICS:", json.dumps(metrics, ensure_ascii=False))


if __name__ == "__main__":
    main()
