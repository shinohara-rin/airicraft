package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeStateFileTest {
	private final String originalBridgeStateFile = System.getProperty(BridgeStateFile.PATH_PROPERTY);

	@AfterEach
	void restoreBridgeStateProperty() {
		if (originalBridgeStateFile == null) {
			System.clearProperty(BridgeStateFile.PATH_PROPERTY);
		}
		else {
			System.setProperty(BridgeStateFile.PATH_PROPERTY, originalBridgeStateFile);
		}
	}

	@Test
	void explicitPropertyHasPriorityOverEnvironment(@TempDir Path tempDir) {
		Path propertyPath = tempDir.resolve("property.json");
		Path environmentPath = tempDir.resolve("environment.json");

		Path resolved = BridgeStateFile.resolvePath(
			propertyPath.toString(),
			environmentPath.toString(),
			tempDir.toString()
		);

		assertEquals(propertyPath, resolved);
	}

	@Test
	void explicitEnvironmentOverridesDefaultHome(@TempDir Path tempDir) {
		Path environmentPath = tempDir.resolve("environment.json");

		Path resolved = BridgeStateFile.resolvePath(null, environmentPath.toString(), tempDir.toString());

		assertEquals(environmentPath, resolved);
	}

	@Test
	void defaultPathUsesAiricraftDirectory(@TempDir Path tempDir) {
		Path resolved = BridgeStateFile.resolvePath(null, null, tempDir.toString());

		assertEquals(tempDir.resolve(".airicraft/bridge-state.json"), resolved);
	}

	@Test
	void readUsesConfiguredProperty(@TempDir Path tempDir) throws Exception {
		Path bridgeStatePath = tempDir.resolve("worker-bridge.json");
		Files.writeString(bridgeStatePath, """
			{"port":1234,"token":"worker-token","startedAtEpochMillis":5678}
			""");
		System.setProperty(BridgeStateFile.PATH_PROPERTY, bridgeStatePath.toString());

		BridgeStateFile.BridgeState state = BridgeStateFile.read().orElseThrow();

		assertEquals(1234, state.port());
		assertEquals("worker-token", state.token());
		assertEquals(5678L, state.startedAtEpochMillis());
	}
}
