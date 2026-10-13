# Homelab deployment — private AI chat (Day 30)

Real deployment of the `private-ai-chat` module on a dedicated CPU-only
Debian 12 LXC (`private-ai`, `203.0.113.10`). Native systemd, no Docker.

```
LAN client
  │  :8080            (0.0.0.0, requires X-API-Key for /api/chat)
  ▼
Spring Boot private-ai-chat   (systemd: private-ai.service, user private-ai)
  │  :11434           (127.0.0.1 only)
  ▼
Ollama  →  qwen3:1.7b   (CPU inference)
```

Security invariants:

- Ollama binds to `127.0.0.1:11434` and must never be reachable from the LAN.
- The API (`POST /api/chat`) requires `X-API-Key` / `Authorization: Bearer`.
- `GET /api/health` and the browser UI on `/` stay open on the private LAN.
- No secret in the repository; the key lives in `/etc/private-ai/private-ai.env`
  (`chmod 600`, root-owned).
- No router port forwarding; private LAN only.

## Files

| File | Purpose |
|------|---------|
| `install.sh` | Idempotent host preparation: Java 25, Ollama (+ loopback drop-in), model, service user/dirs. |
| `deploy.sh` | Installs the JAR, production config, generated API key and the systemd unit. |
| `verify.sh` | Real acceptance checks: health, auth, input limit, chat, context, rate limit, Ollama isolation. |
| `private-ai.service.template` | systemd unit for the chat service. |
| `ollama-override.conf.template` | systemd drop-in that pins Ollama to loopback and single-worker CPU. |

## 1. Prepare the host

Copy the `deployment/homelab` directory to the server, then:

```bash
sudo ./install.sh all        # detect, Java 25, Ollama, qwen3:1.7b, user/dirs
```

Individual steps: `detect`, `java`, `ollama`, `model`, `dirs`.

## 2. Build the JAR (on a machine with JDK 25)

```bash
JAVA_HOME=/path/to/jdk-25 ./mvnw -pl private-ai-chat -am package -DskipTests
# -> private-ai-chat/target/private-ai-chat-1.0.0-SNAPSHOT.jar
```

Or run the full regression suite: `./mvnw verify`.

## 3. Deploy

Transfer the JAR plus this directory to the server and run:

```bash
sudo ./deploy.sh /path/to/private-ai-chat-1.0.0-SNAPSHOT.jar
```

`deploy.sh` writes:

- `/opt/private-ai/private-ai-chat.jar` (service-owned, 0640)
- `/etc/private-ai/application.yml` (production limits, bind `0.0.0.0:8080`)
- `/etc/private-ai/private-ai.env` (generated `CHAT_API_KEY`, 0600, never printed)
- `/etc/systemd/system/private-ai.service`

then reloads systemd, enables and (re)starts the service and waits for health.

The API key is only ever stored in `/etc/private-ai/private-ai.env`. To use it
from a shell on the server:

```bash
. /etc/private-ai/private-ai.env
```

## 4. Verify

From the server:

```bash
sudo /path/to/verify.sh http://127.0.0.1:8080
```

From a LAN client (replace `<key>`):

```bash
curl -H "X-API-Key: <key>" http://203.0.113.10:8080/api/health
curl -H "X-API-Key: <key>" -H "Content-Type: application/json" \
     -d '{"message":"привет"}' http://203.0.113.10:8080/api/chat
```

Port expectations from the LAN:

| Port | Expected |
|------|----------|
| 22   | OPEN |
| 8080 | OPEN |
| 11434 | CLOSED / REFUSED |

If a non-rate-limit check unexpectedly returns `429`, the 60 s fixed window was
already consumed — wait a minute and re-run.

## 5. Operations

```bash
systemctl status ollama private-ai
journalctl -u private-ai -f
systemctl restart ollama private-ai
systemctl is-enabled ollama private-ai
```

Heap: `-Xms128m -Xmx512m` (see `private-ai.service.template`). Ollama keeps the
model loaded for 10 minutes (`OLLAMA_KEEP_ALIVE=10m`).

## 6. Optional Docker Compose (not used in the real deployment)

`compose.yaml` and `Dockerfile` are kept for a container-based alternative. The
verified Day 30 deployment is native systemd.
