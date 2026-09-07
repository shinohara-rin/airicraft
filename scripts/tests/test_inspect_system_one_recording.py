import gzip
import runpy
import json
from pathlib import Path
import tempfile
import unittest
import copy

inspect_recording = runpy.run_path(str(Path(__file__).resolve().parents[1] / "inspect-system-one-recording"))["inspect_recording"]


class RecordingInspectionTest(unittest.TestCase):
    def inspect(self, rows, truncate=False, failure_context=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.jsonl.gz"
            with gzip.open(path, "wt") as stream:
                for row in rows:
                    stream.write(json.dumps(row) + "\n")
            if truncate:
                path.write_bytes(path.read_bytes()[:-8])
            return inspect_recording(path, failure_context)

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

    def test_failure_context_reconstructs_deltas_and_stays_at_the_first_failure(self):
        header, first, _ = self.rows()
        removed = {"x": 0, "y": 1, "z": 0}
        support = {"x": 1, "y": 1, "z": 0}
        far = {"x": 20, "y": 1, "z": 0}
        first["observation"]["changed"] = [{"pos": removed, "seen": {"blockId": "old"}}, {"pos": far, "seen": {"blockId": "far"}}]
        first["outcome"] = ""
        failure = copy.deepcopy(first)
        failure.update(sequence=2, tick=6, effects=[], events=[{"type": "task_ended", "task": 2, "detail": "FAILED:no_step"}])
        failure["observation"].update(inventory={"wood": 1}, removed=[removed], changed=[{"pos": support, "seen": {"blockId": "support"}}])
        later = copy.deepcopy(failure)
        later.update(sequence=3, tick=7, outcome="Outcome[kind=FAILED, evidence=goal_failed]")
        later["observation"].update(feet=far, inventory={}, changed=[{"pos": support, "seen": {"blockId": "air"}}])
        report = self.inspect([header, first, failure, later, {"type": "end", "rows": 4}], failure_context=True)
        self.assertEqual("complete", report["coverage"])
        context = report["first_task_failure"]["context"]
        self.assertEqual({"x": 0, "y": 2, "z": 0}, context["position"])
        self.assertEqual({"wood": 1}, context["inventory"])
        self.assertEqual([{"pos": support, "seen": {"blockId": "support"}}], context["nearby_cells"])
        self.assertEqual(5, context["recent_commands"][0]["tick"])


if __name__ == "__main__":
    unittest.main()
