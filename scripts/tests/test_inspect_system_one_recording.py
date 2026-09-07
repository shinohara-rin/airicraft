import gzip
import runpy
import json
from pathlib import Path
import tempfile
import unittest

inspect_recording = runpy.run_path(str(Path(__file__).resolve().parents[1] / "inspect-system-one-recording"))["inspect_recording"]


class RecordingInspectionTest(unittest.TestCase):
    def inspect(self, rows, truncate=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.jsonl.gz"
            with gzip.open(path, "wt") as stream:
                for row in rows:
                    stream.write(json.dumps(row) + "\n")
            if truncate:
                path.write_bytes(path.read_bytes()[:-8])
            return inspect_recording(path)

    def rows(self):
        return [
            {"type": "production_begin", "tick": 4, "count": 1, "item": "pickaxe", "run": "r", "methodVersion": "old-policy"},
            {"type": "turn", "sequence": 1, "tick": 5, "observation": {"feet": {"x": 0, "y": 2, "z": 0}, "inventory": {"wood": 2}},
             "effects": ["Start[token=t, command=Navigate[stance=p]]"], "events": [
                 {"type": "task_started", "task": 2, "detail": "parent=1 reason=workstation_required"},
                 {"type": "command_finished", "task": 2, "detail": "FAILED:route_blocked"}],
             "outcome": "Outcome[kind=FAILED, evidence=station_unreachable]"},
            {"type": "end", "rows": 2},
        ]

    def test_old_policy_failure_is_inspectable_without_claiming_replay(self):
        report = self.inspect(self.rows())
        self.assertEqual("complete", report["coverage"])
        self.assertEqual("not_checked", report["decision_replay"])
        self.assertEqual([1, 2], [task["task"] for task in report["last_command_failure"]["task_chain"]])
        self.assertEqual({"route_blocked": 1}, report["command_failures"])
        self.assertEqual({"Navigate": 1}, report["commands"])

    def test_missing_turn_does_not_look_complete_even_with_matching_footer(self):
        rows = self.rows()
        rows[1].update(sequence=2, tick=6)
        self.assertEqual("incomplete", self.inspect(rows)["coverage"])

    def test_truncated_compression_and_missing_footer_are_incomplete(self):
        self.assertEqual("incomplete", self.inspect(self.rows(), truncate=True)["coverage"])
        self.assertEqual("incomplete", self.inspect(self.rows()[:-1])["coverage"])

    def test_data_after_footer_is_incomplete(self):
        rows = self.rows()
        self.assertEqual("incomplete", self.inspect(rows + [rows[-1]])["coverage"])

    def test_prerequisite_failure_before_actuation_retains_its_task_chain(self):
        rows = self.rows()
        rows[1]["effects"] = []
        rows[1]["events"][1].update(type="task_ended", detail="FAILED:dependency_cycle:iron")
        report = self.inspect(rows)
        self.assertEqual({}, report["commands"])
        self.assertIsNone(report["last_command_failure"])
        self.assertEqual("dependency_cycle:iron", report["first_task_failure"]["reason"])
        self.assertEqual([1, 2], [task["task"] for task in report["first_task_failure"]["task_chain"]])


if __name__ == "__main__":
    unittest.main()
