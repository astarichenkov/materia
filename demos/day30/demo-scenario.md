# Demo scenario — Day 30 (real homelab deployment)

Video target: **3–6 minutes**. No long benchmarking.

Host: Proxmox LXC `private-ai` (`203.0.113.10`), Debian 12, 6 vCPU / 8 GB RAM,
no GPU. Model `qwen3:1.7b` via Ollama, CPU inference. Spring Boot 4.1.1 on Java 25.

## 1. Show the LXC (Proxmox) — ~20 s

On the Proxmox UI show container `private-ai` running. Do **not** change anything.
(If Proxmox UI cannot be shown, skip to the SSH view.)

## 2. SSH into the server — ~30 s

```bash
ssh root@203.0.113.10
```

```bash
systemctl status ollama --no-pager | head -5
systemctl status private-ai --no-pager | head -5
ollama list
ollama ps          # qwen3:1.7b, 100% CPU, context 4096
```

## 3. Prove Ollama is private — ~25 s

From the laptop:

```bash
curl --max-time 5 http://203.0.113.10:11434/api/tags   # refused
curl http://203.0.113.10:8080/api/health               # 200 UP
```

Point out: only `8080` is exposed; `11434` is loopback-only.

## 4. Chat in the browser — ~60 s

Open `http://203.0.113.10:8080/`. Ask:

> Объясни двумя предложениями, что такое локальная языковая модель.

Then a follow-up that requires memory:

> Какой язык программирования я упоминал ранее? … (or: «Какой язык я только что назвал?»)

Show the answer uses the earlier context.

## 5. Show the API — ~45 s

```bash
export KEY=$(ssh root@203.0.113.10 '. /etc/private-ai/private-ai.env; echo $CHAT_API_KEY')

# no key -> 401
curl -i -s -X POST -H 'Content-Type: application/json' \
     -d '{"message":"привет"}' http://203.0.113.10:8080/api/chat | head -1

# with key -> 200
curl -s -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"message":"Скажи одним словом: привет"}' http://203.0.113.10:8080/api/chat
```

## 6. Show the limits — ~45 s

```bash
# oversized -> 400
python3 -c "print('x'*2001)" > /tmp/big
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "X-API-Key: $KEY" \
     -H 'Content-Type: application/json' --data-binary @/tmp/big \
     http://203.0.113.10:8080/api/chat

# rate limit -> 429
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "$i:%{http_code} " -X POST -H "X-API-Key: $KEY" \
       -H 'Content-Type: application/json' -d '{"message":"hi"}' \
       http://203.0.113.10:8080/api/chat
done; echo
```

## 7. Stability + resources — ~45 s

```bash
# 5 sequential requests (after the rate window resets)
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "req $i: %{http_code} %{time_total}s\n" \
    -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
    -d "{\"message\":\"Reply with one word: thanks $i\"}" \
    http://203.0.113.10:8080/api/chat
done

# while one request runs, show CPU/RAM
ollama ps
free -h
top -bn1 | head -8
```

## 8. Architecture recap — ~20 s

```
LAN client → :8080 Spring Boot (auth, limits, 1 worker + bounded queue)
           → 127.0.0.1:11434 Ollama → qwen3:1.7b (CPU)
```

## Expected / verified results

- Health 200 over the LAN; `11434` refused from the LAN.
- Real answers from `qwen3:1.7b`; follow-up remembers context.
- 401 without a key, 200 with it.
- 2001 chars → 400; exactly 2000 chars → 200.
- Rate limit → 429.
- 5 concurrent requests: all 200, serialized (queue drains), service stays up.
- Cold ~7 s, warm ~4 s; llama-server uses ~5.5–6 cores; RAM ~2 GB with model loaded.

Full numbers: `docs/DAY30_REAL_DEPLOYMENT_REPORT.md`.
