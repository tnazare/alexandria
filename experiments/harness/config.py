"""Load and expose the experiment configuration (``config/experiment.yaml``).

Central place that reads the YAML config into typed accessors, so the rest of the harness never
parses raw config dicts. Recorded verbatim into each run's ``env.json`` for reproducibility.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from harness.alexandria_client import AlexandriaConfig


@dataclass(frozen=True)
class ExperimentConfig:
    raw: dict[str, Any]

    @property
    def experiment_version(self) -> str:
        return str(self.raw.get("experiment_version", "v1"))

    @property
    def library_sizes(self) -> list[int]:
        return [int(n) for n in self.raw.get("library_sizes", [20])]

    @property
    def nrep(self) -> int:
        return int(self.raw.get("nrep", 5))

    @property
    def warmup(self) -> int:
        return int(self.raw.get("warmup", 1))

    @property
    def k(self) -> int:
        return int(self.raw.get("k", 5))

    @property
    def padding_seed(self) -> int:
        return int(self.raw.get("seeds", {}).get("padding", 1234))

    @property
    def order_seed(self) -> int:
        return int(self.raw.get("seeds", {}).get("order", 5678))

    @property
    def conditions(self) -> list[str]:
        return [str(c) for c in self.raw.get("conditions", ["alexandria", "native"])]

    @property
    def model_id(self) -> str | None:
        v = self.raw.get("model_id")
        return str(v) if v else None

    @property
    def tokenizer_encoding(self) -> str:
        return str(self.raw.get("tokenizer_encoding", "cl100k_base"))

    def alexandria(self) -> AlexandriaConfig:
        a = self.raw.get("alexandria", {})
        return AlexandriaConfig(
            host=str(a.get("host", "http://localhost:8080")),
            oidc_token_url=str(a.get("oidc_token_url", "http://localhost:9000/alexandria/token")),
            oidc_client_id=str(a.get("oidc_client_id", "alexandria-client")),
            oidc_client_secret=str(a.get("oidc_client_secret", "any")),
            team_name=str(a.get("team_name", "Benchmark Skills")),
        )

    @property
    def claude_command(self) -> str:
        return str(self.raw.get("claude", {}).get("command", "claude"))

    @property
    def claude_output_format(self) -> str:
        return str(self.raw.get("claude", {}).get("output_format", "json"))


def load_config(path: str | Path) -> ExperimentConfig:
    import yaml

    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    return ExperimentConfig(raw=raw)
