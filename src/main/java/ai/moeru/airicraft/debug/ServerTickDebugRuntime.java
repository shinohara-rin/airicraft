package ai.moeru.airicraft.debug;

/** Process-local owner for the currently running logical server's tick-debug state. */
public final class ServerTickDebugRuntime {
	private static final ServerTickDebugController CONTROLLER = new ServerTickDebugController();

	private ServerTickDebugRuntime() {
	}

	public static ServerTickDebugController controller() {
		return CONTROLLER;
	}

	public static boolean beginServerTick() {
		return CONTROLLER.beginServerTick();
	}

	public static void completeServerTick() {
		CONTROLLER.completeServerTick();
	}

	public static void reset() {
		CONTROLLER.reset();
	}
}
