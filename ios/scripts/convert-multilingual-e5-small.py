#!/usr/bin/env python3
"""Download, convert and INT8-quantize multilingual-e5-small for iOS."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

import coremltools as ct
import numpy as np
import torch
from coremltools.optimize.coreml import OpLinearQuantizerConfig, OptimizationConfig
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


def convert(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID).eval()
    wrapper = HiddenStateModel(model).eval()
    sample_ids = torch.ones((1, MAX_LENGTH), dtype=torch.int32)
    sample_mask = torch.ones((1, MAX_LENGTH), dtype=torch.int32)
    traced = torch.jit.trace(wrapper, (sample_ids, sample_mask), strict=False)

    mlmodel = ct.convert(
        traced,
        convert_to="mlprogram",
        minimum_deployment_target=ct.target.iOS17,
        compute_precision=ct.precision.FLOAT16,
        inputs=[
            ct.TensorType(
                name="input_ids",
                shape=(1, MAX_LENGTH),
                dtype=np.int32,
            ),
            ct.TensorType(
                name="attention_mask",
                shape=(1, MAX_LENGTH),
                dtype=np.int32,
            ),
        ],
        outputs=[ct.TensorType(name="last_hidden_state")],
    )
    quantized = ct.optimize.coreml.linear_quantize_weights(
        mlmodel,
        config=OptimizationConfig(
            global_config=OpLinearQuantizerConfig(
                mode="linear_symmetric",
                dtype="int8",
            )
        ),
    )

    with tempfile.TemporaryDirectory(prefix="orynode-e5-") as temporary:
        package = Path(temporary) / "multilingual-e5-small.mlpackage"
        compiled_root = Path(temporary) / "compiled"
        quantized.save(str(package))
        subprocess.run(
            [
                "xcrun",
                "coremlcompiler",
                "compile",
                str(package),
                str(compiled_root),
            ],
            check=True,
        )
        generated = compiled_root / "multilingual-e5-small.mlmodelc"
        destination = output / generated.name
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(generated, destination)

    tokenizer_output = output / "multilingual-e5-small-tokenizer"
    if tokenizer_output.exists():
        shutil.rmtree(tokenizer_output)
    tokenizer.save_pretrained(tokenizer_output)
    license_path = output / "multilingual-e5-small-LICENSE.txt"
    license_path.write_text(
        "Model: intfloat/multilingual-e5-small\n"
        "Source: https://huggingface.co/intfloat/multilingual-e5-small\n"
        "License: MIT (verify the upstream model card before distribution)\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    convert(args.output.resolve())


if __name__ == "__main__":
    main()
