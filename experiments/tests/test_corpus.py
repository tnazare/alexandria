"""Unit tests for padding generation and corpus assembly.

Covers Requirements 2.4/9.5 (deterministic, seeded padding), 9.4 (padding never collides with
reserved slugs / never a gold answer), and 1.1/1.3 (stable content hash, identical tier sets that
drive both conditions).
"""

from __future__ import annotations

import pytest

from harness.build_corpus import (
    CorpusError,
    assemble,
    compute_content_hash,
)
from harness.generate_padding import generate, max_capacity
from harness.models import HarvestedSkill, Tier


def _real(slug: str) -> HarvestedSkill:
    body = f"---\nname: {slug}\ndescription: real skill {slug}\n---\n# {slug}\nBody.\n"
    return HarvestedSkill(
        slug=slug, name=slug, description=f"real {slug}", body=body, tier=Tier.REAL,
        source_url=f"https://src/{slug}", ref="ref1", license="MIT",
        sha256=f"sha-{slug}",
    )


def _distractor(slug: str) -> HarvestedSkill:
    body = f"---\nname: {slug}\ndescription: distractor {slug}\n---\n# {slug}\nBody.\n"
    return HarvestedSkill(
        slug=slug, name=slug, description=f"distractor {slug}", body=body, tier=Tier.DISTRACTOR,
        source_url=f"https://src/{slug}", ref="ref1", license="MIT",
        sha256=f"sha-{slug}",
    )


class TestPaddingDeterminism:
    def test_same_seed_same_output(self):
        a = generate(50, seed=1234)
        b = generate(50, seed=1234)
        assert [s.slug for s in a] == [s.slug for s in b]
        assert [s.sha256 for s in a] == [s.sha256 for s in b]

    def test_different_seed_different_order(self):
        a = generate(50, seed=1)
        b = generate(50, seed=2)
        # Same set size but (overwhelmingly likely) a different ordering/selection.
        assert [s.slug for s in a] != [s.slug for s in b]

    def test_prefix_stability_across_sizes(self):
        # generate(N) and generate(M) share the same shuffled walk for a fixed seed, so the smaller
        # is a prefix of the larger.
        small = generate(20, seed=1234)
        large = generate(60, seed=1234)
        assert [s.slug for s in small] == [s.slug for s in large[:20]]

    def test_exact_count(self):
        assert len(generate(37, seed=7)) == 37

    def test_slugs_unique(self):
        skills = generate(200, seed=99)
        slugs = [s.slug for s in skills]
        assert len(slugs) == len(set(slugs))

    def test_never_uses_reserved_slugs(self):
        # Reserve some slugs that would otherwise be generated; none may appear.
        reserved = {s.slug for s in generate(10, seed=1234)}
        skills = generate(50, seed=1234, reserved_slugs=reserved)
        assert reserved.isdisjoint({s.slug for s in skills})

    def test_raises_when_matrix_exhausted(self):
        with pytest.raises(ValueError, match="exhausted"):
            generate(max_capacity() + 1, seed=1)

    def test_can_reach_500(self):
        assert max_capacity() >= 500
        assert len(generate(500, seed=1234)) == 500


class TestCorpusAssembly:
    def test_assemble_reaches_target_and_tiers(self):
        real = [_real(f"r{i}") for i in range(5)]
        distractors = [_distractor(f"d{i}") for i in range(3)]
        manifest, padding = assemble(real, distractors, library_size=20, seed=1234)
        assert manifest.library_size == 20
        assert len(manifest.skills) == 20
        assert len(padding) == 20 - 5 - 3
        assert manifest.real_slugs() == {f"r{i}" for i in range(5)}
        tiers = {e.tier for e in manifest.skills}
        assert tiers == {Tier.REAL, Tier.DISTRACTOR, Tier.PADDING}

    def test_content_hash_is_stable_and_order_independent(self):
        real = [_real(f"r{i}") for i in range(5)]
        distractors = [_distractor("d0")]
        m1, _ = assemble(real, distractors, library_size=20, seed=1234)
        m2, _ = assemble(list(reversed(real)), distractors, library_size=20, seed=1234)
        # Same skills, different input order -> identical content hash.
        assert m1.content_hash == m2.content_hash

    def test_different_seed_changes_hash(self):
        real = [_real(f"r{i}") for i in range(5)]
        m1, _ = assemble(real, [], library_size=20, seed=1)
        m2, _ = assemble(real, [], library_size=20, seed=2)
        assert m1.content_hash != m2.content_hash

    def test_identical_corpus_for_both_conditions(self):
        # The single manifest drives both conditions; the (slug, sha256) set is what each condition
        # provisions. Re-assembling with the same inputs yields the identical set.
        real = [_real(f"r{i}") for i in range(5)]
        distractors = [_distractor("d0")]
        m1, _ = assemble(real, distractors, library_size=30, seed=1234)
        m2, _ = assemble(real, distractors, library_size=30, seed=1234)
        pairs1 = sorted((e.slug, e.sha256) for e in m1.skills)
        pairs2 = sorted((e.slug, e.sha256) for e in m2.skills)
        assert pairs1 == pairs2
        assert m1.content_hash == m2.content_hash

    def test_padding_never_collides_with_reserved(self):
        real = [_real("pdf-processing-python-stdlib")]  # deliberately shaped like a padding slug
        manifest, padding = assemble(real, [], library_size=40, seed=1234)
        assert "pdf-processing-python-stdlib" not in {p.slug for p in padding}
        # And the real slug is present exactly once.
        assert [e.slug for e in manifest.skills].count("pdf-processing-python-stdlib") == 1

    def test_rejects_size_below_base_count(self):
        real = [_real(f"r{i}") for i in range(5)]
        distractors = [_distractor(f"d{i}") for i in range(3)]
        with pytest.raises(CorpusError, match="smaller than"):
            assemble(real, distractors, library_size=6, seed=1234)

    def test_size_equal_to_base_count_uses_no_padding(self):
        real = [_real(f"r{i}") for i in range(5)]
        manifest, padding = assemble(real, [], library_size=5, seed=1234)
        assert padding == []
        assert all(e.tier == Tier.REAL for e in manifest.skills)


def test_compute_content_hash_prefix():
    from harness.models import ManifestEntry
    entries = [ManifestEntry(slug="a", tier=Tier.REAL, name="A", sha256="x")]
    assert compute_content_hash(entries).startswith("sha256:")
