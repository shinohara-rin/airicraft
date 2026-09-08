package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Working-light preference. This policy never changes the observation lens or identifies blocks. */
public final class LightingPolicy {
	public record Parameters(int enterBelow, int resumeAt, int supplyCount, int allowanceTicks, int allowanceRadius, int repairTicks, int repairRadius) {
		public Parameters(int enterBelow, int resumeAt, int supplyCount, int allowanceTicks, int allowanceRadius) { this(enterBelow,resumeAt,supplyCount,allowanceTicks,allowanceRadius,2400,32); }
		public Parameters { if (enterBelow < 0 || resumeAt <= enterBelow || resumeAt > 15 || supplyCount < 1 || allowanceTicks < 1 || allowanceRadius < 1 || repairTicks < 1 || repairRadius < 1) throw new IllegalArgumentException("Invalid lighting policy"); }
	}
	public enum Repair { SUPPLY, PLACEMENT }
	public enum Action { CONTINUE, PLACE, SUPPLY, REDUCED_LIGHT_PROGRESS, RETREAT }
	public enum Validity { ACTIVE, HEALTH_LOSS, LIFE_CHANGED, HAZARD, REPAIR_LIMIT }
	public enum Purpose { PROGRESS, LIGHT_RESTORATION }
	public record Allowance(Pos origin, long expiresAt, float observedHealth, long life, Validity validity, int radius, Purpose purpose) {
		public static Allowance begin(Pos origin, long expiresAt, SurvivalPolicy.Vitals vitals) {
			return begin(origin,expiresAt,vitals,4,Purpose.PROGRESS);
		}
		public static Allowance begin(Pos origin, long expiresAt, SurvivalPolicy.Vitals vitals, int radius, Purpose purpose) {
			return new Allowance(origin, expiresAt, vitals.health(), vitals.life(), Validity.ACTIVE,radius,purpose).observe(vitals);
		}
		Allowance observe(SurvivalPolicy.Vitals vitals) {
			Validity next = validity;
			if (next == Validity.ACTIVE) {
				if (vitals.life() != life) next = Validity.LIFE_CHANGED;
				else if (vitals.health() < observedHealth) next = Validity.HEALTH_LOSS;
				else if (vitals.inLava() || vitals.burning()) next = Validity.HAZARD;
			}
			return new Allowance(origin, expiresAt, vitals.health(), life, next,radius,purpose);
		}
	}
	public record State(boolean maintaining, Set<Repair> failed, Optional<Allowance> allowance) {
		public State { failed = Set.copyOf(failed); }
		public static State begin() { return new State(false, Set.of(), Optional.empty()); }
		public State failed(Repair repair) { var failures = new HashSet<>(failed); failures.add(repair); return new State(true, failures, allowance); }
	}
	public record Assessment(State state, Action action) {}
	private final Parameters parameters;
	public LightingPolicy(Parameters parameters) { this.parameters = parameters; }
	public Parameters parameters() { return parameters; }
	/** Observes an episode without authorizing normal work during a repair. */
	public State observe(State state, int light, SurvivalPolicy.Vitals vitals) {
		if (light >= parameters.resumeAt() || (!state.maintaining() && light >= parameters.enterBelow())) return State.begin();
		return new State(true, state.failed(), state.allowance().map(a -> a.observe(vitals)));
	}
	public Assessment assess(State state, int light, int torches, boolean mayResupply, Pos feet, long tick, SurvivalPolicy.Vitals vitals) {
		State maintaining = observe(state, light, vitals);
		if (!maintaining.maintaining()) return new Assessment(maintaining, Action.CONTINUE);
		if (maintaining.allowance().filter(a -> a.validity() != Validity.ACTIVE).isPresent()) return new Assessment(maintaining, Action.RETREAT);
		if (torches > 0 && !state.failed().contains(Repair.PLACEMENT)) return new Assessment(maintaining, Action.PLACE);
		if (torches == 0 && mayResupply && !state.failed().contains(Repair.SUPPLY)) return new Assessment(maintaining, Action.SUPPLY);
		Allowance allowance = maintaining.allowance().orElseGet(() -> Allowance.begin(feet, tick + (mayResupply ? parameters.allowanceTicks() : parameters.repairTicks()), vitals,
			mayResupply ? parameters.allowanceRadius() : parameters.repairRadius(), mayResupply ? Purpose.PROGRESS : Purpose.LIGHT_RESTORATION));
		maintaining = new State(true, state.failed(), Optional.of(allowance));
		return new Assessment(maintaining, permits(allowance, feet, tick) ? Action.REDUCED_LIGHT_PROGRESS : Action.RETREAT);
	}
	public boolean permits(Allowance allowance, Pos position, long tick) {
		return allowance.validity() == Validity.ACTIVE && tick < allowance.expiresAt() && UndergroundSearch.squared(allowance.origin(), position) + Math.pow(allowance.origin().y() - position.y(), 2) <= allowance.radius() * allowance.radius();
	}
}
