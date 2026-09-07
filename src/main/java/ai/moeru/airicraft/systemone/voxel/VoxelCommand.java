package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;

/** Bounded physical requests. Resource acquisition remains a domain task. */
public sealed interface VoxelCommand {
	record Look(float yaw, float pitch) implements VoxelCommand {}
	record Break(Pos target, String expectedBlock) implements VoxelCommand {}
	record Navigate(Pos stance, int maxTravel, int maxTicks) implements VoxelCommand {}
	record Place(String item, Pos support, String expectedSupport) implements VoxelCommand {}
	/** A null station means the player's crafting grid, as declared by the recipe. */
	record Craft(ProductionKnowledge.Recipe recipe, Pos station) implements VoxelCommand {
		public Craft {
			if ((recipe.width() == 2) != (station == null)) throw new IllegalArgumentException("Recipe station mismatch");
		}
	}
}
