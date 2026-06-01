package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.FirstPersonScreenshotService;

import java.util.concurrent.CompletableFuture;

public interface CurrentViewVisionTool {
	boolean isConfigured();

	CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture();

	default CompletableFuture<ViewCaptureResult> requestCapture(ViewCaptureRequest request) {
		return requestCapture().thenApply(screenshot -> new ViewCaptureResult(screenshot, java.util.List.of()));
	}

	CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt);

	default CompletableFuture<VisionDescription> requestDescription(LlmImageAttachment imageAttachment, String prompt) {
		String format = imageAttachment.mimeType().startsWith("image/")
			? imageAttachment.mimeType().substring("image/".length())
			: imageAttachment.mimeType();
		return requestDescription(new FirstPersonScreenshotService.CapturedScreenshot(
			format,
			0,
			0,
			0,
			0,
			System.currentTimeMillis(),
			imageAttachment.imageBytes()
		), prompt);
	}

	default CompletableFuture<VisionDescription> requestDescription(String prompt) {
		return requestCapture().thenCompose(screenshot -> requestDescription(screenshot, prompt));
	}

	static CurrentViewVisionTool disabled() {
		return new CurrentViewVisionTool() {
			@Override
			public boolean isConfigured() {
				return false;
			}

			@Override
			public CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
				return CompletableFuture.failedFuture(
					new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Vision provider is not configured")
				);
			}

			@Override
			public CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) {
				return CompletableFuture.failedFuture(
					new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Vision provider is not configured")
				);
			}
		};
	}
}
