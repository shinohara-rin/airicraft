package ai.moeru.airicraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public final class BridgeDiscoveryFile {
	public static final String PATH_PROPERTY = "airicraft.bridgeStateFile";
	public static final String PATH_ENVIRONMENT_VARIABLE = "AIRICRAFT_BRIDGE_STATE_FILE";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final Path path;

	public BridgeDiscoveryFile(Path path) {
		this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
	}

	public static BridgeDiscoveryFile createDefault() {
		return new BridgeDiscoveryFile(resolvePath(
			System.getProperty(PATH_PROPERTY),
			System.getenv(PATH_ENVIRONMENT_VARIABLE),
			System.getProperty("user.home")
		));
	}

	static Path resolvePath(String propertyValue, String environmentValue, String userHome) {
		String explicitPath = firstNonBlank(propertyValue, environmentValue);
		if (explicitPath != null) {
			return Path.of(explicitPath).toAbsolutePath().normalize();
		}
		return Paths.get(Objects.requireNonNull(userHome, "userHome"), ".airicraft", "bridge-state.json")
			.toAbsolutePath()
			.normalize();
	}

	public Path path() {
		return path;
	}

	public void write(BridgeSessionState state) throws IOException {
		Files.createDirectories(path.getParent());

		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(state, writer);
		}
	}

	public BridgeSessionState read() throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return GSON.fromJson(reader, BridgeSessionState.class);
		}
	}

	public void deleteIfPresent() {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to delete bridge discovery file at {}", path, exception);
		}
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second == null || second.isBlank() ? null : second;
	}
}
