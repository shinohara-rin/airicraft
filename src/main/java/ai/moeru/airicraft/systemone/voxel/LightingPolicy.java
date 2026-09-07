package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Working-light preference. This policy never changes the observation lens or identifies blocks. */
public final class LightingPolicy {
	public record Parameters(int enterBelow, int resumeAt, int supplyCount, int allowanceTicks, int allowanceRadius) {
		public Parameters { if (enterBelow < 0 || resumeAt <= enterBelow || resumeAt > 15 || supplyCount < 1 || allowanceTicks < 1 || allowanceRadius < 1) throw new IllegalArgumentException("Invalid lighting policy"); }
	}
	public enum Repair { SUPPLY, PLACEMENT }
	public enum Action { CONTINUE, PLACE, SUPPLY, REDUCED_LIGHT_PROGRESS, RETREAT }
	public record Allowance(Pos origin, long expiresAt) {}
	public record State(boolean maintaining, Set<Repair> failed, Optional<Allowance> allowance) {
		public State { failed = Set.copyOf(failed); }
		public static State begin() { return new State(false, Set.of(), Optional.empty()); }
		public State failed(Repair repair) { var failures = new HashSet<>(failed); failures.add(repair); return new State(true, failures, allowance); }
	}
	public record Assessment(State state, Action action) {}
	private final Parameters parameters;
	public LightingPolicy(Parameters parameters) { this.parameters = parameters; }
	public Parameters parameters() { return parameters; }
	public Assessment assess(State state, int light, int torches, boolean mayResupply, Pos feet, long tick) {
		if (light >= parameters.resumeAt() || (!state.maintaining() && light >= parameters.enterBelow())) return new Assessment(State.begin(), Action.CONTINUE);
		State maintaining = new State(true, state.failed(), state.allowance());
		if (torches > 0 && !state.failed().contains(Repair.PLACEMENT)) return new Assessment(maintaining, Action.PLACE);
		if (torches == 0 && mayResupply && !state.failed().contains(Repair.SUPPLY)) return new Assessment(maintaining, Action.SUPPLY);
		Allowance allowance = state.allowance().orElseGet(() -> new Allowance(feet, tick + parameters.allowanceTicks()));
		maintaining = new State(true, state.failed(), Optional.of(allowance));
		return new Assessment(maintaining, permits(allowance, feet, tick) ? Action.REDUCED_LIGHT_PROGRESS : Action.RETREAT);
	}
	public boolean permits(Allowance allowance, Pos position, long tick) {
		return tick < allowance.expiresAt() && UndergroundSearch.squared(allowance.origin(), position) + Math.pow(allowance.origin().y() - position.y(), 2) <= parameters.allowanceRadius() * parameters.allowanceRadius();
	}
}
