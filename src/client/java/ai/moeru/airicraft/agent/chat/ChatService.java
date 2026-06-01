package ai.moeru.airicraft.agent.chat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

public final class ChatService {
	public static final int MAX_CHAT_MESSAGE_LENGTH = 220;

	private long lastChatTick = -1L;
	private String lastChatText;

	public boolean send(MinecraftClient client, String text, long tick) {
		if (client == null || text == null || text.isBlank()) {
			return false;
		}

		String sanitizedText = sanitizeForChat(text);
		if (sanitizedText.isBlank()) {
			return false;
		}

		ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
		if (networkHandler == null) {
			return false;
		}

		networkHandler.sendChatMessage(sanitizedText);
		lastChatTick = tick;
		lastChatText = sanitizedText;
		return true;
	}

	public static String sanitizeForChat(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		StringBuilder builder = new StringBuilder(text.length());
		boolean previousWhitespace = false;
		for (int i = 0; i < text.length(); i++) {
			char current = text.charAt(i);
			if (current == '\r' || current == '\n' || current == '\t') {
				current = ' ';
			}
			if (Character.isISOControl(current) || current == '§') {
				continue;
			}
			if (Character.isWhitespace(current)) {
				if (!previousWhitespace) {
					builder.append(' ');
					previousWhitespace = true;
				}
				continue;
			}

			builder.append(current);
			previousWhitespace = false;
		}

		String sanitized = builder.toString().strip();
		while (sanitized.startsWith("/")) {
			sanitized = sanitized.substring(1).stripLeading();
		}
		if (sanitized.length() > MAX_CHAT_MESSAGE_LENGTH) {
			sanitized = sanitized.substring(0, MAX_CHAT_MESSAGE_LENGTH).stripTrailing();
		}
		return sanitized;
	}

	public long lastChatTick() {
		return lastChatTick;
	}

	public String lastChatText() {
		return lastChatText;
	}

	public void clear() {
		lastChatTick = -1L;
		lastChatText = null;
	}
}
