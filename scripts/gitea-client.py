#!/usr/bin/env python3
"""Minimal, safety-focused Gitea REST API client.

Design goals (see AGENTS.md -> "Security Policy — Gitea Access"):

* Read GITEA_URL / GITEA_TOKEN from the environment, or from a local .env
  file when the variables are not set.
* Never print or log the token; only safe diagnostics.
* Send the token exclusively in the `Authorization` header (never in the URL,
  query string, argv or request body).
* Restrict outbound requests to a trusted Gitea host (local network hosts),
  so the token can never be exfiltrated to a third-party server.
* Use explicit connect/read timeouts and handle errors gracefully.

This tool deliberately does NOT touch OS credential stores, `cmdkey`,
`git credential`, or any other credential-discovery mechanism. If the token
is missing it stops with a clear error.

Usage:
    python scripts/gitea-client.py whoami
    python scripts/gitea-client.py version
    python scripts/gitea-client.py repos [--limit 50]
    python scripts/gitea-client.py get /repos/example-org/materia

Exit codes:
    0 success, 1 usage/config error, 2 network/API error, 3 trust error.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Hosts allowed to receive the token. Only the trusted Gitea instance and
# loopback addresses are permitted by default. Override intentionally with
# GITEA_TRUSTED_HOSTS (comma-separated) if the instance moves.
DEFAULT_TRUSTED_HOSTS = {"localhost", "127.0.0.1", "::1"}

DEFAULT_BASE_URL = "https://gitea.example.com"
CONNECT_TIMEOUT_S = 5
READ_TIMEOUT_S = 15
# urllib applies a single socket timeout for connect+read; use the larger of
# the two so reads are not cut short (connect is guarded by the OS anyway).
REQUEST_TIMEOUT_S = max(CONNECT_TIMEOUT_S, READ_TIMEOUT_S)


class ConfigError(Exception):
    """Missing/invalid configuration (no secret values in message)."""


class TrustError(Exception):
    """Destination host is not in the trusted allow-list."""


def load_dotenv(path: str = ".env") -> None:
    """Populate os.environ from a simple KEY=VALUE .env file.

    Existing environment variables always win. Values are never echoed.
    Supports `export KEY=VALUE`, comments and optional surrounding quotes.
    """
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):]
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip().strip("'\"")
            if key and key not in os.environ:
                os.environ[key] = value


def trusted_hosts() -> set[str]:
    override = os.environ.get("GITEA_TRUSTED_HOSTS")
    if override:
        return {h.strip() for h in override.split(",") if h.strip()}
    return set(DEFAULT_TRUSTED_HOSTS)


def resolve_base_url() -> str:
    return os.environ.get("GITEA_URL", DEFAULT_BASE_URL).rstrip("/")


def assert_trusted(base_url: str) -> None:
    host = urllib.parse.urlparse(base_url).hostname
    if not host:
        raise ConfigError("GITEA_URL is not a valid URL")
    if host not in trusted_hosts():
        raise TrustError(
            "refusing to send credentials to untrusted host %r; "
            "add it to GITEA_TRUSTED_HOSTS only if you trust it" % host
        )


def require_token() -> str:
    token = os.environ.get("GITEA_TOKEN")
    if not token:
        raise ConfigError(
            "GITEA_TOKEN is not set. Per security policy the operation stops "
            "here; no alternative credential source is used."
        )
    return token


def api_get(base_url: str, path: str, token: str, params: dict | None = None):
    if not path.startswith("/"):
        path = "/" + path
    url = base_url + "/api/v1" + path
    if params:
        url += "?" + urllib.parse.urlencode(params)

    request = urllib.request.Request(url, method="GET")
    # Token only in the header; never in the URL.
    request.add_header("Authorization", "token " + token)
    request.add_header("Accept", "application/json")

    return urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_S)


def cmd_version(base_url: str, _args) -> int:
    # Version endpoint is public but goes through the same trusted host check.
    with api_get(base_url, "/version", require_token()) as response:
        data = json.load(response)
    print("Gitea version:", data.get("version", "?"))
    return 0


def cmd_whoami(base_url: str, _args) -> int:
    with api_get(base_url, "/user", require_token()) as response:
        data = json.load(response)
    print("login=%s id=%s admin=%s" % (
        data.get("login"), data.get("id"), data.get("is_admin")))
    return 0


def cmd_repos(base_url: str, args) -> int:
    with api_get(
        base_url, "/user/repos", require_token(), {"limit": args.limit}
    ) as response:
        data = json.load(response)
    print("repositories=%d" % len(data))
    for repo in data:
        print(" - %s private=%s" % (repo.get("full_name"), repo.get("private")))
    return 0


def cmd_get(base_url: str, args) -> int:
    with api_get(base_url, args.path, require_token()) as response:
        data = json.load(response)
    print(json.dumps(data, indent=2, ensure_ascii=False))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Safe Gitea REST API client (token from environment only)."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("version", help="print Gitea version")
    sub.add_parser("whoami", help="print authenticated user")
    repos = sub.add_parser("repos", help="list repositories of current user")
    repos.add_argument("--limit", type=int, default=50)
    get = sub.add_parser("get", help="GET an /api/v1 path and print JSON")
    get.add_argument("path", help="path after /api/v1, e.g. /repos/owner/name")
    return parser


def main(argv: list[str] | None = None) -> int:
    # Local .env is a convenience only; environment variables take precedence.
    load_dotenv()
    args = build_parser().parse_args(argv)

    try:
        base_url = resolve_base_url()
        assert_trusted(base_url)
        token = require_token()
    except (ConfigError, TrustError) as exc:
        # Safe message: never includes token or full environment.
        print("ERROR: %s" % exc, file=sys.stderr)
        return 1 if isinstance(exc, ConfigError) else 3

    if not base_url.startswith("https://"):
        print("WARN: Gitea is reached over plain HTTP (no TLS); "
              "acceptable only within a trusted local network.",
              file=sys.stderr)

    handlers = {
        "version": cmd_version,
        "whoami": cmd_whoami,
        "repos": cmd_repos,
        "get": cmd_get,
    }
    try:
        return handlers[args.command](base_url, args)
    except urllib.error.HTTPError as exc:
        print("ERROR: HTTP %s from Gitea (path=%s)" % (
            exc.code, args.command), file=sys.stderr)
        return 2
    except urllib.error.URLError as exc:
        # exc.reason may contain the URL but never the token (it is header-only).
        print("ERROR: cannot reach Gitea: %s" % exc.reason, file=sys.stderr)
        return 2
    except (TimeoutError, OSError) as exc:
        print("ERROR: network failure: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
