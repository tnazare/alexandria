"""Thin Alexandria API client for the harness (token minting + skill/team REST).

Interacts with a running Alexandria stack only through its public API — no product-code changes
(Requirement 7.2). Uses the documented mock-OIDC flow (``client_secret=any``) to obtain an
Org_Admin token, then the same skill/team endpoints the importer uses.

Uses only the standard library (``urllib``) so it runs anywhere the harness does, without extra
runtime deps.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

TIMEOUT = 30


class AlexandriaError(RuntimeError):
    pass


@dataclass(frozen=True)
class AlexandriaConfig:
    host: str
    oidc_token_url: str
    oidc_client_id: str
    oidc_client_secret: str
    team_name: str


def _request(method: str, url: str, *, token: Optional[str] = None,
             json_body: Optional[dict] = None, form: Optional[dict] = None) -> tuple[int, Any]:
    data = None
    headers = {}
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif form is not None:
        from urllib.parse import urlencode
        data = urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body.strip() else None)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail)
        except json.JSONDecodeError:
            pass
        return e.code, detail


class AlexandriaClient:
    def __init__(self, cfg: AlexandriaConfig) -> None:
        self.cfg = cfg
        self._token: Optional[str] = None

    def token(self) -> str:
        """Fetch (and cache) an Org_Admin OIDC token via the mock server (client_secret required)."""
        if self._token:
            return self._token
        status, resp = _request("POST", self.cfg.oidc_token_url, form={
            "grant_type": "client_credentials",
            "client_id": self.cfg.oidc_client_id,
            "client_secret": self.cfg.oidc_client_secret,
            "scope": "openid",
        })
        if status != 200 or not isinstance(resp, dict) or "access_token" not in resp:
            raise AlexandriaError(f"OIDC token request failed (HTTP {status}): {resp}")
        self._token = resp["access_token"]
        return self._token

    def health(self) -> bool:
        try:
            status, resp = _request("GET", f"{self.cfg.host}/actuator/health")
            return status == 200 and isinstance(resp, dict) and resp.get("status") == "UP"
        except Exception:
            return False

    def ensure_team(self) -> str:
        token = self.token()
        status, resp = _request("GET", f"{self.cfg.host}/api/teams?size=200", token=token)
        if status == 200 and isinstance(resp, dict):
            for team in resp.get("content", []):
                if team.get("name") == self.cfg.team_name:
                    return team["id"]
        status, resp = _request("POST", f"{self.cfg.host}/api/teams", token=token, json_body={
            "name": self.cfg.team_name,
            "description": "Skill-retrieval-benchmark corpus team",
        })
        if status in (200, 201) and isinstance(resp, dict) and "id" in resp:
            return resp["id"]
        if status == 409:  # already exists — re-fetch
            _, page = _request("GET", f"{self.cfg.host}/api/teams?size=200", token=token)
            for team in (page or {}).get("content", []):
                if team.get("name") == self.cfg.team_name:
                    return team["id"]
        raise AlexandriaError(f"Failed to ensure team (HTTP {status}): {resp}")

    def create_skill(self, name: str, description: str, team_id: str,
                     canonical_instructions: str) -> tuple[int, Any]:
        return _request("POST", f"{self.cfg.host}/api/skills", token=self.token(), json_body={
            "name": name,
            "description": description,
            "ownerTeamId": team_id,
            "canonicalInstructions": canonical_instructions,
        })

    def resolve_count(self, query: str, limit: int = 100) -> int:
        """Return how many candidates the resolver returns for a broad query (resolvable count)."""
        status, resp = _request("POST", f"{self.cfg.host}/api/agent/v1/skills/resolve",
                                token=self.token(), json_body={"query": query, "limit": limit})
        if status != 200 or not isinstance(resp, dict):
            raise AlexandriaError(f"resolve failed (HTTP {status}): {resp}")
        return len(resp.get("results", []))
