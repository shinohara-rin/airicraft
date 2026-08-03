package ai.moeru.airicraft.debug;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ClientTickPlayerSnapshot(
	int entityId,
	String uuid,
	String name,
	String entityTypeId,
	String gameMode,
	PositionSnapshot position,
	RotationSnapshot rotation,
	VectorSnapshot velocity,
	BoundsSnapshot bounds,
	MovementSnapshot movement,
	VitalsSnapshot vitals,
	HungerSnapshot hunger,
	ExperienceSnapshot experience,
	AbilitiesSnapshot abilities,
	InputSnapshot input,
	int inventorySize,
	int selectedHotbarSlot,
	List<ItemStackSnapshot> inventory,
	Map<String, ItemStackSnapshot> equipment,
	List<StatusEffectSnapshot> statusEffects,
	List<AttributeSnapshot> attributes
) {
	public ClientTickPlayerSnapshot {
		inventory = List.copyOf(inventory);
		equipment = Collections.unmodifiableMap(new LinkedHashMap<>(equipment));
		statusEffects = List.copyOf(statusEffects);
		attributes = List.copyOf(attributes);
	}

	public record PositionSnapshot(
		double x,
		double y,
		double z,
		int blockX,
		int blockY,
		int blockZ
	) {
	}

	public record RotationSnapshot(float yaw, float pitch, float headYaw, float bodyYaw) {
	}

	public record VectorSnapshot(double x, double y, double z) {
	}

	public record BoundsSnapshot(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	public record MovementSnapshot(
		String pose,
		boolean onGround,
		boolean horizontalCollision,
		boolean verticalCollision,
		boolean sprinting,
		boolean sneaking,
		boolean swimming,
		boolean crawling,
		boolean gliding,
		boolean climbing,
		boolean touchingWater,
		boolean submergedInWater,
		boolean inLava,
		double fallDistance,
		boolean usingItem,
		int itemUseTime,
		int itemUseTimeLeft
	) {
	}

	public record VitalsSnapshot(
		boolean alive,
		boolean removed,
		float health,
		float maxHealth,
		float absorption,
		int armor,
		int air,
		int maxAir,
		int fireTicks,
		int frozenTicks,
		int hurtTime,
		int deathTime
	) {
	}

	public record HungerSnapshot(int food, float saturation) {
	}

	public record ExperienceSnapshot(int level, int total, float progress) {
	}

	public record AbilitiesSnapshot(
		boolean invulnerable,
		boolean flying,
		boolean allowFlying,
		boolean creativeMode,
		boolean allowModifyWorld,
		float flySpeed,
		float walkSpeed
	) {
	}

	public record InputSnapshot(
		boolean forward,
		boolean backward,
		boolean left,
		boolean right,
		boolean jump,
		boolean sneak,
		boolean sprint
	) {
	}

	public record ItemStackSnapshot(
		int slot,
		String itemId,
		String name,
		int count,
		int maxCount,
		boolean damageable,
		int damage,
		int maxDamage,
		boolean glint
	) {
	}

	public record StatusEffectSnapshot(
		String effectId,
		int amplifier,
		int durationTicks,
		boolean ambient,
		boolean showParticles,
		boolean showIcon
	) {
	}

	public record AttributeSnapshot(String attributeId, double baseValue, double value) {
	}
}
