package ai.moeru.airicraft.agent.idle;

import java.util.List;

public record IdleIdeasConfig(
	boolean enabled,
	int initialDelaySeconds,
	int cooldownSeconds,
	List<String> ideas
) {
	public IdleIdeasConfig {
		initialDelaySeconds = Math.max(0, initialDelaySeconds);
		cooldownSeconds = Math.max(0, cooldownSeconds);
		ideas = ideas == null ? List.of() : List.copyOf(ideas);
	}

	public static IdleIdeasConfig defaults() {
		return new IdleIdeasConfig(true, 30, 90, List.of(
			"Work toward stone-age tools: gather wood logs, craft planks and sticks, build a wooden pickaxe, mine cobblestone, then craft stone tools.",
			"Progress toward iron-age tools: find a cave or strip-mine for iron and coal, build a furnace, smelt iron, craft an iron pickaxe and sword.",
			"Build a minimal shelter so you survive the night. Even a dirt-block hut counts. Pick a flat spot near where you are.",
			"Once a basic shelter exists, briefly ask the player what kind of real home they want and where to put it. Do not over-ask.",
			"Stock up on basic food: kill cows, chickens, or pigs, or harvest crops; cook meat in the furnace."
		));
	}

	public static IdleIdeasConfig disabled() {
		return new IdleIdeasConfig(false, 30, 90, List.of());
	}
}
