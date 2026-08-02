package ai.moeru.airicraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeDiscoveryFileTest {
	private final String originalBridgeStateFile = System.getProperty(BridgeDiscoveryFile.PATH_PROPERTY);

	@AfterEach
	void restoreBridgeStateProperty() {
		if (originalBridgeStateFile == null) {
			System.clearProperty(BridgeDiscoveryFile.PATH_PROPERTY);
		}
		else {
			System.setProperty(BridgeDiscoveryFile.PATH_PROPERTY, originalBridgeStateFile);
		}
	}

	@Test
	void explicitPropertyHasPriorityOverEnvironment(@TempDir Path tempDir) {
		Path propertyPath = tempDir.resolve("property.json");
		Path environmentPath = tempDir.resolve("environment.json");

		Path resolved = BridgeDiscoveryFile.resolvePath(
			propertyPath.toString(),
			environmentPath.toString(),
			tempDir.toString()
		);

		assertEquals(propertyPath, resolved);
	}

	@Test
	void explicitEnvironmentOverridesDefaultHome(@TempDir Path tempDir) {
		Path environmentPath = tempDir.resolve("environment.json");

		Path resolved = BridgeDiscoveryFile.resolvePath(null, environmentPath.toString(), tempDir.toString());

		assertEquals(environmentPath, resolved);
	}

	@Test
	void defaultPathUsesAiricraftDirectory(@TempDir Path tempDir) {
		Path resolved = BridgeDiscoveryFile.resolvePath(null, null, tempDir.toString());

		assertEquals(tempDir.resolve(".airicraft/bridge-state.json"), resolved);
	}

	@Test
	void configuredFileOwnsItsLifecycle(@TempDir Path tempDir) throws Exception {
		Path path = tempDir.resolve("worker/bridge-state.json");
		BridgeDiscoveryFile file = new BridgeDiscoveryFile(path);
		BridgeSessionState state = new BridgeSessionState(1234, "token", 5678L, 9012L);

		file.write(state);

		assertTrue(Files.exists(path));
		assertEquals(state, file.read());

		file.deleteIfPresent();

		assertFalse(Files.exists(path));
	}

	@Test
	void defaultFactoryUsesConfiguredProperty(@TempDir Path tempDir) {
		Path path = tempDir.resolve("worker-bridge.json");
		System.setProperty(BridgeDiscoveryFile.PATH_PROPERTY, path.toString());

		BridgeDiscoveryFile file = BridgeDiscoveryFile.createDefault();

		assertEquals(path, file.path());
	}
}
