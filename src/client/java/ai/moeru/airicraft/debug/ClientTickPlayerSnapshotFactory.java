package ai.moeru.airicraft.debug;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.PlayerInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ClientTickPlayerSnapshotFactory {
	private ClientTickPlayerSnapshotFactory() {
	}

	static ClientTickPlayerSnapshot capture(MinecraftClient client, ClientPlayerEntity player) {
		var position = player.getPos();
		var velocity = player.getVelocity();
		var bounds = player.getBoundingBox();
		var abilities = player.getAbilities();
		PlayerInput input = player.input == null || player.input.playerInput == null
			? PlayerInput.DEFAULT
			: player.input.playerInput;
		return new ClientTickPlayerSnapshot(
			player.getId(),
			player.getUuidAsString(),
			player.getName().getString(),
			Registries.ENTITY_TYPE.getId(player.getType()).toString(),
			client.interactionManager == null ? null : client.interactionManager.getCurrentGameMode().asString(),
			new ClientTickPlayerSnapshot.PositionSnapshot(
				position.x,
				position.y,
				position.z,
				player.getBlockX(),
				player.getBlockY(),
				player.getBlockZ()
			),
			new ClientTickPlayerSnapshot.RotationSnapshot(
				player.getYaw(),
				player.getPitch(),
				player.getHeadYaw(),
				player.getBodyYaw()
			),
			new ClientTickPlayerSnapshot.VectorSnapshot(velocity.x, velocity.y, velocity.z),
			new ClientTickPlayerSnapshot.BoundsSnapshot(
				bounds.minX,
				bounds.minY,
				bounds.minZ,
				bounds.maxX,
				bounds.maxY,
				bounds.maxZ
			),
			new ClientTickPlayerSnapshot.MovementSnapshot(
				player.getPose().name().toLowerCase(Locale.ROOT),
				player.isOnGround(),
				player.horizontalCollision,
				player.verticalCollision,
				player.isSprinting(),
				player.isSneaking(),
				player.isSwimming(),
				player.isCrawling(),
				player.isGliding(),
				player.isClimbing(),
				player.isTouchingWater(),
				player.isSubmergedInWater(),
				player.isInLava(),
				player.fallDistance,
				player.isUsingItem(),
				player.getItemUseTime(),
				player.getItemUseTimeLeft()
			),
			new ClientTickPlayerSnapshot.VitalsSnapshot(
				player.isAlive(),
				player.isRemoved(),
				player.getHealth(),
				player.getMaxHealth(),
				player.getAbsorptionAmount(),
				player.getArmor(),
				player.getAir(),
				player.getMaxAir(),
				player.getFireTicks(),
				player.getFrozenTicks(),
				player.hurtTime,
				player.deathTime
			),
			new ClientTickPlayerSnapshot.HungerSnapshot(
				player.getHungerManager().getFoodLevel(),
				player.getHungerManager().getSaturationLevel()
			),
			new ClientTickPlayerSnapshot.ExperienceSnapshot(
				player.experienceLevel,
				player.totalExperience,
				player.experienceProgress
			),
			new ClientTickPlayerSnapshot.AbilitiesSnapshot(
				abilities.invulnerable,
				abilities.flying,
				abilities.allowFlying,
				abilities.creativeMode,
				abilities.allowModifyWorld,
				abilities.getFlySpeed(),
				abilities.getWalkSpeed()
			),
			new ClientTickPlayerSnapshot.InputSnapshot(
				input.forward(),
				input.backward(),
				input.left(),
				input.right(),
				input.jump(),
				input.sneak(),
				input.sprint()
			),
			player.getInventory().size(),
			player.getInventory().getSelectedSlot(),
			inventory(player),
			equipment(player),
			statusEffects(player.getStatusEffects()),
			attributes(player.getAttributes().getAttributesToSend())
		);
	}

	static ClientTickPlayerSnapshot.ItemStackSnapshot itemStack(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		return new ClientTickPlayerSnapshot.ItemStackSnapshot(
			slot,
			Registries.ITEM.getId(stack.getItem()).toString(),
			stack.getName().getString(),
			stack.getCount(),
			stack.getMaxCount(),
			stack.isDamageable(),
			stack.getDamage(),
			stack.getMaxDamage(),
			stack.hasGlint()
		);
	}

	static List<ClientTickPlayerSnapshot.StatusEffectSnapshot> statusEffects(
		Iterable<StatusEffectInstance> effects
	) {
		List<ClientTickPlayerSnapshot.StatusEffectSnapshot> snapshots = new ArrayList<>();
		for (StatusEffectInstance effect : effects) {
			snapshots.add(new ClientTickPlayerSnapshot.StatusEffectSnapshot(
				Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString(),
				effect.getAmplifier(),
				effect.getDuration(),
				effect.isAmbient(),
				effect.shouldShowParticles(),
				effect.shouldShowIcon()
			));
		}
		snapshots.sort(Comparator.comparing(ClientTickPlayerSnapshot.StatusEffectSnapshot::effectId));
		return List.copyOf(snapshots);
	}

	static List<ClientTickPlayerSnapshot.AttributeSnapshot> attributes(
		Iterable<EntityAttributeInstance> attributes
	) {
		List<ClientTickPlayerSnapshot.AttributeSnapshot> snapshots = new ArrayList<>();
		for (EntityAttributeInstance attribute : attributes) {
			snapshots.add(new ClientTickPlayerSnapshot.AttributeSnapshot(
				Registries.ATTRIBUTE.getId(attribute.getAttribute().value()).toString(),
				attribute.getBaseValue(),
				attribute.getValue()
			));
		}
		snapshots.sort(Comparator.comparing(ClientTickPlayerSnapshot.AttributeSnapshot::attributeId));
		return List.copyOf(snapshots);
	}

	private static List<ClientTickPlayerSnapshot.ItemStackSnapshot> inventory(ClientPlayerEntity player) {
		List<ClientTickPlayerSnapshot.ItemStackSnapshot> snapshots = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ClientTickPlayerSnapshot.ItemStackSnapshot snapshot = itemStack(slot, player.getInventory().getStack(slot));
			if (snapshot != null) {
				snapshots.add(snapshot);
			}
		}
		return List.copyOf(snapshots);
	}

	static Map<String, ClientTickPlayerSnapshot.ItemStackSnapshot> equipment(LivingEntity player) {
		Map<String, ClientTickPlayerSnapshot.ItemStackSnapshot> snapshots = new LinkedHashMap<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ClientTickPlayerSnapshot.ItemStackSnapshot snapshot = itemStack(-1, player.getEquippedStack(slot));
			if (snapshot != null) {
				snapshots.put(slot.getName(), snapshot);
			}
		}
		return snapshots;
	}
}
