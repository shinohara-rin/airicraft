package ai.moeru.airicraft.agent.tasks;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class EntitySelectorResolver {
	public static final double DEFAULT_NEARBY_RADIUS_BLOCKS = 32.0D;
	public static final double DEFAULT_INTERACTION_RANGE_BLOCKS = 4.5D;

	private EntitySelectorResolver() {
	}

	public static SelectionResult select(
		EntitySelector selector,
		List<EntityCandidate> candidates,
		double selfX,
		double selfY,
		double selfZ
	) {
		return select(selector, candidates, selfX, selfY, selfZ, DEFAULT_NEARBY_RADIUS_BLOCKS);
	}

	public static SelectionResult select(
		EntitySelector selector,
		List<EntityCandidate> candidates,
		double selfX,
		double selfY,
		double selfZ,
		double nearbyRadius
	) {
		Objects.requireNonNull(selector, "selector");
		List<EntityCandidate> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
		List<EntityCandidate> matches = safeCandidates.stream()
			.filter(candidate -> matches(selector, candidate))
			.toList();
		if (matches.isEmpty()) {
			return new SelectionResult(SelectionStatus.TARGET_NOT_FOUND, null, 0);
		}

		List<EntityCandidate> nearbyMatches = matches.stream()
			.filter(candidate -> isWithinRadius(selfX, selfY, selfZ, candidate.x(), candidate.y(), candidate.z(), nearbyRadius))
			.toList();
		if (nearbyMatches.isEmpty()) {
			return new SelectionResult(SelectionStatus.TARGET_NOT_NEARBY, null, matches.size());
		}

		List<EntityCandidate> aliveMatches = nearbyMatches.stream()
			.filter(EntityCandidate::alive)
			.toList();
		if (aliveMatches.isEmpty()) {
			return new SelectionResult(SelectionStatus.TARGET_NOT_ALIVE, null, nearbyMatches.size());
		}
		if (aliveMatches.size() > 1) {
			return new SelectionResult(SelectionStatus.TARGET_AMBIGUOUS, null, aliveMatches.size());
		}
		return new SelectionResult(SelectionStatus.SELECTED, aliveMatches.get(0), aliveMatches.size());
	}

	public static boolean isWithinInteractionRange(
		double selfX,
		double selfY,
		double selfZ,
		double targetX,
		double targetY,
		double targetZ
	) {
		return isWithinRadius(selfX, selfY, selfZ, targetX, targetY, targetZ, DEFAULT_INTERACTION_RANGE_BLOCKS);
	}

	public static boolean isWithinRadius(
		double selfX,
		double selfY,
		double selfZ,
		double targetX,
		double targetY,
		double targetZ,
		double radius
	) {
		double dx = selfX - targetX;
		double dy = selfY - targetY;
		double dz = selfZ - targetZ;
		return (dx * dx) + (dy * dy) + (dz * dz) <= radius * radius;
	}

	private static boolean matches(EntitySelector selector, EntityCandidate candidate) {
		if (selector.uuid() != null && !uuidMatches(selector.uuid(), candidate.uuid())) {
			return false;
		}
		if (selector.name() != null && !selector.name().equalsIgnoreCase(candidate.name())) {
			return false;
		}
		if (selector.entityTypeId() != null && !selector.entityTypeId().equals(normalizedEntityTypeId(candidate.entityTypeId()))) {
			return false;
		}
		return true;
	}

	private static boolean uuidMatches(String selectorUuid, String candidateUuid) {
		String normalizedSelector = normalizedUuid(selectorUuid);
		String normalizedCandidate = normalizedUuid(candidateUuid);
		if (normalizedSelector == null || normalizedCandidate == null) {
			return false;
		}
		return normalizedCandidate.startsWith(normalizedSelector);
	}

	private static String normalizedUuid(String uuid) {
		return uuid == null ? null : uuid.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalizedEntityTypeId(String entityTypeId) {
		return entityTypeId == null ? null : entityTypeId.trim().toLowerCase(Locale.ROOT);
	}

	public enum SelectionStatus {
		SELECTED,
		TARGET_NOT_FOUND,
		TARGET_NOT_NEARBY,
		TARGET_NOT_ALIVE,
		TARGET_AMBIGUOUS
	}

	public record SelectionResult(
		SelectionStatus status,
		EntityCandidate selected,
		int matchCount
	) {
		public SelectionResult {
			status = Objects.requireNonNull(status, "status");
			matchCount = Math.max(0, matchCount);
		}
	}

	public record EntityCandidate(
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		boolean alive
	) {
		public double squaredDistanceTo(double selfX, double selfY, double selfZ) {
			double dx = selfX - x;
			double dy = selfY - y;
			double dz = selfZ - z;
			return (dx * dx) + (dy * dy) + (dz * dz);
		}

		public static Comparator<EntityCandidate> nearestFirst(double selfX, double selfY, double selfZ) {
			return Comparator.comparingDouble(candidate -> candidate.squaredDistanceTo(selfX, selfY, selfZ));
		}
	}
}
