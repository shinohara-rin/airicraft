package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickDebugController;
import ai.moeru.airicraft.debug.ClientTickTraceRecorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

final class ClientTickIndicator {
	private static final String PAUSED_LABEL = "PAUSED";
	private static final String TRACE_LABEL = "TRACE";
	private static final int PAUSED_COLOR = 0xD0B52222;
	private static final int TRACE_COLOR = 0xD0205EBA;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int MARGIN = 6;
	private static final int PADDING = 4;

	void render(
		MinecraftClient client,
		DrawContext drawContext,
		ClientTickDebugController.DebugStatus debugStatus,
		ClientTickTraceRecorder.TraceStatus traceStatus
	) {
		if ((debugStatus == null || !debugStatus.paused()) && (traceStatus == null || !traceStatus.active())) {
			return;
		}
		if (client == null || drawContext == null || client.textRenderer == null) {
			return;
		}

		TextRenderer textRenderer = client.textRenderer;
		for (IndicatorPanel panel : layout(
			debugStatus,
			traceStatus,
			drawContext.getScaledWindowWidth(),
			textRenderer.getWidth(PAUSED_LABEL),
			textRenderer.getWidth(TRACE_LABEL),
			textRenderer.fontHeight
		)) {
			IndicatorBounds bounds = panel.bounds();
			drawContext.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), panel.backgroundColor());
			drawContext.drawText(
				textRenderer,
				panel.label(),
				bounds.left() + PADDING,
				bounds.top() + PADDING,
				TEXT_COLOR,
				true
			);
		}
	}

	static List<IndicatorPanel> layout(
		ClientTickDebugController.DebugStatus debugStatus,
		ClientTickTraceRecorder.TraceStatus traceStatus,
		int scaledWindowWidth,
		int pausedTextWidth,
		int traceTextWidth,
		int fontHeight
	) {
		List<IndicatorPanel> panels = new ArrayList<>(2);
		int top = MARGIN;
		if (debugStatus != null && debugStatus.paused()) {
			IndicatorBounds bounds = bounds(scaledWindowWidth, pausedTextWidth, fontHeight, top);
			panels.add(new IndicatorPanel(PAUSED_LABEL, PAUSED_COLOR, bounds));
			top = bounds.bottom();
		}
		if (traceStatus != null && traceStatus.active()) {
			panels.add(new IndicatorPanel(
				TRACE_LABEL,
				TRACE_COLOR,
				bounds(scaledWindowWidth, traceTextWidth, fontHeight, top)
			));
		}
		return List.copyOf(panels);
	}

	private static IndicatorBounds bounds(int scaledWindowWidth, int textWidth, int fontHeight, int top) {
		int right = Math.max(MARGIN, scaledWindowWidth - MARGIN);
		int left = Math.max(0, right - Math.max(0, textWidth) - (PADDING * 2));
		return new IndicatorBounds(left, top, right, top + Math.max(0, fontHeight) + (PADDING * 2));
	}

	record IndicatorPanel(String label, int backgroundColor, IndicatorBounds bounds) {
	}

	record IndicatorBounds(int left, int top, int right, int bottom) {
	}
}
