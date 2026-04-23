package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class NearbyEntityService {
	public static final int DEFAULT_MAX_RESULTS = 32;

	private NearbyEntityService() {
	}

	public static List<NearbyEntitySnapshot> listNearbyEntities(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null) {
			return List.of();
		}
		return listNearbyEntities(
			client.player,
			client.world.getEntities(),
			EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS,
			DEFAULT_MAX_RESULTS
		);
	}

	static EntitySelectorResolver.EntityCandidate toCandidate(Entity entity) {
		Objects.requireNonNull(entity, "entity");
		return new EntitySelectorResolver.EntityCandidate(
			entity.getId(),
			entity.getUuidAsString(),
			entity.getName() == null ? null : entity.getName().getString(),
			Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
			entity.getX(),
			entity.getY(),
			entity.getZ(),
			entity.isAlive()
		);
	}

	static List<NearbyEntitySnapshot> listNearbyEntities(
		Entity self,
		Iterable<? extends Entity> entities,
		double nearbyRadius,
		int maxResults
	) {
		if (self == null || entities == null) {
			return List.of();
		}
		ArrayList<NearbyEntitySnapshot> snapshots = new ArrayList<>();
		for (Entity entity : entities) {
			if (entity == null || entity == self || entity.isRemoved()) {
				continue;
			}
			EntitySelectorResolver.EntityCandidate candidate = toCandidate(entity);
			if (!EntitySelectorResolver.isWithinRadius(
				self.getX(),
				self.getY(),
				self.getZ(),
				candidate.x(),
				candidate.y(),
				candidate.z(),
				nearbyRadius
			)) {
				continue;
			}
			double distance = Math.sqrt(candidate.squaredDistanceTo(self.getX(), self.getY(), self.getZ()));
			Float health = null;
			Float maxHealth = null;
			if (entity instanceof LivingEntity livingEntity) {
				health = livingEntity.getHealth();
				maxHealth = livingEntity.getMaxHealth();
			}
			snapshots.add(new NearbyEntitySnapshot(
				candidate.entityId(),
				candidate.uuid(),
				candidate.name(),
				candidate.entityTypeId(),
				candidate.x(),
				candidate.y(),
				candidate.z(),
				distance,
				candidate.alive(),
				health,
				maxHealth,
				entity instanceof PlayerEntity
			));
		}
		snapshots.sort(Comparator
			.comparingDouble(NearbyEntitySnapshot::distance)
			.thenComparing(snapshot -> safeText(snapshot.entityTypeId()))
			.thenComparing(snapshot -> safeText(snapshot.name()))
			.thenComparing(snapshot -> safeText(snapshot.uuid())));
		if (maxResults > 0 && snapshots.size() > maxResults) {
			return List.copyOf(snapshots.subList(0, maxResults));
		}
		return List.copyOf(snapshots);
	}

	private static String safeText(String value) {
		return value == null ? "" : value;
	}

	public record NearbyEntitySnapshot(
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		double distance,
		boolean alive,
		Float health,
		Float maxHealth,
		boolean isPlayer
	) {
		public String compactDescription() {
			StringBuilder builder = new StringBuilder("{")
				.append("uuid=").append(uuid)
				.append(", name=").append(name)
				.append(", entityTypeId=").append(entityTypeId)
				.append(", distance=").append(format(distance))
				.append(", alive=").append(alive);
			if (isPlayer) {
				builder.append(", player=true");
			}
			if (health != null) {
				builder.append(", health=").append(format(health.doubleValue()));
			}
			if (maxHealth != null) {
				builder.append(", maxHealth=").append(format(maxHealth.doubleValue()));
			}
			builder.append(", pos=")
				.append(format(x)).append(',')
				.append(format(y)).append(',')
				.append(format(z))
				.append('}');
			return builder.toString();
		}

		private static String format(double value) {
			return String.format(Locale.ROOT, "%.1f", value);
		}
	}
}
