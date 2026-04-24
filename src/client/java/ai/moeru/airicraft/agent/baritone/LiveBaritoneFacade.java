package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class LiveBaritoneFacade implements BaritoneFacade {
	private final IBaritone baritone;
	private final Runnable settingsApplier;
	private final ConcurrentLinkedQueue<PathEvent> pathEvents = new ConcurrentLinkedQueue<>();

	public LiveBaritoneFacade() {
		this(BaritoneAPI.getProvider().getPrimaryBaritone(), BaritoneSettingsProfile::apply);
	}

	LiveBaritoneFacade(IBaritone baritone, Runnable settingsApplier) {
		this.baritone = baritone;
		this.settingsApplier = Objects.requireNonNull(settingsApplier, "settingsApplier");
		if (baritone != null) {
			baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
				@Override
				public void onPathEvent(PathEvent event) {
					pathEvents.add(event);
				}
			});
		}
	}

	@Override
	public boolean isLoaded() {
		return baritone != null;
	}

	@Override
	public void applySettings() {
		settingsApplier.run();
	}

	@Override
	public void startFollow(String playerName) {
		if (!isLoaded() || playerName == null || playerName.isBlank()) {
			return;
		}
		pathEvents.clear();
		baritone.getFollowProcess().follow(entity ->
			entity != null
				&& entity.getName() != null
				&& playerName.equalsIgnoreCase(entity.getName().getString())
		);
	}

	@Override
	public void startNavigate(GoalPosition position) {
		if (!isLoaded() || position == null) {
			return;
		}
		pathEvents.clear();
		baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(position.x(), position.y(), position.z()));
	}

	@Override
	public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		if (!isLoaded() || position == null) {
			return;
		}
		pathEvents.clear();
		baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
			new BlockPos(position.x(), position.y(), position.z()),
			Math.max(1, radiusBlocks)
		));
	}

	@Override
	public void startMine(GoalMineSpec spec) {
		if (!isLoaded() || spec == null) {
			return;
		}
		pathEvents.clear();
		baritone.getMineProcess().mineByName(spec.quantity(), spec.blockIds().toArray(String[]::new));
	}

	@Override
	public void cancel() {
		if (!isLoaded()) {
			return;
		}
		pathEvents.clear();
		baritone.getPathingBehavior().cancelEverything();
	}

	@Override
	public Optional<String> activeProcessName() {
		if (!isLoaded()) {
			return Optional.empty();
		}
		return baritone.getPathingControlManager()
			.mostRecentInControl()
			.map(IBaritoneProcess::displayName0);
	}

	@Override
	public Optional<Double> estimatedTicksToGoal() {
		if (!isLoaded()) {
			return Optional.empty();
		}
		IPathingBehavior pathingBehavior = baritone.getPathingBehavior();
		return pathingBehavior == null ? Optional.empty() : pathingBehavior.estimatedTicksToGoal();
	}

	@Override
	public Optional<String> pollPathEvent() {
		PathEvent event = pathEvents.poll();
		return event == null ? Optional.empty() : Optional.of(event.name());
	}

	@Override
	public boolean navigationGoalReached(GoalPosition position) {
		if (position == null) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return false;
		}

		BlockPos playerBlockPos = client.player.getBlockPos();
		if (position.exactY()) {
			return playerBlockPos.getX() == position.x()
				&& playerBlockPos.getY() == position.y()
				&& playerBlockPos.getZ() == position.z();
		}
		return playerBlockPos.getX() == position.x()
			&& playerBlockPos.getZ() == position.z()
			&& Math.abs(playerBlockPos.getY() - position.y()) <= 1;
	}
}
