package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.FirstPersonScreenshotService;

import java.util.List;

public record ViewCaptureResult(
	FirstPersonScreenshotService.CapturedScreenshot screenshot,
	List<String> metadataLines
) {
	public ViewCaptureResult {
		metadataLines = metadataLines == null ? List.of() : List.copyOf(metadataLines);
	}
}
