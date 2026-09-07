package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;

/** Bounded physical requests. Resource acquisition remains a domain task. */
public sealed interface VoxelCommand {
	record Look(float yaw, float pitch) implements VoxelCommand {}
	record Break(Pos target, String expectedBlock) implements VoxelCommand {}
	record Navigate(Pos stance, int maxTravel, int maxTicks) implements VoxelCommand {}
	enum Face {
		UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
		public final int x, y, z;
		Face(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public Pos adjacent(Pos support) { return support.offset(x, y, z); }
	}
	record Place(String item, Pos support, String expectedSupport, Face face, String expectedPlacedBlock) implements VoxelCommand {
		public Place(String item, Pos support, String expectedSupport) { this(item, support, expectedSupport, Face.UP, item); }
		public Pos destination() { return face.adjacent(support); }
	}
	record StartSmelt(ProductionKnowledge.Smelt recipe, Pos station, String fuel, int fuelCount) implements VoxelCommand {
		public StartSmelt { if (fuelCount < 1) throw new IllegalArgumentException("Fuel quantity required"); }
	}
	record CollectSmelt(ProductionKnowledge.Smelt recipe, Pos station) implements VoxelCommand {}
	/** A null station means the player's crafting grid, as declared by the recipe. */
	record Craft(ProductionKnowledge.Recipe recipe, Pos station) implements VoxelCommand {
		public Craft {
			if ((recipe.width() == 2) != (station == null)) throw new IllegalArgumentException("Recipe station mismatch");
		}
	}
}
