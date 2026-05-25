package ai.moeru.airicraft.agent.commonsense;

import java.util.List;

public record CommonsenseConfig(
	boolean enabled,
	List<String> rules
) {
	public CommonsenseConfig {
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public static CommonsenseConfig defaults() {
		return new CommonsenseConfig(true, List.of(
			"Any addressed player chat, admin message, or follow request preempts the current autonomous job. Drop the autonomous job immediately, serve the player, and only resume idle-think initiative once the player's request is handled.",
			"Reality-check the action against Minecraft mechanics before persisting on it. Example: mining a block by hand that requires a pickaxe will never break the block; pivot to acquiring the right tool first. Repeated attempts with no progress mean the world rules disallow this approach as-is.",
			"Craft tools at minimum-viable resource counts; do not stockpile. The moment you have the recipe materials (3 cobblestone + 2 sticks for a stone pickaxe), craft. Each tool should serve a concrete next goal, not be a milestone for its own sake.",
			"Match tool tier to target material. Wooden pickaxe mines stone and coal; stone pickaxe mines iron; iron pickaxe mines diamond. Picking the wrong tier wastes durability and time.",
			"Treat 'cannot break by hand' signals (leaves without shears, ice without silk touch, obsidian without diamond, etc.) as a prompt to acquire the right tool or pick a different target, not to retry."
		));
	}

	public static CommonsenseConfig disabled() {
		return new CommonsenseConfig(false, List.of());
	}

	public List<String> effectiveRules() {
		return enabled ? rules : List.of();
	}
}
