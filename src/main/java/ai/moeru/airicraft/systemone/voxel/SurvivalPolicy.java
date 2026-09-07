package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;

/** Minecraft hazard priors operate only on observed geometry and the player's own condition. */
public final class SurvivalPolicy {
	public record Vitals(long life, float health, boolean inLava, boolean burning) {
		public static Vitals healthy() { return new Vitals(0, 20, false, false); }
		public boolean alive() { return health > 0; }
	}
	public record Parameters(Set<String> hazards, int escapeRadius, int maxAttempts, int escapeTicks, int maxDeaths) {
		public Parameters { hazards = Set.copyOf(hazards); if (escapeRadius < 1 || maxAttempts < 1 || escapeTicks < 1 || maxDeaths < 0) throw new IllegalArgumentException("Invalid survival limits"); }
		public static Parameters minecraft() { return new Parameters(Set.of("minecraft:lava", "minecraft:fire", "minecraft:soul_fire"), 8, 4, 400, 2); }
	}
	private final Parameters parameters;
	public SurvivalPolicy(Parameters parameters) { this.parameters = parameters; }
	public Parameters parameters() { return parameters; }
	public boolean nearHazard(World world, Pos position) {
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 1; y++) {
			Seen seen = world.known().get(position.offset(x, y, z));
			if (seen != null && seen.identified() && parameters.hazards().contains(seen.blockId())) return true;
		}
		return false;
	}
	public boolean urgent(World world) { return world.vitals().alive() && (world.vitals().inLava() || world.vitals().burning() || nearHazard(world, world.feet())); }
	public boolean safeStance(World world, Pos feet) { return StoneAcquisition.standable(world.known(), feet) && !nearHazard(world, feet) && !nearHazard(world, feet.offset(0, -1, 0)); }
	public Optional<Pos> refuge(World world, Pos origin, Set<Pos> rejected) {
		return world.known().keySet().stream().filter(pos -> !pos.equals(world.feet()) && !rejected.contains(pos) && safeStance(world, pos))
			.filter(pos -> distance(pos, origin) <= parameters.escapeRadius() * parameters.escapeRadius())
			.sorted(Comparator.<Pos>comparingDouble(pos -> distance(pos, world.feet())).thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).findFirst();
	}
	private static double distance(Pos a, Pos b) { return UndergroundSearch.squared(a, b) + Math.pow(a.y() - b.y(), 2); }
}
