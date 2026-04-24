package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.listener.IEventBus;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.IMineProcess;
import baritone.api.process.IBaritoneProcess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveBaritoneFacadeTest {
	@Test
	void applySettingsAndPathEventsWorkThroughAnInjectedBaritoneHarness() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		AtomicReference<Boolean> settingsApplied = new AtomicReference<>(false);
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> settingsApplied.set(true));

		facade.applySettings();
		facade.startNavigate(new GoalPosition(12, 64, -8, true));
		harness.publishPathEvent(PathEvent.AT_GOAL);

		assertTrue(settingsApplied.get());
		assertEquals(Optional.of("AT_GOAL"), facade.pollPathEvent());
		assertEquals(Optional.of("custom_goal"), facade.activeProcessName());
		assertEquals(Optional.of(37.5D), facade.estimatedTicksToGoal());

		assertEquals(1, harness.navigateCalls.size());
		assertInstanceOf(GoalBlock.class, harness.navigateCalls.get(0));
		GoalBlock goal = (GoalBlock) harness.navigateCalls.get(0);
		assertEquals(12, goal.x);
		assertEquals(64, goal.y);
		assertEquals(-8, goal.z);
	}

	@Test
	void startFollowMineAndCancelDelegateToTheInjectedBaritoneProcesses() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});
		GoalMineSpec mineSpec = new GoalMineSpec(List.of("minecraft:oak_log"), 8);

		facade.startFollow("Alice");
		facade.startMine(mineSpec);
		facade.cancel();

		assertNotNull(harness.followPredicate.get());
		assertEquals(Optional.of("mine"), facade.activeProcessName());
		assertEquals(1, harness.mineCalls.size());
		assertEquals(8, harness.mineCalls.get(0)[0]);
		assertArrayEquals(new String[] {"minecraft:oak_log"}, (String[]) harness.mineCalls.get(0)[1]);
		assertTrue(harness.cancelEverythingCalled.get());
	}

	@Test
	void startNavigateNearUsesGoalNearRadius() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});

		facade.startNavigateNear(new GoalPosition(12, 64, -8, false), 3);

		assertEquals(1, harness.navigateCalls.size());
		assertInstanceOf(GoalNear.class, harness.navigateCalls.get(0));
		GoalNear goal = (GoalNear) harness.navigateCalls.get(0);
		assertTrue(goal.isInGoal(12, 64, -8));
		assertTrue(goal.isInGoal(14, 64, -8));
		assertFalse(goal.isInGoal(16, 64, -8));
	}

	private static final class RecordingBaritoneHarness {
		private final AtomicReference<AbstractGameEventListener> pathListener = new AtomicReference<>();
		private final AtomicReference<String> activeProcessName = new AtomicReference<>();
		private final AtomicReference<Object> followPredicate = new AtomicReference<>();
		private final AtomicReference<Boolean> cancelEverythingCalled = new AtomicReference<>(false);
		private final AtomicReference<Double> estimatedTicksToGoal = new AtomicReference<>(37.5D);
		private final List<Object> navigateCalls = new ArrayList<>();
		private final List<Object[]> mineCalls = new ArrayList<>();
		private final IPathingBehavior pathingBehavior = proxy(IPathingBehavior.class, (proxy, method, args) -> switch (method.getName()) {
			case "estimatedTicksToGoal" -> Optional.ofNullable(estimatedTicksToGoal.get());
			case "cancelEverything" -> {
				cancelEverythingCalled.set(true);
				yield true;
			}
			case "getGoal", "getCurrent", "getNext" -> null;
			case "getInProgress" -> Optional.empty();
			case "isPathing", "hasPath" -> false;
			default -> defaultValue(method);
		});
		private final IFollowProcess followProcess = proxy(IFollowProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "follow" -> {
				followPredicate.set(args[0]);
				activeProcessName.set("follow");
				yield null;
			}
			case "pickup" -> null;
			case "following" -> List.of();
			case "currentFilter" -> null;
			case "cancel" -> null;
			case "isActive" -> false;
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			case "displayName0" -> "follow";
			default -> defaultValue(method);
		});
		private final ICustomGoalProcess customGoalProcess = proxy(ICustomGoalProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "setGoalAndPath", "setGoal" -> {
				navigateCalls.add(args[0]);
				activeProcessName.set("custom_goal");
				yield null;
			}
			case "path" -> null;
			case "getGoal", "mostRecentGoal" -> navigateCalls.isEmpty() ? null : navigateCalls.get(navigateCalls.size() - 1);
			case "isActive" -> false;
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			case "displayName0" -> "custom_goal";
			default -> defaultValue(method);
		});
		private final IMineProcess mineProcess = proxy(IMineProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "mineByName" -> {
				mineCalls.add(args.clone());
				activeProcessName.set("mine");
				yield null;
			}
			case "mine", "cancel" -> null;
			case "isActive" -> false;
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			case "displayName0" -> "mine";
			default -> defaultValue(method);
		});
		private final IBaritoneProcess activeProcess = proxy(IBaritoneProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "displayName0" -> activeProcessName.get();
			case "isActive" -> true;
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			default -> defaultValue(method);
		});
		private final IPathingControlManager pathingControlManager = proxy(IPathingControlManager.class, (proxy, method, args) -> switch (method.getName()) {
			case "mostRecentInControl" -> Optional.ofNullable(activeProcessName.get()).map(name -> activeProcess);
			case "mostRecentCommand" -> Optional.empty();
			default -> defaultValue(method);
		});
		private final IEventBus eventBus = proxy(IEventBus.class, (proxy, method, args) -> switch (method.getName()) {
			case "registerEventListener" -> {
				pathListener.set((AbstractGameEventListener) args[0]);
				yield null;
			}
			default -> defaultValue(method);
		});
		private final IBaritone baritone = proxy(IBaritone.class, (proxy, method, args) -> switch (method.getName()) {
			case "getPathingBehavior" -> pathingBehavior;
			case "getFollowProcess" -> followProcess;
			case "getMineProcess" -> mineProcess;
			case "getCustomGoalProcess" -> customGoalProcess;
			case "getPathingControlManager" -> pathingControlManager;
			case "getGameEventHandler" -> eventBus;
			case "getBuilderProcess", "getExploreProcess", "getFarmProcess", "getGetToBlockProcess", "getElytraProcess", "getWorldProvider", "getInputOverrideHandler", "getPlayerContext", "getSelectionManager", "getCommandManager" -> null;
			case "openClick" -> null;
			default -> defaultValue(method);
		});

		IBaritone baritone() {
			return baritone;
		}

		void publishPathEvent(PathEvent pathEvent) {
			AbstractGameEventListener listener = pathListener.get();
			if (listener == null) {
				throw new IllegalStateException("Expected path event listener to be registered");
			}
			listener.onPathEvent(pathEvent);
		}

		private static <T> T proxy(Class<T> type, InvocationHandler handler) {
			Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
			return type.cast(proxy);
		}

		private static Object defaultValue(Method method) {
			Class<?> returnType = method.getReturnType();
			if (!returnType.isPrimitive()) {
				return null;
			}
			if (returnType == boolean.class) {
				return false;
			}
			if (returnType == int.class) {
				return 0;
			}
			if (returnType == long.class) {
				return 0L;
			}
			if (returnType == double.class) {
				return 0.0D;
			}
			if (returnType == float.class) {
				return 0.0F;
			}
			if (returnType == short.class) {
				return (short) 0;
			}
			if (returnType == byte.class) {
				return (byte) 0;
			}
			if (returnType == char.class) {
				return '\0';
			}
			throw new IllegalStateException("Unsupported primitive return type: " + returnType.getName());
		}
	}
}
