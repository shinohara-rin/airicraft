package ai.moeru.airicraft.agent.actions;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class ActionsetLibraryPaths {
	private ActionsetLibraryPaths() {
	}

	public static Path defaultRoot() {
		for (Path candidate : new Path[] {
			Path.of("actionsets"),
			Path.of("..", "actionsets")
		}) {
			if (Files.exists(candidate)) {
				return candidate;
			}
		}
		return Path.of("actionsets");
	}

	public static Path runtimeRoot() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("airicraft").resolve("actionsets");
		}
		catch (RuntimeException exception) {
			return defaultRoot();
		}
	}
}
