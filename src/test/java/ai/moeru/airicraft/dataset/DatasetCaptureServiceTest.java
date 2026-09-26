package ai.moeru.airicraft.dataset;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.dataset.DatasetCaptureService.CaptureOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetCaptureServiceTest {
	@Test
	void defaultOptionsValidate() {
		assertDoesNotThrow(() -> CaptureOptions.defaults().validate());
	}

	@Test
	void rejectsInvalidStride() {
		CaptureOptions options = new CaptureOptions(null, null, null, null, 0, 96, 32, 8, 24, true, true, null);
		assertThrows(BridgeUnavailableException.class, options::validate);
	}

	@Test
	void rejectsOutOfRangeAngles() {
		assertThrows(BridgeUnavailableException.class, () -> new CaptureOptions(
			null, 190.0, null, null, 8, 96, 32, 8, 24, true, true, null).validate());
		assertThrows(BridgeUnavailableException.class, () -> new CaptureOptions(
			null, null, -95.0, null, 8, 96, 32, 8, 24, true, true, null).validate());
	}

	@Test
	void rejectsMalformedLookAt() {
		assertThrows(BridgeUnavailableException.class, () -> new CaptureOptions(
			null, null, null, new double[]{1.0, 2.0}, 8, 96, 32, 8, 24, true, true, null).validate());
	}

	@Test
	void rejectsOversizedRegion() {
		assertThrows(BridgeUnavailableException.class, () -> new CaptureOptions(
			null, null, null, null, 8, 96, 65, 8, 24, true, true, null).validate());
		assertThrows(BridgeUnavailableException.class, () -> new CaptureOptions(
			null, null, null, null, 8, 96, 64, 400, 400, true, true, null).validate());
	}
}
