import runpy
from pathlib import Path
import unittest

assess = runpy.run_path(str(Path(__file__).resolve().parents[1] / "check-system-one-run"))["assess"]


class RunAcceptanceTest(unittest.TestCase):
    def inputs(self):
        return [
            {"worlds": [{"id": "iron-pickaxe-held-out-01"}], "goal": {"itemId": "minecraft:iron_pickaxe", "quantity": 1}, "budgets": {"maxElapsedTicks": 100, "maxPlannerTurns": 0, "maxObservedHorizontalTravel": 20, "maxRuntimeCommands": 10},
             "releaseAcceptance": {"harness": "OK", "recordCoverage": "complete", "llmCallsAllRuns": 0, "perceptionViolationsAllRuns": 0}},
            {"id": "iron-pickaxe-held-out-01", "reportStatus": "PASSED", "elapsedTicks": 100, "plannerTurns": 0},
            {"harnessStatus": "OK", "clientExit": {"finalReturnCode": 0}},
            {"goal": "acquire 1 minecraft:iron_pickaxe", "inventory": {"minecraft:iron_pickaxe": 1}, "coverage": "complete", "commands": {"Break": 3, "Navigate": 7}, "metrics": {"travel": {"horizontal_observed_blocks": 20}, "perception_violations": {"status": "complete", "count": 0}}},
            {"records": [], "truncated": False},
            {"runtime": "system_one", "motor": {"command": "", "finishing": "", "releasePending": False, "smelting": "", "crafting": "", "pathingActive": False, "stopping": False, "edgePlacement": ""}},
            {"finished": True, "complete": True, "failure": ""}, {"executionMode": "no_llm"}, "verified",
        ]

    def test_exact_budget_boundaries_pass_but_a_green_scenario_over_budget_does_not(self):
        self.assertTrue(assess(*self.inputs())["eligibleRun"])
        for index, change in [(1, lambda a: a.update(elapsedTicks=101)),
                              (3, lambda a: a["metrics"]["travel"].update(horizontal_observed_blocks=20.01)),
                              (3, lambda a: a["commands"].update(Look=1))]:
            values = self.inputs(); change(values[index])
            self.assertFalse(assess(*values)["eligibleRun"])

    def test_missing_risk_evidence_never_means_zero(self):
        for value in [None, False, -1, float("nan"), float("inf")]:
            values = self.inputs(); values[3]["metrics"]["travel"]["horizontal_observed_blocks"] = value
            self.assertFalse(assess(*values)["eligibleRun"])
        for audit in [{}, {"status": "unavailable", "count": None}, {"status": "complete", "count": False}]:
            values = self.inputs(); values[3]["metrics"]["perception_violations"] = audit
            self.assertFalse(assess(*values)["eligibleRun"])

    def test_failed_runs_still_check_calls_and_perception(self):
        values = self.inputs(); values[1]["reportStatus"] = "FAILED"
        values[4]["records"] = [{}]; values[3]["metrics"]["perception_violations"]["count"] = 1
        report = assess(*values)
        failures = {c["name"] for c in report["checks"] if c["status"] != "passed"}
        self.assertTrue({"scenario_outcome", "llm_calls", "perception_violations"} <= failures)

    def test_incomplete_records_unchecked_replay_and_unreleased_motor_cannot_pass(self):
        for index, change in [(2, lambda a: a.update(harnessStatus="RUNNING")),
                              (3, lambda a: a.update(coverage="incomplete")),
                              (4, lambda a: a.update(truncated=True)),
                              (5, lambda a: a["motor"].pop("pathingActive")),
                              (6, lambda a: a.update(complete=False))]:
            values = self.inputs(); change(values[index])
            self.assertFalse(assess(*values)["eligibleRun"])
        values = self.inputs(); values[8] = None
        self.assertFalse(assess(*values)["eligibleRun"])

    def test_wrong_goal_or_inventory_cannot_borrow_a_passed_scenario_label(self):
        for change in [lambda a: a.update(goal="acquire 3 minecraft:cobblestone"), lambda a: a.update(inventory={})]:
            values = self.inputs(); change(values[3])
            self.assertFalse(assess(*values)["eligibleRun"])


if __name__ == "__main__":
    unittest.main()
