package ai.moeru.airicraft.agent.reflex;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class SurvivalEscapeTargetSelector {
	private static final double MIN_THREAT_SEPARATION_GAIN = 2.0D;
	private static final double MAX_ESCAPE_HOP_DISTANCE = 8.0D;
	private static final int MAX_ESCAPE_HOP_HEIGHT = 2;

	private SurvivalEscapeTargetSelector() {
	}

	static Optional<Candidate> select(Point origin, List<Point> threats, List<Candidate> candidates) {
		if (origin == null || threats == null || threats.isEmpty() || candidates == null) {
			return Optional.empty();
		}
		double currentSeparation = nearestHorizontalDistance(origin, threats);
		return candidates.stream()
			.filter(Candidate::safeStanding)
			.filter(candidate -> !candidate.water())
			.filter(candidate -> !candidate.hazard())
			.filter(candidate -> horizontalDistance(origin, candidate.point()) <= MAX_ESCAPE_HOP_DISTANCE)
			.filter(candidate -> Math.abs(candidate.y() - origin.y()) <= MAX_ESCAPE_HOP_HEIGHT)
			.filter(candidate -> nearestHorizontalDistance(candidate.point(), threats)
				>= currentSeparation + MIN_THREAT_SEPARATION_GAIN)
			.max(Comparator.comparingDouble(candidate -> score(origin, threats, candidate)));
	}

	private static double score(Point origin, List<Point> threats, Candidate candidate) {
		double threatSeparation = nearestHorizontalDistance(candidate.point(), threats);
		double travelDistance = horizontalDistance(origin, candidate.point());
		return threatSeparation * 4.0D - travelDistance + (candidate.sheltered() ? 1_000.0D : 0.0D);
	}

	private static double nearestHorizontalDistance(Point point, List<Point> threats) {
		return threats.stream()
			.mapToDouble(threat -> horizontalDistance(point, threat))
			.min()
			.orElse(0.0D);
	}

	private static double horizontalDistance(Point left, Point right) {
		double dx = left.x() - right.x();
		double dz = left.z() - right.z();
		return Math.sqrt(dx * dx + dz * dz);
	}

	record Point(int x, int y, int z) {
	}

	record Candidate(int x, int y, int z, boolean safeStanding, boolean water, boolean hazard, boolean sheltered) {
		Point point() {
			return new Point(x, y, z);
		}
	}
}
