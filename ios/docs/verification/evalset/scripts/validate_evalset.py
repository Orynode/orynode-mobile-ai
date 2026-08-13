#!/usr/bin/env python3
"""Validate Orynode Mobile evalset skeleton (no network)."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KIND_TO_BUCKET = {
    "answered": "answered",
    "paraphrase": "paraphrase_or_sparse",
    "sparse": "paraphrase_or_sparse",
    "no_answer": "no_answer_or_conflict",
    "conflict": "no_answer_or_conflict",
}


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text:
            continue
        try:
            rows.append(json.loads(text))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{path}:{line_no}: invalid JSON ({exc})") from exc
    return rows


def document_body(path: Path) -> str:
    """Return searchable text for must_contain checks.

    PDFs are binary; prefer the committed `sources/<stem>.txt` companion so
    validation stays dependency-free and matches the generator input.
    """
    if path.suffix.lower() == ".pdf":
        source = path.parent / "sources" / f"{path.stem}.txt"
        if not source.is_file():
            raise FileNotFoundError(f"PDF source missing for {path.name}: {source}")
        return source.read_text(encoding="utf-8")
    return path.read_text(encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Fail unless MANIFEST targets are met (for release freeze).",
    )
    args = parser.parse_args()

    manifest_path = ROOT / "MANIFEST.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    warnings: list[str] = []

    for relative in manifest.get("corpus", []):
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing corpus file: {relative}")

    questions: list[dict] = []
    seen_ids: set[str] = set()
    for relative in manifest.get("questions", []):
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing questions file: {relative}")
            continue
        for row in load_jsonl(path):
            qid = row.get("id")
            if not isinstance(qid, str):
                errors.append(f"{relative}: question missing id")
                continue
            if qid in seen_ids:
                errors.append(f"duplicate question id: {qid}")
            seen_ids.add(qid)
            kind = row.get("kind")
            if kind not in KIND_TO_BUCKET:
                errors.append(f"{qid}: unknown kind {kind!r}")
            expect = row.get("expect") or {}
            behavior = expect.get("behavior")
            if behavior not in {"answer", "refuse"}:
                errors.append(f"{qid}: expect.behavior must be answer|refuse")
            if behavior == "answer" and not expect.get("evidence"):
                errors.append(f"{qid}: answer questions need expect.evidence")
            for doc in expect.get("allowed_documents") or []:
                if not (ROOT / doc).is_file():
                    errors.append(f"{qid}: allowed_documents missing {doc}")
            for evidence in expect.get("evidence") or []:
                doc = evidence.get("document")
                if not doc or not (ROOT / doc).is_file():
                    errors.append(f"{qid}: evidence.document missing {doc}")
                needles = evidence.get("must_contain") or []
                if doc and (ROOT / doc).is_file():
                    try:
                        body = document_body(ROOT / doc)
                    except FileNotFoundError as exc:
                        errors.append(f"{qid}: {exc}")
                        continue
                    for needle in needles:
                        if needle not in body:
                            errors.append(
                                f"{qid}: must_contain not found in {doc}: {needle!r}"
                            )
            questions.append(row)

    buckets = Counter(KIND_TO_BUCKET[q["kind"]] for q in questions if q.get("kind") in KIND_TO_BUCKET)
    seed = manifest.get("seed_counts") or {}
    for key, expected in seed.items():
        if key in {"txt", "markdown", "text_pdf", "failure_samples"}:
            continue
        actual = buckets.get(key, 0)
        if actual != expected:
            errors.append(f"seed_counts.{key}= {expected}, found {actual}")

    targets = manifest.get("targets") or {}
    for key, target in targets.items():
        if key in {"txt", "markdown", "text_pdf", "failure_samples"}:
            continue
        actual = buckets.get(key, 0)
        if actual < target:
            msg = f"target {key}: {actual}/{target}"
            (errors if args.strict else warnings).append(msg)

    pdf_dir = ROOT / "corpus" / "pdf"
    pdfs = [p for p in pdf_dir.glob("*.pdf") if p.is_file()]
    if len(pdfs) < targets.get("text_pdf", 0):
        msg = f"text PDF fixtures: {len(pdfs)}/{targets.get('text_pdf', 0)} (see corpus/pdf/README.md)"
        (errors if args.strict else warnings).append(msg)

    if errors:
        print("FAIL")
        for item in errors:
            print(f"  - {item}")
        for item in warnings:
            print(f"  warning: {item}")
        return 1

    print("OK")
    print(f"  version: {manifest.get('version')}")
    print(f"  frozen: {manifest.get('frozen')}")
    print(f"  questions: {len(questions)} ({dict(buckets)})")
    for item in warnings:
        print(f"  warning: {item}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
