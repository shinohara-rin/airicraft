import gzip
import runpy
import json
from pathlib import Path
import tempfile
import unittest
import copy

inspect_recording = runpy.run_path(str(Path(__file__).resolve().parents[1] / "inspect-system-one-recording"))["inspect_recording"]


class RecordingInspectionTest(unittest.TestCase):
    def inspect(self, rows, truncate=False, failure_context=False, audit_breaks=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "record.jsonl.gz"
            with gzip.open(path, "wt") as stream:
                for row in rows:
                    stream.write(json.dumps(row) + "\n")
            if truncate:
                path.write_bytes(path.read_bytes()[:-8])
            return inspect_recording(path, failure_context, audit_breaks)

    def break_rows(self):
        rows = self.rows()
        rows[1]["observation"].update(changed=[{"pos": {"x": 1, "y": 2, "z": 0},
                                             "seen": {"blockId": "stone", "identified": True, "empty": False, "tick": 4}}], removed=[])
        rows[1]["effects"] = ["Start[token=t, command=Break[target=Pos[x=1, y=2, z=0], expectedBlock=stone]]"]
        return rows

    def test_break_audit_reconstructs_memory_without_claiming_hidden_read_coverage(self):
        header, first, _ = self.break_rows()
        first.update(effects=[], outcome="")
        second = copy.deepcopy(first)
        second.update(sequence=2, tick=6, effects=self.break_rows()[1]["effects"], outcome="done")
        second["observation"]["changed"] = []
        report = self.inspect([header, first, second, {"type": "end", "rows": 3}], audit_breaks=True)
        audit = report["metrics"]["break_target_observations"]
        self.assertEqual(("complete", 1, 0), (audit["status"], audit["checked_breaks"], audit["mismatched_breaks"]))
        self.assertEqual("unavailable", report["metrics"]["perception_violations"]["status"])
        second["observation"]["removed"] = [{"x": 1, "y": 2, "z": 0}]
        audit = self.inspect([header, first, second, {"type": "end", "rows": 3}], audit_breaks=True)["metrics"]["break_target_observations"]
        self.assertEqual({"unknown_target": 1}, audit["reasons"])

    def test_break_audit_flags_identity_identification_and_future_observations(self):
        for field, value, reason in [("blockId", "iron", "target_identity_mismatch"),
                                     ("identified", False, "target_not_identified_occupied"),
                                     ("empty", True, "target_not_identified_occupied"),
                                     ("tick", 9, "future_observation")]:
            with self.subTest(field=field):
                rows = self.break_rows()
                rows[1]["observation"]["changed"][0]["seen"][field] = value
                audit = self.inspect(rows, audit_breaks=True)["metrics"]["break_target_observations"]
                self.assertEqual(1, audit["mismatched_breaks"])
                self.assertEqual({reason: 1}, audit["reasons"])

    def test_break_audit_missing_or_unparsed_evidence_is_not_a_clean_zero(self):
        for missing in ("changed", "observation", "command", "tick"):
            rows = self.break_rows()
            if missing == "changed": rows[1]["observation"].pop("changed")
            elif missing == "observation": rows[1]["observation"] = None
            elif missing == "tick": rows[1]["observation"]["changed"][0]["seen"].pop("tick")
            else: rows[1]["effects"] = ["Start[token=t, command=Break[unknown syntax]]"]
            audit = self.inspect(rows, audit_breaks=True)["metrics"]["break_target_observations"]
            self.assertEqual("partial", audit["status"])
            self.assertEqual(1, audit["missing_evidence_breaks"])
        audit = self.inspect(self.break_rows(), truncate=True, audit_breaks=True)["metrics"]["break_target_observations"]
        self.assertEqual("partial", audit["status"])

    def test_break_audit_cannot_reconstruct_missing_earlier_observations(self):
        header, first, _ = self.break_rows()
        second = copy.deepcopy(first)
        second.update(sequence=2, tick=6)
        second["observation"]["changed"] = []
        first.update(effects=[], outcome="")
        first["observation"].pop("changed")
        audit = self.inspect([header, first, second, {"type": "end", "rows": 3}], audit_breaks=True)["metrics"]["break_target_observations"]
        self.assertEqual("partial", audit["status"])
        self.assertEqual(1, audit["missing_evidence_breaks"])
        self.assertEqual(0, audit["mismatched_breaks"])
        self.assertEqual({"incomplete_observation_history": 1}, audit["reasons"])

    def test_break_audit_does_not_reuse_cells_across_absent_world_or_ignore_unknown_effects(self):
        header, first, _ = self.break_rows()
        first.update(effects=[], outcome="")
        missing = copy.deepcopy(first)
        missing.update(sequence=2, tick=6, observation=None)
        later = copy.deepcopy(first)
        later.update(sequence=3, tick=7, effects=self.break_rows()[1]["effects"], outcome="done")
        later["observation"]["changed"] = []
        audit = self.inspect([header, first, missing, later, {"type": "end", "rows": 4}], audit_breaks=True)["metrics"]["break_target_observations"]
        self.assertEqual({"unknown_target": 1}, audit["reasons"])
        rows = self.break_rows()
        rows[1]["effects"] = ["Start[unrecognized command format]"]
        audit = self.inspect(rows, audit_breaks=True)["metrics"]["break_target_observations"]
        self.assertEqual("partial", audit["status"])
        self.assertEqual(1, audit["unclassified_effects"])

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

    def test_search_rejection_evidence_distinguishes_missing_from_recorded_empty(self):
        rows = self.rows()
        self.assertEqual("unavailable", self.inspect(rows)["search_rejections"]["status"])
        rows[1]["search"] = []
        self.assertEqual("complete", self.inspect(rows)["search_rejections"]["status"])
        rows[1]["search"] = [{"task": 2, "change": "rejected", "candidate": {"x": 0, "y": 2, "z": 1}, "reason": "UNRESOLVED_OBSERVATION", "observed": []}]
        report = self.inspect(rows)["search_rejections"]
        self.assertEqual({"rejected": 1}, report["counts"])
        self.assertEqual(5, report["recent_changes"][0]["tick"])

    def test_old_policy_failure_is_inspectable_without_claiming_replay(self):
        report = self.inspect(self.rows())
        self.assertEqual("complete", report["coverage"])
        self.assertEqual("not_checked", report["decision_replay"])
        self.assertEqual([1, 2], [task["task"] for task in report["last_command_failure"]["task_chain"]])
        self.assertEqual({"route_blocked": 1}, report["command_failures"])
        self.assertEqual({"Navigate": 1}, report["commands"])

    def test_allowance_snapshots_distinguish_active_and_suspended_state(self):
        rows = self.rows()
        rows[0]["version"] = 2
        frame = {"task": 2, "phase": "Acting", "leaf": True, "continuation": ["explore"],
                 "allowance": {"origin": {"x": 0, "y": 2, "z": 0}, "expiresAt": 5}}
        suspended = copy.deepcopy(frame)
        suspended.update(task=3, phase="WaitingChild", leaf=False, continuation=["resupply_return", "explore"])
        rows[1]["lighting"] = {"policyLight": 3, "before": [frame], "after": [frame, suspended]}
        metrics = self.inspect(rows)["metrics"]["dark_exposure_allowance"]
        self.assertEqual("recorded_policy_state", metrics["status"])
        self.assertEqual(2, metrics["instances_observed"])
        self.assertEqual(2, metrics["allowance_state_samples"])
        self.assertEqual(1, metrics["active_exploration_state_samples"])
        self.assertEqual(1, metrics["expired_active_exploration_samples"])
        self.assertIsNone(metrics["actual_dark_exposure_ticks"])

    def test_new_format_requires_lighting_evidence_and_empty_is_a_recorded_zero(self):
        rows = self.rows()
        rows[0]["version"] = 2
        self.assertEqual("incomplete", self.inspect(rows)["coverage"])
        rows[1]["lighting"] = {"policyLight": 15, "before": [], "after": []}
        report = self.inspect(rows)
        self.assertEqual("complete", report["coverage"])
        self.assertEqual(0, report["metrics"]["dark_exposure_allowance"]["instances_observed"])

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

    def test_metrics_do_not_invent_vitals_or_a_perception_audit_for_older_tapes(self):
        metrics = self.inspect(self.rows())["metrics"]
        self.assertIsNone(metrics["survival"]["minimum_observed_health"])
        self.assertIsNone(metrics["survival"]["lives_observed_dead"])
        self.assertIsNone(metrics["travel"]["horizontal_observed_blocks"])
        self.assertIsNone(metrics["perception_violations"]["count"])
        self.assertEqual("unavailable", metrics["perception_violations"]["status"])

    def test_metrics_split_gaps_and_lives_and_count_repeated_dead_samples_once(self):
        header, template, _ = self.rows()
        rows = [header]
        # Travel is 5 + 1 + 1 + 1. The gap and respawn displacement are excluded.
        samples = [(0, 0, 20, 0), (3, 4, 16, 0), None, (100, 0, 12, 0),
                   (101, 0, 0, 0), (102, 0, 0, 0), (0, 0, 20, 1), (1, 0, 18, 1)]
        for index, sample in enumerate(samples, 1):
            row = copy.deepcopy(template)
            row.update(sequence=index, tick=4 + index, effects=[], events=[])
            if sample is None:
                row["observation"] = None
            else:
                x, z, health, life = sample
                row["observation"].update(eye={"x": x, "y": 3, "z": z}, vitals={"health": health, "life": life})
            rows.append(row)
        rows[2]["effects"] = ["Start[token=t, command=Break[target=p]]"]
        rows[2]["events"] = [{"type": "task_suspended", "task": 2, "detail": "lighting_supply"}]
        rows[-1]["events"] = [{"type": "task_resumed", "task": 2, "detail": "SUCCEEDED:supplied"}]
        rows.append({"type": "end", "rows": len(rows)})
        metrics = self.inspect(rows)["metrics"]
        self.assertEqual(8.0, metrics["travel"]["horizontal_observed_blocks"])
        self.assertEqual(4, metrics["travel"]["adjacent_same_life_segments"])
        self.assertEqual(18.0, metrics["survival"]["observed_health_decrease"])
        self.assertEqual(1, metrics["survival"]["lives_observed_dead"])
        self.assertEqual(1, metrics["missing_observation_turns"])
        self.assertEqual(1, metrics["excavation"]["break_commands_started"])
        self.assertEqual({"lighting_supply": 1}, metrics["task_suspensions"])
        self.assertEqual({"SUCCEEDED:supplied": 1}, metrics["task_resumptions"])
        self.assertEqual("incomplete", self.inspect(rows[:-1])["metrics"]["coverage"])

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
