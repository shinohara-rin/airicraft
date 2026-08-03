package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickTraceRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

final class ClientTickTraceIndicator {
	private static final String LABEL = "TRACE";
	private static final int MARGIN = 6;
	private static final int PADDING = 4;
	private static final int BACKGROUND_COLOR = 0xD0205EBA;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	void render(
		MinecraftClient client,
		DrawContext drawContext,
		ClientTickTraceRecorder.TraceStatus traceStatus
	) {
		if (!isVisible(traceStatus) || client == null || drawContext == null) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		if (textRenderer == null) {
			return;
		}

		IndicatorBounds bounds = layout(
			drawContext.getScaledWindowWidth(),
			textRenderer.getWidth(LABEL),
			textRenderer.fontHeight
		);
		drawContext.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), BACKGROUND_COLOR);
		drawContext.drawText(
			textRenderer,
			LABEL,
			bounds.left() + PADDING,
			bounds.top() + PADDING,
			TEXT_COLOR,
			true
		);
	}

	static boolean isVisible(ClientTickTraceRecorder.TraceStatus traceStatus) {
		return traceStatus != null && traceStatus.active();
	}

	static IndicatorBounds layout(int scaledWindowWidth, int textWidth, int fontHeight) {
		int panelWidth = Math.max(0, textWidth) + (PADDING * 2);
		int right = Math.max(MARGIN, scaledWindowWidth - MARGIN);
		int left = Math.max(0, right - panelWidth);
		int top = MARGIN;
		int bottom = top + Math.max(0, fontHeight) + (PADDING * 2);
		return new IndicatorBounds(left, top, right, bottom);
	}

	record IndicatorBounds(int left, int top, int right, int bottom) {
	}
}
