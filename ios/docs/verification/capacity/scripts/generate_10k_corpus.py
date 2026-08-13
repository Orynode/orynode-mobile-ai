#!/usr/bin/env python3
"""Generate a markdown corpus that yields exactly N StructuredKnowledgeChunker chunks.

Each `# Cxxxxx` section is kept well under 520 characters so default chunking
(520/64) produces one chunk per section.

Usage:
  python3 ios/docs/verification/capacity/scripts/generate_10k_corpus.py
  python3 ios/docs/verification/capacity/scripts/generate_10k_corpus.py --chunks 1000
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "generated"


def make_section(index: int) -> str:
    tag = f"CHUNK-{index:05d}"
    # Keep ASCII+CJK short: one chunk, unique retrieval anchors.
    return (
        f"# C{index:05d}\n"
        f"{tag} 离线容量闸门填充段。本段用于构造恰好一万个可检索 chunks，"
        f"不得上传文档，也不得调用云端搜索。\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chunks", type=int, default=10_000, help="Target chunk/section count")
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Output markdown path (default: generated/capacity_N.md)",
    )
    args = parser.parse_args()
    if args.chunks < 1:
        raise SystemExit("--chunks must be >= 1")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = args.out or (OUT_DIR / f"capacity_{args.chunks}.md")
    # No preamble outside headings: a leading plain paragraph would become an extra chunk.
    body = "".join(make_section(i) for i in range(1, args.chunks + 1))
    out.write_text(body, encoding="utf-8")
    print(f"wrote {out} sections={args.chunks} bytes={out.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
