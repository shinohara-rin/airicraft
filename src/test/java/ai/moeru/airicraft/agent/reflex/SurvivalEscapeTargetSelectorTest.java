package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurvivalEscapeTargetSelectorTest {
	@Test
	void rejectsDirectAwayWaterAndChoosesDryStandingTarget() {
		SurvivalEscapeTargetSelector.Point origin = new SurvivalEscapeTargetSelector.Point(0, 64, 0);
		List<SurvivalEscapeTargetSelector.Point> threats = List.of(
			new SurvivalEscapeTargetSelector.Point(-3, 64, 0)
		);
		List<SurvivalEscapeTargetSelector.Candidate> candidates = List.of(
			new SurvivalEscapeTargetSelector.Candidate(8, 64, 0, true, true, false, false),
			new SurvivalEscapeTargetSelector.Candidate(0, 64, 7, true, false, false, false)
		);

		assertEquals(
			new SurvivalEscapeTargetSelector.Point(0, 64, 7),
			SurvivalEscapeTargetSelector.select(origin, threats, candidates).orElseThrow().point()
		);
	}

	@Test
	void rejectsHazardsAndCandidatesThatDoNotIncreaseThreatSeparation() {
		SurvivalEscapeTargetSelector.Point origin = new SurvivalEscapeTargetSelector.Point(0, 64, 0);
		List<SurvivalEscapeTargetSelector.Point> threats = List.of(
			new SurvivalEscapeTargetSelector.Point(2, 64, 0)
		);
		List<SurvivalEscapeTargetSelector.Candidate> candidates = List.of(
			new SurvivalEscapeTargetSelector.Candidate(-8, 64, 0, true, false, true, false),
			new SurvivalEscapeTargetSelector.Candidate(1, 64, 0, true, false, false, false),
			new SurvivalEscapeTargetSelector.Candidate(-6, 64, 4, true, false, false, false)
		);

		assertEquals(
			new SurvivalEscapeTargetSelector.Point(-6, 64, 4),
			SurvivalEscapeTargetSelector.select(origin, threats, candidates).orElseThrow().point()
		);
	}

	@Test
	void rejectsDistantAndAbruptlyElevatedTargetsInFavorOfShortReachableHops() {
		SurvivalEscapeTargetSelector.Point origin = new SurvivalEscapeTargetSelector.Point(0, 64, 0);
		List<SurvivalEscapeTargetSelector.Point> threats = List.of(
			new SurvivalEscapeTargetSelector.Point(2, 64, 0)
		);
		List<SurvivalEscapeTargetSelector.Candidate> candidates = List.of(
			new SurvivalEscapeTargetSelector.Candidate(-16, 64, 0, true, false, false, false),
			new SurvivalEscapeTargetSelector.Candidate(-6, 67, 0, true, false, false, false),
			new SurvivalEscapeTargetSelector.Candidate(-6, 64, 0, true, false, false, false)
		);

		assertEquals(
			new SurvivalEscapeTargetSelector.Point(-6, 64, 0),
			SurvivalEscapeTargetSelector.select(origin, threats, candidates).orElseThrow().point()
		);
	}

	@Test
	void prefersAReachableShelteredTargetOverAFrankRetreat() {
		SurvivalEscapeTargetSelector.Point origin = new SurvivalEscapeTargetSelector.Point(0, 64, 0);
		List<SurvivalEscapeTargetSelector.Point> threats = List.of(
			new SurvivalEscapeTargetSelector.Point(2, 64, 0)
		);
		List<SurvivalEscapeTargetSelector.Candidate> candidates = List.of(
			new SurvivalEscapeTargetSelector.Candidate(-8, 64, 0, true, false, false, false),
			new SurvivalEscapeTargetSelector.Candidate(-5, 64, 4, true, false, false, true)
		);

		assertEquals(
			new SurvivalEscapeTargetSelector.Point(-5, 64, 4),
			SurvivalEscapeTargetSelector.select(origin, threats, candidates).orElseThrow().point()
		);
	}
}
