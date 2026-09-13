"""Single, fixed tokenizer for the client-independent token metric (Requirement 3.1).

The same encoding is applied to both conditions so ``context_token_cost`` is comparable and
reproducible. This is deliberately a *comparable proxy*, not Claude's private tokenizer — the
protocol pairs it with Claude's own reported usage as corroboration (see PROTOCOL threats-to-validity).

Kept as a tiny wrapper so the rest of the harness never imports ``tiktoken`` directly and the choice
of encoding lives in exactly one place.
"""

from __future__ import annotations

from functools import lru_cache

DEFAULT_ENCODING = "cl100k_base"


@lru_cache(maxsize=4)
def _encoding(name: str):
    import tiktoken  # imported lazily so unit tests that stub counting need no native dep

    return tiktoken.get_encoding(name)


class Tokenizer:
    """Counts tokens with one fixed encoding. Instantiate once and reuse."""

    def __init__(self, encoding: str = DEFAULT_ENCODING) -> None:
        self.encoding_name = encoding
        self._enc = _encoding(encoding)

    def count(self, text: str) -> int:
        """Return the number of tokens in ``text`` (empty/None -> 0)."""
        if not text:
            return 0
        return len(self._enc.encode(text))

    def count_all(self, texts: list[str]) -> int:
        """Return the summed token count over several strings."""
        return sum(self.count(t) for t in texts)
