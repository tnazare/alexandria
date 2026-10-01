"""Local embeddings for semantic subset construction (task 2).

Embeds each superset skill's ``name + description`` with a pinned local sentence-transformers model
and caches the vectors as committed data under ``corpus/embeddings/``. This runs offline (after the
model is downloaded once) and is deterministic: a fixed model, no sampling, L2-normalized vectors.

The cache is the permanent artifact — the subset builder (task 3) loads it with **no model
dependency**, so rebuilding subsets or running the experiment never needs the embedding stack.
Only regenerating the embeddings needs ``requirements-embeddings.txt``.

We use **model2vec** (static distilled embeddings): real local semantic vectors with pure-numpy
inference and no torch — necessary because torch has no wheel for the Python 3.14 interpreter here.
The pinned model is ``minishlab/potion-base-8M``. Vectors are L2-normalized so cosine == dot product.

Cache format (``corpus/embeddings/embeddings.json``):
    {
      "model": "minishlab/potion-base-8M",
      "dim": 256,
      "superset_size": 500,
      "vectors": { "<slug>": [float, ...], ... }   # L2-normalized
    }
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger("harness.embeddings")

# Pinned static-embedding model: local, pure-numpy inference (no torch), deterministic.
MODEL_ID = "minishlab/potion-base-8M"
EMBEDDINGS_DIR = "corpus/embeddings"
SUPERSET_MANIFEST = "corpus/superset/manifest.json"


def embed_text(text: str) -> str:
    """The text we embed per skill: name + description (what a resolver/model sees to pick a skill)."""
    return text


def _skill_text(skill: dict) -> str:
    name = (skill.get("name") or "").strip()
    desc = (skill.get("description") or "").strip()
    return f"{name}. {desc}".strip()


def build_embeddings(superset_manifest: str | Path = SUPERSET_MANIFEST,
                     out_dir: str | Path = EMBEDDINGS_DIR,
                     model_id: str = MODEL_ID) -> Path:
    """Embed all superset skills and write the committed cache. Needs model2vec + numpy."""
    import numpy as np
    from model2vec import StaticModel

    manifest = json.loads(Path(superset_manifest).read_text(encoding="utf-8"))
    skills = manifest["skills"]
    slugs = [s["slug"] for s in skills]
    texts = [_skill_text(s) for s in skills]

    log.info("loading model %s", model_id)
    model = StaticModel.from_pretrained(model_id)
    log.info("embedding %d skills", len(texts))
    vecs = model.encode(texts, show_progress_bar=False)
    # L2-normalize so cosine similarity == dot product; deterministic (static embeddings).
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    vecs = vecs / norms

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    cache = {
        "model": model_id,
        "dim": int(vecs.shape[1]),
        "superset_size": len(slugs),
        # round to 6 decimals so the committed JSON is stable/diffable across machines.
        "vectors": {slug: [round(float(x), 6) for x in vec] for slug, vec in zip(slugs, vecs)},
    }
    cache_path = out / "embeddings.json"
    cache_path.write_text(json.dumps(cache), encoding="utf-8")
    log.info("wrote %d vectors (dim=%d) -> %s", len(slugs), cache["dim"], cache_path)
    return cache_path


def load_embeddings(out_dir: str | Path = EMBEDDINGS_DIR) -> dict:
    """Load the frozen embeddings cache (no torch needed). Returns the full cache dict."""
    return json.loads((Path(out_dir) / "embeddings.json").read_text(encoding="utf-8"))


def main() -> int:
    import argparse

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Embed the superset skills (task 2).")
    parser.add_argument("--superset", default=SUPERSET_MANIFEST)
    parser.add_argument("--out", default=EMBEDDINGS_DIR)
    parser.add_argument("--model", default=MODEL_ID)
    args = parser.parse_args()

    path = build_embeddings(args.superset, args.out, args.model)
    cache = json.loads(Path(path).read_text(encoding="utf-8"))
    print(f"Embedded {cache['superset_size']} skills, dim={cache['dim']}, model={cache['model']} -> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
