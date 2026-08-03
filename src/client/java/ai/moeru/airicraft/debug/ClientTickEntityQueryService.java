package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ClientTickEntityQueryService {
	public static final int DEFAULT_PAGE_LIMIT = 32;
	public static final int MAX_PAGE_LIMIT = 256;
	public static final double MAX_RADIUS = 4_096.0D;

	public Map<String, Object> query(
		MinecraftClient client,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		EntityQuery query
	) {
		EntityQueryResult result = capture(client, snapshot, query);
		Map<String, Object> response = ClientTickWorldQueryService.baseResponse(snapshot);
		response.put("query", result.query());
		response.put("loadedEntityCount", result.loadedEntityCount());
		response.put("totalMatchCount", result.totalMatchCount());
		response.put("cursor", result.cursor());
		response.put("resultCount", result.entities().size());
		response.put("nextCursor", result.nextCursor());
		response.put("complete", result.complete());
		response.put("entities", result.entities());
		return response;
	}

	EntityQueryResult capture(
		MinecraftClient client,
		ClientTickDebugController.ClientTickSnapshot snapshot,
		EntityQuery query
	) {
		ClientWorld world = ClientTickWorldQueryService.requireMatchingWorld(client, snapshot);
		List<EntityCandidate> candidates = new ArrayList<>();
		for (Entity entity : world.getEntities()) {
			candidates.add(EntityCandidate.from(entity, query));
		}
		EntityPage<EntityCandidate> page = selectPage(candidates, query, client.player.getId(), snapshot.player().position());
		List<EntityObservation> observations = observeSelected(
			page.entities(),
			candidate -> observe(candidate.entity(), client.player)
		);
		return new EntityQueryResult(
			query,
			candidates.size(),
			page.totalMatchCount(),
			page.cursor(),
			page.complete() ? null : page.cursor() + page.entities().size(),
			page.complete(),
			observations.stream().map(EntityObservation::payload).toList()
		);
	}

	static EntityPage<EntityObservation> select(
		List<EntityObservation> observations,
		EntityQuery query,
		int selfEntityId,
		ClientTickPlayerSnapshot.PositionSnapshot playerPosition
	) {
		return selectPage(observations, query, selfEntityId, playerPosition);
	}

	static <S extends EntitySummary, T> List<T> observeSelected(List<S> selected, Function<S, T> observer) {
		return selected.stream().map(observer).toList();
	}

	private static <T extends EntitySummary> EntityPage<T> selectPage(
		List<T> entities,
		EntityQuery query,
		int selfEntityId,
		ClientTickPlayerSnapshot.PositionSnapshot playerPosition
	) {
		if (entities == null) {
			entities = List.of();
		}
		EntityQuery validQuery = query == null ? EntityQuery.all() : query;
		double sortX = validQuery.radius() == null ? playerPosition.x() : validQuery.radius().x();
		double sortY = validQuery.radius() == null ? playerPosition.y() : validQuery.radius().y();
		double sortZ = validQuery.radius() == null ? playerPosition.z() : validQuery.radius().z();
		List<T> matches = entities.stream()
			.filter(entity -> validQuery.includeSelf() || entity.entityId() != selfEntityId)
			.filter(validQuery::matches)
			.sorted(Comparator
				.comparingDouble((T entity) -> entity.squaredDistanceTo(sortX, sortY, sortZ))
				.thenComparingInt(EntitySummary::entityId))
			.toList();
		if (validQuery.cursor() > matches.size()) {
			throw new BridgeUnavailableException("invalid_request", "cursor is outside the entity result set");
		}
		int start = Math.toIntExact(validQuery.cursor());
		int end = Math.min(matches.size(), start + validQuery.limit());
		return new EntityPage<>(validQuery.cursor(), matches.size(), List.copyOf(matches.subList(start, end)));
	}

	private static EntityObservation observe(Entity entity, Entity self) {
		String name = entity.getName() == null ? null : entity.getName().getString();
		String entityTypeId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("entityId", entity.getId());
		payload.put("uuid", entity.getUuidAsString());
		payload.put("name", name);
		payload.put("customName", entity.getCustomName() == null ? null : entity.getCustomName().getString());
		payload.put("entityTypeId", entityTypeId);
		payload.put("isPlayer", entity instanceof PlayerEntity);
		payload.put("isLiving", entity instanceof LivingEntity);
		payload.put("distanceFromPlayer", Math.sqrt(entity.squaredDistanceTo(self)));
		payload.put("position", new ClientTickPlayerSnapshot.PositionSnapshot(
			entity.getX(),
			entity.getY(),
			entity.getZ(),
			entity.getBlockX(),
			entity.getBlockY(),
			entity.getBlockZ()
		));
		float headYaw = entity instanceof LivingEntity living ? living.getHeadYaw() : entity.getYaw();
		float bodyYaw = entity instanceof LivingEntity living ? living.getBodyYaw() : entity.getYaw();
		payload.put("rotation", new ClientTickPlayerSnapshot.RotationSnapshot(
			entity.getYaw(),
			entity.getPitch(),
			headYaw,
			bodyYaw
		));
		var velocity = entity.getVelocity();
		payload.put("velocity", new ClientTickPlayerSnapshot.VectorSnapshot(velocity.x, velocity.y, velocity.z));
		var bounds = entity.getBoundingBox();
		payload.put("bounds", new ClientTickPlayerSnapshot.BoundsSnapshot(
			bounds.minX,
			bounds.minY,
			bounds.minZ,
			bounds.maxX,
			bounds.maxY,
			bounds.maxZ
		));
		payload.put("pose", entity.getPose().name().toLowerCase(Locale.ROOT));
		payload.put("alive", entity.isAlive());
		payload.put("removed", entity.isRemoved());
		payload.put("onGround", entity.isOnGround());
		payload.put("horizontalCollision", entity.horizontalCollision);
		payload.put("verticalCollision", entity.verticalCollision);
		payload.put("sprinting", entity.isSprinting());
		payload.put("sneaking", entity.isSneaking());
		payload.put("swimming", entity.isSwimming());
		payload.put("crawling", entity.isCrawling());
		payload.put("touchingWater", entity.isTouchingWater());
		payload.put("submergedInWater", entity.isSubmergedInWater());
		payload.put("inLava", entity.isInLava());
		payload.put("invisible", entity.isInvisible());
		payload.put("glowing", entity.isGlowing());
		payload.put("silent", entity.isSilent());
		payload.put("fireTicks", entity.getFireTicks());
		payload.put("frozenTicks", entity.getFrozenTicks());
		payload.put("fallDistance", entity.fallDistance);
		payload.put("age", entity.age);
		payload.put("vehicleEntityId", entity.getVehicle() == null ? null : entity.getVehicle().getId());
		payload.put("passengerEntityIds", entity.getPassengerList().stream().map(Entity::getId).sorted().toList());
		payload.put("commandTags", entity.getCommandTags().stream().sorted().toList());
		if (entity instanceof LivingEntity living) {
			payload.put("health", living.getHealth());
			payload.put("maxHealth", living.getMaxHealth());
			payload.put("absorption", living.getAbsorptionAmount());
			payload.put("armor", living.getArmor());
			payload.put("air", living.getAir());
			payload.put("maxAir", living.getMaxAir());
			payload.put("hurtTime", living.hurtTime);
			payload.put("deathTime", living.deathTime);
			payload.put("headYaw", living.getHeadYaw());
			payload.put("bodyYaw", living.getBodyYaw());
			payload.put("usingItem", living.isUsingItem());
			payload.put("itemUseTime", living.getItemUseTime());
			payload.put("equipment", ClientTickPlayerSnapshotFactory.equipment(living));
			payload.put("statusEffects", ClientTickPlayerSnapshotFactory.statusEffects(living.getStatusEffects()));
			payload.put("attributes", ClientTickPlayerSnapshotFactory.attributes(living.getAttributes().getAttributesToSend()));
		}
		if (entity instanceof ItemEntity itemEntity) {
			payload.put("item", ClientTickPlayerSnapshotFactory.itemStack(-1, itemEntity.getStack()));
		}
		if (entity instanceof ExperienceOrbEntity experienceOrb) {
			payload.put("experienceValue", experienceOrb.getValue());
		}
		if (entity instanceof MobEntity mob) {
			payload.put("aiDisabled", mob.isAiDisabled());
			payload.put("persistent", mob.isPersistent());
			payload.put("targetEntityId", mob.getTarget() == null ? null : mob.getTarget().getId());
		}
		return new EntityObservation(
			entity.getId(),
			entity.getUuidAsString(),
			name,
			entityTypeId,
			entity.getX(),
			entity.getY(),
			entity.getZ(),
			entity.isAlive(),
			entity instanceof LivingEntity,
			entity instanceof PlayerEntity,
			payload
		);
	}

	public record EntityQuery(
		ClientTickWorldQueryService.RegionBounds region,
		RadiusBounds radius,
		Integer entityId,
		String uuid,
		String name,
		Set<String> entityTypeIds,
		Boolean alive,
		boolean livingOnly,
		boolean playerOnly,
		boolean includeSelf,
		long cursor,
		int limit
	) {
		public EntityQuery {
			if (region != null && radius != null) {
				throw new BridgeUnavailableException("invalid_request", "Use either region or radius, not both");
			}
			uuid = normalizedOptional(uuid);
			name = normalizedOptional(name);
			entityTypeIds = normalizedTypes(entityTypeIds);
			if (cursor < 0L) {
				throw new BridgeUnavailableException("invalid_request", "cursor must not be negative");
			}
			if (limit < 1 || limit > MAX_PAGE_LIMIT) {
				throw new BridgeUnavailableException("invalid_request", "limit must be between 1 and " + MAX_PAGE_LIMIT);
			}
		}

		public static EntityQuery all() {
			return new EntityQuery(null, null, null, null, null, Set.of(), null, false, false, false, 0L, DEFAULT_PAGE_LIMIT);
		}

		boolean matches(EntitySummary entity) {
			if (region != null && !insideRegion(entity)) {
				return false;
			}
			if (radius != null && entity.squaredDistanceTo(radius.x(), radius.y(), radius.z()) > radius.radius() * radius.radius()) {
				return false;
			}
			if (entityId != null && entity.entityId() != entityId) {
				return false;
			}
			if (uuid != null && !uuid.equalsIgnoreCase(entity.uuid())) {
				return false;
			}
			if (name != null && !name.equalsIgnoreCase(entity.name())) {
				return false;
			}
			if (!entityTypeIds.isEmpty() && !entityTypeIds.contains(entity.entityTypeId())) {
				return false;
			}
			if (alive != null && alive != entity.alive()) {
				return false;
			}
			if (livingOnly && !entity.living()) {
				return false;
			}
			return !playerOnly || entity.player();
		}

		private boolean insideRegion(EntitySummary entity) {
			return entity.x() >= region.minX()
				&& entity.x() < (double) region.maxX() + 1.0D
				&& entity.y() >= region.minY()
				&& entity.y() < (double) region.maxY() + 1.0D
				&& entity.z() >= region.minZ()
				&& entity.z() < (double) region.maxZ() + 1.0D;
		}

		private static String normalizedOptional(String value) {
			return value == null || value.isBlank() ? null : value.trim();
		}

		private static Set<String> normalizedTypes(Set<String> values) {
			if (values == null || values.isEmpty()) {
				return Set.of();
			}
			Set<String> normalized = new LinkedHashSet<>();
			for (String value : values) {
				if (value == null || value.isBlank()) {
					throw new BridgeUnavailableException("invalid_request", "entityTypeIds cannot contain blank values");
				}
				normalized.add(value.trim().toLowerCase(Locale.ROOT));
			}
			return Collections.unmodifiableSet(normalized);
		}
	}

	public record RadiusBounds(double x, double y, double z, double radius) {
		public RadiusBounds {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new BridgeUnavailableException("invalid_request", "radius center coordinates must be finite");
			}
			if (!Double.isFinite(radius) || radius < 0.0D || radius > MAX_RADIUS) {
				throw new BridgeUnavailableException("invalid_request", "radius must be between 0 and " + MAX_RADIUS);
			}
		}
	}

	public record EntityQueryResult(
		EntityQuery query,
		int loadedEntityCount,
		int totalMatchCount,
		long cursor,
		Long nextCursor,
		boolean complete,
		List<Map<String, Object>> entities
	) {
		public EntityQueryResult {
			entities = List.copyOf(entities);
		}
	}

	interface EntitySummary {
		int entityId();

		String uuid();

		String name();

		String entityTypeId();

		double x();

		double y();

		double z();

		boolean alive();

		boolean living();

		boolean player();

		default double squaredDistanceTo(double targetX, double targetY, double targetZ) {
			double dx = x() - targetX;
			double dy = y() - targetY;
			double dz = z() - targetZ;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	private record EntityCandidate(
		Entity entity,
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		boolean alive,
		boolean living,
		boolean player
	) implements EntitySummary {
		private static EntityCandidate from(Entity entity, EntityQuery query) {
			EntityQuery validQuery = query == null ? EntityQuery.all() : query;
			return new EntityCandidate(
				entity,
				entity.getId(),
				validQuery.uuid() == null ? null : entity.getUuidAsString(),
				validQuery.name() == null ? null : entity.getName().getString(),
				validQuery.entityTypeIds().isEmpty() ? null : Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getX(),
				entity.getY(),
				entity.getZ(),
				validQuery.alive() == null || entity.isAlive(),
				validQuery.livingOnly() && entity instanceof LivingEntity,
				validQuery.playerOnly() && entity instanceof PlayerEntity
			);
		}
	}

	record EntityObservation(
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		boolean alive,
		boolean living,
		boolean player,
		Map<String, Object> payload
	) implements EntitySummary {
		EntityObservation {
			payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
		}
	}

	record EntityPage<T extends EntitySummary>(long cursor, int totalMatchCount, List<T> entities) {
		boolean complete() {
			return cursor + entities.size() >= totalMatchCount;
		}
	}
}
