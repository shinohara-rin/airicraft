package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Objects;
import java.util.Optional;

public final class DispatchingWorldTaskExecutor implements WorldTaskExecutor {
	private final WorldTaskExecutor baritoneExecutor;
	private final WorldTaskExecutor craftingExecutor;
	private final WorldTaskExecutor dropItemsExecutor;
	private final WorldTaskExecutor entityInteractionExecutor;
	private final WorldTaskExecutor smeltingExecutor;
	private final WorldTaskExecutor returnToSurfaceExecutor;
	private final WorldTaskExecutor blockInteractionExecutor;
	private WorldTaskType activeType;

	public DispatchingWorldTaskExecutor(WorldTaskExecutor baritoneExecutor, WorldTaskExecutor craftingExecutor) {
		this(baritoneExecutor, craftingExecutor, new DropItemsTaskExecutor(), new EntityInteractionTaskExecutor(), new SmeltingTaskExecutor(), new ReturnToSurfaceTaskExecutor(null), new BlockInteractionTaskExecutor());
	}

	public DispatchingWorldTaskExecutor(WorldTaskExecutor baritoneExecutor, WorldTaskExecutor craftingExecutor, WorldTaskExecutor dropItemsExecutor) {
		this(baritoneExecutor, craftingExecutor, dropItemsExecutor, new EntityInteractionTaskExecutor(), new SmeltingTaskExecutor(), new ReturnToSurfaceTaskExecutor(null), new BlockInteractionTaskExecutor());
	}

	public DispatchingWorldTaskExecutor(
		WorldTaskExecutor baritoneExecutor,
		WorldTaskExecutor craftingExecutor,
		WorldTaskExecutor dropItemsExecutor,
		WorldTaskExecutor entityInteractionExecutor
	) {
		this(baritoneExecutor, craftingExecutor, dropItemsExecutor, entityInteractionExecutor, new SmeltingTaskExecutor(), new ReturnToSurfaceTaskExecutor(null), new BlockInteractionTaskExecutor());
	}

	public DispatchingWorldTaskExecutor(
		WorldTaskExecutor baritoneExecutor,
		WorldTaskExecutor craftingExecutor,
		WorldTaskExecutor dropItemsExecutor,
		WorldTaskExecutor entityInteractionExecutor,
		WorldTaskExecutor smeltingExecutor
	) {
		this(baritoneExecutor, craftingExecutor, dropItemsExecutor, entityInteractionExecutor, smeltingExecutor, new ReturnToSurfaceTaskExecutor(null), new BlockInteractionTaskExecutor());
	}

	public DispatchingWorldTaskExecutor(
		WorldTaskExecutor baritoneExecutor,
		WorldTaskExecutor craftingExecutor,
		WorldTaskExecutor dropItemsExecutor,
		WorldTaskExecutor entityInteractionExecutor,
		WorldTaskExecutor smeltingExecutor,
		WorldTaskExecutor returnToSurfaceExecutor
	) {
		this(baritoneExecutor, craftingExecutor, dropItemsExecutor, entityInteractionExecutor, smeltingExecutor, returnToSurfaceExecutor, new BlockInteractionTaskExecutor());
	}

	public DispatchingWorldTaskExecutor(
		WorldTaskExecutor baritoneExecutor,
		WorldTaskExecutor craftingExecutor,
		WorldTaskExecutor dropItemsExecutor,
		WorldTaskExecutor entityInteractionExecutor,
		WorldTaskExecutor smeltingExecutor,
		WorldTaskExecutor returnToSurfaceExecutor,
		WorldTaskExecutor blockInteractionExecutor
	) {
		this.baritoneExecutor = Objects.requireNonNull(baritoneExecutor, "baritoneExecutor");
		this.craftingExecutor = Objects.requireNonNull(craftingExecutor, "craftingExecutor");
		this.dropItemsExecutor = Objects.requireNonNull(dropItemsExecutor, "dropItemsExecutor");
		this.entityInteractionExecutor = Objects.requireNonNull(entityInteractionExecutor, "entityInteractionExecutor");
		this.smeltingExecutor = Objects.requireNonNull(smeltingExecutor, "smeltingExecutor");
		this.returnToSurfaceExecutor = Objects.requireNonNull(returnToSurfaceExecutor, "returnToSurfaceExecutor");
		this.blockInteractionExecutor = Objects.requireNonNull(blockInteractionExecutor, "blockInteractionExecutor");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty()) {
			activeType = null;
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		WorldTaskType previousActiveType = activeType;
		activeType = request.type();
		if (request.type() == WorldTaskType.CRAFT_RECIPE) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return craftingExecutor.tick(sessionSnapshot, activeTask);
		}
		if (request.type() == WorldTaskType.DROP_ITEMS) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return dropItemsExecutor.tick(sessionSnapshot, activeTask);
		}
		if (request.type() == WorldTaskType.ATTACK_ENTITY || request.type() == WorldTaskType.USE_ENTITY) {
			if (!isEntityInteractionType(previousActiveType)) {
				baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			}
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return entityInteractionExecutor.tick(sessionSnapshot, activeTask);
		}
		if (request.type() == WorldTaskType.SMELT_ITEMS || request.type() == WorldTaskType.COLLECT_SMELTED_ITEMS) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return smeltingExecutor.tick(sessionSnapshot, activeTask);
		}
		if (request.type() == WorldTaskType.RETURN_TO_SURFACE) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			return returnToSurfaceExecutor.tick(sessionSnapshot, activeTask);
		}
		if (request.type() == WorldTaskType.PLACE_BLOCK || request.type() == WorldTaskType.USE_BLOCK) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			craftingExecutor.tick(sessionSnapshot, Optional.empty());
			dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
			entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
			smeltingExecutor.tick(sessionSnapshot, Optional.empty());
			returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
			return blockInteractionExecutor.tick(sessionSnapshot, activeTask);
		}

		craftingExecutor.tick(sessionSnapshot, Optional.empty());
		dropItemsExecutor.tick(sessionSnapshot, Optional.empty());
		entityInteractionExecutor.tick(sessionSnapshot, Optional.empty());
		smeltingExecutor.tick(sessionSnapshot, Optional.empty());
		returnToSurfaceExecutor.tick(sessionSnapshot, Optional.empty());
		blockInteractionExecutor.tick(sessionSnapshot, Optional.empty());
		return baritoneExecutor.tick(sessionSnapshot, activeTask);
	}

	private static boolean isEntityInteractionType(WorldTaskType type) {
		return type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY;
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		if (activeType == WorldTaskType.CRAFT_RECIPE) {
			return craftingExecutor.snapshot();
		}
		if (activeType == WorldTaskType.DROP_ITEMS) {
			return dropItemsExecutor.snapshot();
		}
		if (activeType == WorldTaskType.ATTACK_ENTITY || activeType == WorldTaskType.USE_ENTITY) {
			return entityInteractionExecutor.snapshot();
		}
		if (activeType == WorldTaskType.SMELT_ITEMS || activeType == WorldTaskType.COLLECT_SMELTED_ITEMS) {
			return smeltingExecutor.snapshot();
		}
		if (activeType == WorldTaskType.RETURN_TO_SURFACE) {
			return returnToSurfaceExecutor.snapshot();
		}
		if (activeType == WorldTaskType.PLACE_BLOCK || activeType == WorldTaskType.USE_BLOCK) {
			return blockInteractionExecutor.snapshot();
		}
		if (activeType != null) {
			return baritoneExecutor.snapshot();
		}
		return TaskExecutionSnapshot.idle();
	}

	@Override
	public void onWorldLeave() {
		activeType = null;
		baritoneExecutor.onWorldLeave();
		craftingExecutor.onWorldLeave();
		dropItemsExecutor.onWorldLeave();
		entityInteractionExecutor.onWorldLeave();
		smeltingExecutor.onWorldLeave();
		returnToSurfaceExecutor.onWorldLeave();
		blockInteractionExecutor.onWorldLeave();
	}

	@Override
	public void shutdown() {
		activeType = null;
		baritoneExecutor.shutdown();
		craftingExecutor.shutdown();
		dropItemsExecutor.shutdown();
		entityInteractionExecutor.shutdown();
		smeltingExecutor.shutdown();
		returnToSurfaceExecutor.shutdown();
		blockInteractionExecutor.shutdown();
	}
}
