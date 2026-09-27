package ai.moeru.airicraft.dataset;

import ai.moeru.airicraft.dataset.ViewGeometry.Basis;
import ai.moeru.airicraft.dataset.ViewGeometry.Egocentric;
import ai.moeru.airicraft.dataset.ViewGeometry.Letterbox;
import ai.moeru.airicraft.dataset.ViewGeometry.Projection;
import ai.moeru.airicraft.dataset.ViewGeometry.ScreenPoint;
import ai.moeru.airicraft.dataset.ViewGeometry.SourcePixel;
import ai.moeru.airicraft.dataset.ViewGeometry.Vec;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs inside the world render pass so labels describe exactly the frame that
 * was captured: a raycast grid over the emitted image, the surrounding voxel
 * region with a line-of-sight mask, and the entities inside it.
 */
public final class DatasetViewLabeler {
	public LabelData label(
		MinecraftClient client,
		View view,
		int stridePx,
		double reach,
		RegionBoundsSpec region,
		boolean includeEntities
	) {
		ClientWorld world = client.world;
		Vec eye = view.cameraPos();
		Vec3d eyeVec = new Vec3d(eye.x(), eye.y(), eye.z());

		Set<Long> viewVisibleBlocks = new HashSet<>();
		Map<Integer, Integer> entityHitCells = new HashMap<>();
		List<LabelCell> cells = new ArrayList<>();

		int outW = view.outputWidth();
		int outH = view.outputHeight();
		for (int cellY = 0; cellY < outH; cellY += stridePx) {
			for (int cellX = 0; cellX < outW; cellX += stridePx) {
				int w = Math.min(stridePx, outW - cellX);
				int h = Math.min(stridePx, outH - cellY);
				cells.add(labelCell(client, world, eyeVec, view, cellX, cellY, w, h, reach, viewVisibleBlocks, entityHitCells));
			}
		}

		List<RegionCell> regionCells = region == null
			? List.of()
			: scanRegion(world, regionBounds(region, eye), viewVisibleBlocks);

		List<EntityLabel> entities = includeEntities
			? scanEntities(world, client.player, view, reach, entityHitCells)
			: List.of();

		return new LabelData(
			cells,
			(outW + stridePx - 1) / stridePx,
			(outH + stridePx - 1) / stridePx,
			regionCells,
			entities
		);
	}

	private LabelCell labelCell(
		MinecraftClient client,
		ClientWorld world,
		Vec3d eyeVec,
		View view,
		int cellX,
		int cellY,
		int cellW,
		int cellH,
		double reach,
		Set<Long> viewVisibleBlocks,
		Map<Integer, Integer> entityHitCells
	) {
		double centerX = cellX + cellW / 2.0D;
		double centerY = cellY + cellH / 2.0D;
		SourcePixel source = ViewGeometry.outputToSource(
			view.letterbox(), centerX, centerY, view.sourceWidth(), view.sourceHeight()
		);
		if (source == null) {
			return new LabelCell(cellX, cellY, cellW, cellH, "padding", null, null, null, null, null, null, null, null, null, null);
		}

		Vec dir = ViewGeometry.sourcePixelRay(
			view.basis(), view.projection(), source.x(), source.y(), view.sourceWidth(), view.sourceHeight()
		);
		Vec3d dirVec = new Vec3d(dir.x(), dir.y(), dir.z());
		Vec3d rayEnd = eyeVec.add(dirVec.multiply(reach));

		BlockHitResult blockHit = world.raycast(new RaycastContext(
			eyeVec, rayEnd, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, client.player
		));

		Box entitySearchBox = client.player.getBoundingBox().stretch(dirVec.multiply(reach)).expand(1.0D);
		EntityHitResult entityHit = ProjectileUtil.raycast(
			client.player, eyeVec, rayEnd, entitySearchBox,
			entity -> entity != client.player && !entity.isSpectator() && entity.canHit(),
			reach * reach
		);

		double blockDistance = blockHit != null && blockHit.getType() == HitResult.Type.BLOCK
			? blockHit.getPos().distanceTo(eyeVec)
			: Double.POSITIVE_INFINITY;
		Entity entity = entityHit == null ? null : entityHit.getEntity();
		double entityDistance = entityHit == null ? Double.POSITIVE_INFINITY : entityHit.getPos().distanceTo(eyeVec);

		if (entity != null && entityDistance < blockDistance) {
			entityHitCells.merge(entity.getId(), 1, Integer::sum);
			return new LabelCell(
				cellX, cellY, cellW, cellH, "entity", entityDistance,
				null, null, null, null, null,
				entity.getId(), entity.getUuidAsString(),
				Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getName().getString()
			);
		}
		if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			viewVisibleBlocks.add(pos.asLong());
			BlockState state = world.getBlockState(pos);
			return new LabelCell(
				cellX, cellY, cellW, cellH, "block", blockDistance,
				pos.getX(), pos.getY(), pos.getZ(),
				Registries.BLOCK.getId(state.getBlock()).toString(),
				stateKey(state),
				null, null, null, null
			);
		}
		return new LabelCell(cellX, cellY, cellW, cellH, "sky", null, null, null, null, null, null, null, null, null, null);
	}

	private List<RegionCell> scanRegion(ClientWorld world, BlockBounds bounds, Set<Long> viewVisibleBlocks) {
		List<RegionCell> cells = new ArrayList<>(bounds.cellCount());
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
			for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
				for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
					pos.set(x, y, z);
					boolean loaded = world.isChunkLoaded(pos);
					if (!loaded) {
						cells.add(new RegionCell(x, y, z, false, null, null, false, 0, false, false));
						continue;
					}
					BlockState state = world.getBlockState(pos);
					cells.add(new RegionCell(
						x, y, z, true,
						Registries.BLOCK.getId(state.getBlock()).toString(),
						stateKey(state),
						state.isAir(),
						world.getLightLevel(pos),
						world.isSkyVisible(pos),
						viewVisibleBlocks.contains(pos.asLong())
					));
				}
			}
		}
		return cells;
	}

	private List<EntityLabel> scanEntities(
		ClientWorld world,
		Entity viewer,
		View view,
		double reach,
		Map<Integer, Integer> entityHitCells
	) {
		Vec eye = view.cameraPos();
		Vec3d eyeVec = new Vec3d(eye.x(), eye.y(), eye.z());
		List<EntityLabel> entities = new ArrayList<>();
		for (Entity entity : world.getEntities()) {
			if (entity == viewer || entity.isSpectator() || entity.isRemoved()) {
				continue;
			}
			Vec3d entityPos = entity.getPos();
			double distance = entityPos.distanceTo(eyeVec);
			if (distance > reach) {
				continue;
			}
			Box box = entity.getBoundingBox();
			Vec center = new Vec(
				(box.minX + box.maxX) / 2.0D,
				(box.minY + box.maxY) / 2.0D,
				(box.minZ + box.maxZ) / 2.0D
			);
			ScreenPoint screen = ViewGeometry.project(
				eye, view.basis(), view.projection(), center, view.sourceWidth(), view.sourceHeight()
			);
			boolean onScreen = screen != null
				&& screen.x() >= 0.0D && screen.x() < view.sourceWidth()
				&& screen.y() >= 0.0D && screen.y() < view.sourceHeight();
			entities.add(new EntityLabel(
				entity.getId(),
				entity.getUuidAsString(),
				Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getName().getString(),
				entity.hasCustomName(),
				new Pos(entityPos.x, entityPos.y, entityPos.z),
				new BoxLabel(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
				distance,
				screen == null ? null : new ScreenLabel(screen.x(), screen.y(), screen.depth(), onScreen),
				entityHitCells.getOrDefault(entity.getId(), 0),
				entity.isOnGround(),
				entity.getAir()
			));
		}
		entities.sort(Comparator.comparingDouble(EntityLabel::distance));
		return entities;
	}

	static BlockBounds regionBounds(RegionBoundsSpec spec, Vec eye) {
		int ex = (int) Math.floor(eye.x());
		int ey = (int) Math.floor(eye.y());
		int ez = (int) Math.floor(eye.z());
		return new BlockBounds(
			ex - spec.radius(), ey - spec.below(), ez - spec.radius(),
			ex + spec.radius(), ey + spec.above(), ez + spec.radius()
		);
	}

	static String stateKey(BlockState state) {
		String id = Registries.BLOCK.getId(state.getBlock()).toString();
		if (state.getProperties().isEmpty()) {
			return id;
		}
		StringBuilder builder = new StringBuilder(id).append('[');
		boolean first = true;
		for (Property<?> property : state.getProperties()) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			builder.append(property.getName()).append('=').append(propertyValue(state, property));
		}
		return builder.append(']').toString();
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	/** Camera view description captured on the render thread for one frame. */
	public record View(
		Vec cameraPos,
		Basis basis,
		Projection projection,
		Letterbox letterbox,
		int sourceWidth,
		int sourceHeight,
		int outputWidth,
		int outputHeight,
		float effectiveFov
	) {
		public Egocentric egocentric(Vec offset) {
			return ViewGeometry.egocentric(basis, offset);
		}
	}

	/** Horizontal radius plus separate vertical bounds around the camera block. */
	public record RegionBoundsSpec(int radius, int below, int above) {
	}

	public record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public int cellCount() {
			return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		}
	}

	public record LabelCell(
		int x, int y, int w, int h,
		String kind,
		Double depth,
		Integer blockX, Integer blockY, Integer blockZ,
		String blockId,
		String stateKey,
		Integer entityId, String entityUuid, String entityType, String entityName
	) {
	}

	public record RegionCell(
		int x, int y, int z,
		boolean loaded,
		String id,
		String stateKey,
		boolean air,
		int light,
		boolean skyVisible,
		boolean viewVisible
	) {
	}

	public record Pos(double x, double y, double z) {
	}

	public record BoxLabel(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	public record ScreenLabel(double u, double v, double depth, boolean onScreen) {
	}

	public record EntityLabel(
		int id,
		String uuid,
		String type,
		String name,
		boolean customNamed,
		Pos pos,
		BoxLabel box,
		double distance,
		ScreenLabel screen,
		int hitCells,
		boolean onGround,
		int airTicks
	) {
	}

	public record LabelData(
		List<LabelCell> cells,
		int cellCols,
		int cellRows,
		List<RegionCell> region,
		List<EntityLabel> entities
	) {
	}
}
