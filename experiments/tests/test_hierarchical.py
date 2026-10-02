"""Unit tests for hierarchical disclosure: deterministic categorization + the token model.

The categorization tests build a tiny synthetic embeddings cache (clearly separated clusters) so the
k-means result is unambiguous and no committed data or embedding model is needed. The token-model
tests use a trivial whitespace counter.
"""

from __future__ import annotations

import math

from harness.categorize import Categorization, Category, categorize, suggest_k
from harness.measure_tokens import (
    category_header_line,
    hierarchical_resident_tokens,
    hierarchical_task_tokens,
)


def wc(text: str) -> int:
    return len(text.split())


def _two_cluster_cache():
    """Six skills in two obvious clusters along orthogonal axes (so k=2 is unambiguous)."""
    vectors = {
        "pdf-extract": [1.0, 0.0], "pdf-merge": [0.98, 0.02], "pdf-split": [0.96, 0.0],
        "auth-oidc": [0.0, 1.0], "auth-saml": [0.02, 0.98], "auth-scim": [0.0, 0.96],
    }
    return {"model": "test", "dim": 2, "vectors": vectors}


class TestCategorize:
    def test_splits_two_obvious_clusters(self):
        cache = _two_cluster_cache()
        cat = categorize(list(cache["vectors"]), k=2, seed=1234, cache=cache)
        assert cat.k == 2
        groups = {frozenset(c.slugs) for c in cat.categories}
        assert frozenset({"pdf-extract", "pdf-merge", "pdf-split"}) in groups
        assert frozenset({"auth-oidc", "auth-saml", "auth-scim"}) in groups

    def test_is_deterministic(self):
        cache = _two_cluster_cache()
        a = categorize(list(cache["vectors"]), k=2, seed=1234, cache=cache)
        b = categorize(list(cache["vectors"]), k=2, seed=1234, cache=cache)
        assert [(c.label, c.slugs) for c in a.categories] == [(c.label, c.slugs) for c in b.categories]

    def test_labels_are_unique(self):
        cache = _two_cluster_cache()
        cat = categorize(list(cache["vectors"]), k=2, seed=1234, cache=cache)
        labels = [c.label for c in cat.categories]
        assert len(labels) == len(set(labels))

    def test_slug_to_label_covers_every_slug(self):
        cache = _two_cluster_cache()
        cat = categorize(list(cache["vectors"]), k=2, seed=1234, cache=cache)
        mapping = cat.slug_to_label()
        assert set(mapping) == set(cache["vectors"])

    def test_k_clamped_to_n(self):
        cache = _two_cluster_cache()
        cat = categorize(list(cache["vectors"]), k=99, seed=1, cache=cache)
        # Cannot have more non-empty categories than skills.
        assert cat.k <= len(cache["vectors"])

    def test_suggest_k_is_sublinear(self):
        # ~sqrt(N), clamped to [2, 24]: grows far slower than N.
        assert suggest_k(1) == 2
        assert suggest_k(100) == 10
        assert suggest_k(400) == 20
        assert suggest_k(10_000) == 24          # clamp
        assert suggest_k(500) <= math.ceil(math.sqrt(500))


class TestHierarchicalTokenModel:
    def _headers(self, n_categories: int, members_each: int = 5):
        return [(f"cat{i}", members_each, f"summary {i}") for i in range(n_categories)]

    def test_resident_is_only_headers_plus_schemas(self):
        headers = self._headers(4)
        resident = hierarchical_resident_tokens(wc, headers)
        # Resident scales with the number of CATEGORIES, not the number of skills.
        more = hierarchical_resident_tokens(wc, self._headers(8))
        assert more > resident

    def test_resident_grows_with_categories_not_skills(self):
        # Same category count, wildly different member counts -> header line text is nearly identical,
        # so resident cost barely moves (member_count is a small number in the header).
        few = hierarchical_resident_tokens(wc, self._headers(5, members_each=3))
        many = hierarchical_resident_tokens(wc, self._headers(5, members_each=400))
        assert abs(many - few) <= 5     # only the count digits differ

    def test_task_adds_opened_category_lines_and_body(self):
        headers = self._headers(5)
        opened = ["pdf-extract: PDF Extract — pull text", "pdf-merge: PDF Merge — combine"]
        bd = hierarchical_task_tokens(wc, headers, opened_category_lines=opened,
                                      fetched_body="a b c d")
        assert bd.resident == hierarchical_resident_tokens(wc, headers)
        assert bd.per_task == wc("\n".join(opened)) + wc("a b c d")
        assert bd.total == bd.resident + bd.per_task

    def test_task_zero_per_task_when_nothing_opened(self):
        headers = self._headers(5)
        bd = hierarchical_task_tokens(wc, headers, opened_category_lines=[], fetched_body="")
        assert bd.per_task == 0
        assert bd.total == bd.resident

    def test_header_line_shapes(self):
        assert category_header_line("auth", 3, "login identity") == "auth (3): login identity"
        assert category_header_line("auth", 3) == "auth (3)"
