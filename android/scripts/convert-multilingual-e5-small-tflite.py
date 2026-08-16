#!/usr/bin/env python3
"""Download, convert and export multilingual-e5-small for Android LiteRT."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import torch
from transformers import AutoModel, AutoTokenizer


MODEL_ID = "intfloat/multilingual-e5-small"
MAX_LENGTH = 256


class HiddenStateModel(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model
        self.register_buffer(
            "position_ids",
            torch.arange(MAX_LENGTH, dtype=torch.int64).unsqueeze(0),
        )
        self.register_buffer(
            "token_type_ids",
            torch.zeros((1, MAX_LENGTH), dtype=torch.int64),
        )

    def forward(
        self, input_ids: torch.Tensor, attention_mask: torch.Tensor
    ) -> torch.Tensor:
        return self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            position_ids=self.position_ids,
            token_type_ids=self.token_type_ids,
            return_dict=False,
        )[0]


def export_tokenizer_assets(tokenizer: AutoTokenizer, output: Path) -> None:
    tokenizer_dir = output / "multilingual-e5-small-tokenizer"
    if tokenizer_dir.exists():
        shutil.rmtree(tokenizer_dir)
    tokenizer_dir.mkdir(parents=True)
    tokenizer.save_pretrained(tokenizer_dir)

    tokenizer_json = tokenizer_dir / "tokenizer.json"
    data = json.loads(tokenizer_json.read_text(encoding="utf-8"))
    vocab = data.get("model", {}).get("vocab", [])
    lines: list[str] = []
    if vocab and isinstance(vocab[0], list):
        for piece, score in vocab:
            lines.append(f"{piece}\t{float(score)}")
    else:
        ordered = sorted(tokenizer.get_vocab().items(), key=lambda item: item[1])
        for piece, _idx in ordered:
            lines.append(f"{piece}\t0.0")

    (tokenizer_dir / "unigram.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    specials = {
        "bos_id": int(tokenizer.bos_token_id or 0),
        "eos_id": int(tokenizer.eos_token_id or 2),
        "unk_id": int(tokenizer.unk_token_id or 3),
        "pad_id": int(tokenizer.pad_token_id or 1),
        "max_length": MAX_LENGTH,
        "metaspace": "▁",
    }
    (tokenizer_dir / "specials.json").write_text(
        json.dumps(specials, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output / "multilingual-e5-small-LICENSE.txt").write_text(
        "Model: intfloat/multilingual-e5-small\n"
        "Source: https://huggingface.co/intfloat/multilingual-e5-small\n"
        "License: MIT (verify the upstream model card before distribution)\n",
        encoding="utf-8",
    )


def convert_tflite(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID).eval()
    wrapper = HiddenStateModel(model).eval()
    sample_ids = torch.ones((1, MAX_LENGTH), dtype=torch.int32)
    sample_mask = torch.ones((1, MAX_LENGTH), dtype=torch.int32)

    with tempfile.TemporaryDirectory(prefix="orynode-e5-android-") as temporary:
        temporary_path = Path(temporary)
        onnx_path = temporary_path / "e5.onnx"
        torch.onnx.export(
            wrapper,
            (sample_ids, sample_mask),
            str(onnx_path),
            input_names=["input_ids", "attention_mask"],
            output_names=["last_hidden_state"],
            opset_version=17,
            do_constant_folding=True,
        )

        saved_model_dir = temporary_path / "saved_model"
        onnx2tf = shutil.which("onnx2tf") or str(
            Path(sys.executable).resolve().parent / "onnx2tf"
        )
        subprocess.run(
            [
                onnx2tf,
                "-i",
                str(onnx_path),
                "-o",
                str(saved_model_dir),
                "-b",
                "1",
                "-ois",
                f"input_ids:1,{MAX_LENGTH}",
                f"attention_mask:1,{MAX_LENGTH}",
                # GELU/Erf are not in TFLite builtins; replace with pseudo ops.
                "-rtpo",
                "Gelu",
                "Erf",
            ],
            check=True,
        )

        import tensorflow as tf  # type: ignore

        converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
        ]
        tflite_model = converter.convert()
        destination = output / "multilingual-e5-small.tflite"
        destination.write_bytes(tflite_model)

    export_tokenizer_assets(tokenizer, output)
    print(f"Wrote {output / 'multilingual-e5-small.tflite'}")
    print(f"Wrote {output / 'multilingual-e5-small-tokenizer'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    convert_tflite(args.output.resolve())


if __name__ == "__main__":
    main()
