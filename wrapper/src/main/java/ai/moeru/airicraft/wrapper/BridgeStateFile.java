package ai.moeru.airicraft.wrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BridgeStateFile {
	static final String PATH_PROPERTY = "airicraft.bridgeStateFile";
	static final String PATH_ENVIRONMENT_VARIABLE = "AIRICRAFT_BRIDGE_STATE_FILE";
	private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
	private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern STARTED_PATTERN = Pattern.compile("\"startedAtEpochMillis\"\\s*:\\s*(\\d+)");

	private BridgeStateFile() {
	}

	static Optional<BridgeState> read() {
		Path bridgeFile = bridgeFile();
		if (!Files.exists(bridgeFile)) {
			return Optional.empty();
		}

		try {
			String raw = Files.readString(bridgeFile, StandardCharsets.UTF_8);
			Matcher portMatcher = PORT_PATTERN.matcher(raw);
			Matcher tokenMatcher = TOKEN_PATTERN.matcher(raw);
			Matcher startedMatcher = STARTED_PATTERN.matcher(raw);
			if (!portMatcher.find() || !tokenMatcher.find() || !startedMatcher.find()) {
				return Optional.empty();
			}

			return Optional.of(new BridgeState(
				Integer.parseInt(portMatcher.group(1)),
				tokenMatcher.group(1),
				Long.parseLong(startedMatcher.group(1))
			));
		}
		catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	static void deleteIfPresent() {
		try {
			Files.deleteIfExists(bridgeFile());
		}
		catch (IOException ignored) {
		}
	}

	private static Path bridgeFile() {
		return resolvePath(
			System.getProperty(PATH_PROPERTY),
			System.getenv(PATH_ENVIRONMENT_VARIABLE),
			System.getProperty("user.home")
		);
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

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second == null || second.isBlank() ? null : second;
	}

	record BridgeState(int port, String token, long startedAtEpochMillis) {
	}
}
