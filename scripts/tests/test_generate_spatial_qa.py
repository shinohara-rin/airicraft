#!/usr/bin/env python3
"""Tests for the spatial QA generator."""

from __future__ import annotations

import gzip
import importlib.machinery
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType


GENERATOR_PATH = Path(__file__).resolve().parents[1] / "generate-spatial-qa"


def load_generator() -> ModuleType:
    module_name = "airicraft_spatial_qa_under_test"
    loader = importlib.machinery.SourceFileLoader(module_name, str(GENERATOR_PATH))
    spec = importlib.util.spec_from_loader(module_name, loader)
    if spec is None:
        raise RuntimeError(f"cannot load {GENERATOR_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    loader.exec_module(module)
    return module


def write_capture(root: Path, capture_id: str, cells, region=None, entities=None) -> Path:
    directory = root / capture_id
    directory.mkdir(parents=True)
    meta = {
        "formatVersion": 1,
        "captureId": capture_id,
        "label": "test",
        "capturedAtMs": 0,
        "dimensionId": "minecraft:overworld",
        "worldTime": 1000,
        "timeOfDay": 500,
        "playerPos": {"x": 0.0, "y": 64.0, "z": 0.0},
        "playerYaw": 0.0,
        "playerPitch": 0.0,
        "camera": {"x": 0.0, "y": 65.6, "z": 0.0, "yaw": 0.0, "pitch": 0.0},
        "effectiveFov": 70.0,
        "image": {"width": 854, "height": 480, "sourceWidth": 854, "sourceHeight": 480},
        "letterbox": {"scale": 1.0, "offsetX": 0, "offsetY": 0, "scaledWidth": 854, "scaledHeight": 480},
        "labels": {"stridePx": 8, "reach": 96.0, "cellCols": 107, "cellRows": 60},
    }
    (directory / "meta.json").write_text(json.dumps(meta))
    (directory / "frame.png").write_bytes(b"png")
    with gzip.open(directory / "labels.json.gz", "wt", encoding="utf-8") as handle:
        json.dump({"stridePx": 8, "reach": 96.0, "cells": cells}, handle)
    if region is not None:
        with gzip.open(directory / "region.json.gz", "wt", encoding="utf-8") as handle:
            json.dump({"cells": region}, handle)
    if entities is not None:
        (directory / "entities.json").write_text(json.dumps({"entities": entities}))
    return directory


def cell(x, y, kind, depth=None, block=None, entity=None):
    base = {"x": x, "y": y, "w": 8, "h": 8, "kind": kind}
    if depth is not None:
        base["depth"] = depth
    if block is not None:
        base.update({
            "blockX": block[0], "blockY": block[1], "blockZ": block[2],
            "blockId": block[3],
            "stateKey": block[3],
        })
    if entity is not None:
        base.update({"entityId": entity[0], "entityType": entity[1]})
    return base


class GenerateSpatialQaTest(unittest.TestCase):
    def setUp(self):
        self.module = load_generator()

    def test_emits_all_qa_types_and_files(self):
        cells = []
        for i in range(40):
            cells.append(cell(i * 16 % 848, (i // 20) * 120, "block", depth=5.0 + i, block=(i, 64, 10, "minecraft:stone")))
        for i in range(40, 60):
            cells.append(cell(i * 16 % 848, 240 + (i % 3) * 60, "block", depth=30.0 + i, block=(i, 63, 20, "minecraft:dirt")))
        cells.append(cell(400, 200, "entity", depth=12.0, entity=(7, "minecraft:zombie")))
        cells.append(cell(800, 400, "sky"))
        region = [
            {"x": 0, "y": 64, "z": 10, "loaded": True, "id": "minecraft:stone", "air": False, "viewVisible": True},
            {"x": 5, "y": 60, "z": -5, "loaded": True, "id": "minecraft:gold_ore", "air": False, "viewVisible": False},
        ]
        entities = [{"id": 7, "type": "minecraft:zombie", "pos": {"x": 1.0, "y": 64.0, "z": 8.0}}]

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_capture(root, "cap-a", cells, region=region, entities=entities)
            write_capture(root, "cap-b", cells)
            out = root / "out"

            code = self.module.main(["--input", str(root), "--out", str(out), "--seed", "7"])
            self.assertEqual(0, code)

            train = [json.loads(line) for line in (out / "train.jsonl").read_text().splitlines()]
            ev = [json.loads(line) for line in (out / "eval.jsonl").read_text().splitlines()]
            patches = [json.loads(line) for line in (out / "patch_labels.jsonl").read_text().splitlines()]
            summary = json.loads((out / "summary.json").read_text())

            self.assertEqual(2, summary["captures"])
            self.assertEqual(1, summary["evalCaptures"])
            self.assertEqual(len(train) + len(ev), summary["trainExamples"] + summary["evalExamples"])
            self.assertTrue(patches)

            types = {e["type"] for e in train + ev}
            self.assertIn("block_at_pixel", types)
            self.assertIn("point_to", types)
            self.assertIn("egocentric_offset", types)
            self.assertIn("depth_compare", types)
            self.assertIn("left_right", types)
            self.assertIn("above_below", types)
            self.assertIn("count_visible", types)
            self.assertIn("line_of_sight", types)

            for example in train + ev:
                self.assertIn("split", example)
                self.assertIn("question", example)
                self.assertIn("answer", example)
                self.assertTrue(example["image"].endswith("/frame.png"))

            los = [e for e in train + ev if e["type"] == "line_of_sight"]
            self.assertTrue(los)
            answers = {e["answer"] for e in los}
            self.assertEqual({"yes", "no"}, answers)

            patch = patches[0]
            self.assertIn("kind", patch)
            self.assertIn("patch", patch)
            self.assertEqual(patch["patch"]["row"], 0)

    def test_deterministic_with_same_seed(self):
        cells = [cell(i * 8, 0, "block", depth=10.0, block=(i, 64, 10, "minecraft:stone")) for i in range(30)]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_capture(root, "cap-x", cells)
            out1 = root / "a"
            out2 = root / "b"
            self.module.main(["--input", str(root), "--out", str(out1), "--seed", "3"])
            self.module.main(["--input", str(root), "--out", str(out2), "--seed", "3"])
            self.assertEqual(
                (out1 / "train.jsonl").read_text(),
                (out2 / "train.jsonl").read_text(),
            )

    def test_no_captures_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            code = self.module.main(["--input", tmp, "--out", str(out)])
            self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()
