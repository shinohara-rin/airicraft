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
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
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
	private static final int MAX_CUTOUT_HOPS = 16;
	/**
	 * Sub-pixel sample positions inside a label cell, as fractions of the cell
	 * size. Thin cutout geometry (leaf litter, sugar cane, torches) often misses
	 * a single centre ray, so cells vote over these offsets instead.
	 */
	static final double[][] SAMPLE_OFFSETS = {
		{0.5D, 0.5D},
		{0.25D, 0.25D}, {0.75D, 0.25D},
		{0.25D, 0.75D}, {0.75D, 0.75D}
	};
	private final CutoutSampler cutoutSampler = new CutoutSampler();

	public LabelData label(
		MinecraftClient client,
		View view,
		int stridePx,
		double reach,
		double farReach,
		RegionBoundsSpec region,
		boolean includeEntities
	) {
		ClientWorld world = client.world;
		Vec eye = view.cameraPos();
		Vec3d eyeVec = new Vec3d(eye.x(), eye.y(), eye.z());

		Set<Long> renderedSections = renderedSections(client);
		Set<Long> viewVisibleBlocks = new HashSet<>();
		Map<Integer, Integer> entityHitCells = new HashMap<>();
		List<LabelCell> cells = new ArrayList<>();

		int outW = view.outputWidth();
		int outH = view.outputHeight();
		for (int cellY = 0; cellY < outH; cellY += stridePx) {
			for (int cellX = 0; cellX < outW; cellX += stridePx) {
				int w = Math.min(stridePx, outW - cellX);
				int h = Math.min(stridePx, outH - cellY);
				cells.add(labelCell(client, world, eyeVec, view, cellX, cellY, w, h, reach, farReach, renderedSections, viewVisibleBlocks, entityHitCells));
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
		double farReach,
		Set<Long> renderedSections,
		Set<Long> viewVisibleBlocks,
		Map<Integer, Integer> entityHitCells
	) {
		List<Candidate> votes = new ArrayList<>(SAMPLE_OFFSETS.length);
		for (double[] offset : SAMPLE_OFFSETS) {
			double sampleX = cellX + offset[0] * cellW;
			double sampleY = cellY + offset[1] * cellH;
			SourcePixel source = ViewGeometry.outputToSource(
				view.letterbox(), sampleX, sampleY, view.sourceWidth(), view.sourceHeight()
			);
			if (source == null) {
				votes.add(Candidate.PADDING);
				continue;
			}
			Vec dir = ViewGeometry.sourcePixelRay(
				view.basis(), view.projection(), source.x(), source.y(), view.sourceWidth(), view.sourceHeight()
			);
			votes.add(sampleRay(client, world, eyeVec, new Vec3d(dir.x(), dir.y(), dir.z()), reach, renderedSections, viewVisibleBlocks));
		}

		Candidate winner = pickWinner(votes);
		List<String> alsoPresent = votes.stream()
			.filter(candidate -> candidate != winner)
			.map(Candidate::subject)
			.filter(subject -> subject != null)
			.distinct()
			.toList();
		double support = votes.stream().filter(candidate -> candidate.key().equals(winner.key())).count()
			/ (double) SAMPLE_OFFSETS.length;

		if (winner.kind().equals("entity")) {
			Entity entity = winner.entity();
			entityHitCells.merge(entity.getId(), 1, Integer::sum);
			Vec3d hitPos = winner.hitPos();
			Egocentric ego = view.egocentric(new Vec(hitPos.x - eyeVec.x, hitPos.y - eyeVec.y, hitPos.z - eyeVec.z));
			return new LabelCell(
				cellX, cellY, cellW, cellH, "entity", winner.depth(),
				ego.forward(), ego.right(), ego.up(),
				null, null, null, null, null,
				entity.getId(), entity.getUuidAsString(),
				Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getName().getString(),
				null,
				world.getLightLevel(BlockPos.ofFloored(entity.getEyePos())),
				support, alsoPresent
			);
		}
		if (winner.kind().equals("block")) {
			BlockPos pos = winner.blockPos();
			BlockState state = world.getBlockState(pos);
			Vec3d hitPos = winner.hitPos();
			Egocentric ego = view.egocentric(new Vec(hitPos.x - eyeVec.x, hitPos.y - eyeVec.y, hitPos.z - eyeVec.z));
			return new LabelCell(
				cellX, cellY, cellW, cellH, "block", winner.depth(),
				ego.forward(), ego.right(), ego.up(),
				pos.getX(), pos.getY(), pos.getZ(),
				Registries.BLOCK.getId(state.getBlock()).toString(),
				stateKey(state),
				null, null, null, null,
				winner.cutoutChecked(),
				winner.hitLight(),
				support, alsoPresent
			);
		}
		if (winner.kind().equals("miss") && farReach > reach) {
			// Near miss: probe out to the render-distance edge. Terrain that is
			// visible but unresolvable (fog, thin silhouette) becomes "distant";
			// a below-horizon ray that still hits nothing is distant terrain
			// beyond the loaded world rather than sky.
			Vec3d dirVec = winner.direction();
			OpaqueHit far = raycastOpaque(world, client, eyeVec, dirVec, eyeVec.add(dirVec.multiply(farReach)), renderedSections);
			BlockHitResult farHit = far.hit();
			if (farHit != null && farHit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = farHit.getBlockPos();
				BlockState state = world.getBlockState(pos);
				Vec3d hitPos = farHit.getPos();
				double depth = hitPos.distanceTo(eyeVec);
				Egocentric ego = view.egocentric(new Vec(hitPos.x - eyeVec.x, hitPos.y - eyeVec.y, hitPos.z - eyeVec.z));
				return new LabelCell(
					cellX, cellY, cellW, cellH, "distant", depth,
					ego.forward(), ego.right(), ego.up(),
					pos.getX(), pos.getY(), pos.getZ(),
					Registries.BLOCK.getId(state.getBlock()).toString(),
					stateKey(state),
					null, null, null, null,
					false,
					world.getLightLevel(pos.offset(farHit.getSide())),
					support, alsoPresent
				);
			}
			String kind = dirVec.y < 0.0D ? "distant" : "sky";
			return new LabelCell(cellX, cellY, cellW, cellH, kind, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, support, alsoPresent);
		}
		String kind = winner.kind().equals("padding") ? "padding" : "sky";
		return new LabelCell(cellX, cellY, cellW, cellH, kind, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, support, alsoPresent);
	}

	/**
	 * Majority vote over the cell's sub-pixel samples; ties resolve to the
	 * nearer surface, which is what the pixels there actually show.
	 */
	private static Candidate pickWinner(List<Candidate> votes) {
		Map<String, List<Candidate>> byKey = new HashMap<>();
		for (Candidate vote : votes) {
			byKey.computeIfAbsent(vote.key(), key -> new ArrayList<>()).add(vote);
		}
		List<Candidate> best = null;
		for (List<Candidate> group : byKey.values()) {
			if (best == null
				|| group.size() > best.size()
				|| (group.size() == best.size() && minDepth(group) < minDepth(best))) {
				best = group;
			}
		}
		Candidate winner = best.get(0);
		for (Candidate candidate : best) {
			if (candidate.depth() < winner.depth()) {
				winner = candidate;
			}
		}
		return winner;
	}

	private static double minDepth(List<Candidate> group) {
		double min = Double.POSITIVE_INFINITY;
		for (Candidate candidate : group) {
			min = Math.min(min, candidate.depth());
		}
		return min;
	}

	private Candidate sampleRay(
		MinecraftClient client,
		ClientWorld world,
		Vec3d eyeVec,
		Vec3d dirVec,
		double reach,
		Set<Long> renderedSections,
		Set<Long> viewVisibleBlocks
	) {
		Vec3d rayEnd = eyeVec.add(dirVec.multiply(reach));
		OpaqueHit opaqueHit = raycastOpaque(world, client, eyeVec, dirVec, rayEnd, renderedSections);
		BlockHitResult blockHit = opaqueHit.hit();

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
			return new Candidate("entity", entityDistance, entityHit.getPos(), null, null, entity, false,
				world.getLightLevel(BlockPos.ofFloored(entity.getEyePos())), dirVec);
		}
		if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			viewVisibleBlocks.add(pos.asLong());
			// The light on the hit face lives in the air cell just outside it.
			int hitLight = world.getLightLevel(pos.offset(blockHit.getSide()));
			return new Candidate("block", blockDistance, blockHit.getPos(), pos, world.getBlockState(pos), null,
				opaqueHit.cutoutVerified(), hitLight, dirVec);
		}
		return new Candidate("miss", Double.POSITIVE_INFINITY, null, null, null, null, false, null, dirVec);
	}

	/**
	 * Render sections whose chunk mesh has data this frame. Blocks in sections
	 * with no built mesh are invisible on the captured frame, so rays must pass
	 * through them rather than label the pixels with the hidden block.
	 */
	static Set<Long> renderedSections(MinecraftClient client) {
		Set<Long> rendered = new HashSet<>();
		for (ChunkBuilder.BuiltChunk chunk : client.worldRenderer.getBuiltChunks()) {
			var data = chunk.getCurrentRenderData();
			if (data != null && data.hasData()) {
				rendered.add(chunk.getSectionPos());
			}
		}
		return rendered;
	}

	private static Vec3d exitRenderedSection(BlockPos pos, Vec3d start, Vec3d dir) {
		int sx = pos.getX() >> 4;
		int sy = pos.getY() >> 4;
		int sz = pos.getZ() >> 4;
		Box sectionBox = new Box(sx << 4, sy << 4, sz << 4, (sx << 4) + 16, (sy << 4) + 16, (sz << 4) + 16);
		double tExit = CutoutSampler.rayBoxExit(start, dir, sectionBox);
		return tExit < 0 ? null : start.add(dir.multiply(tExit));
	}

	/**
	 * Raycasts along the ray, skipping through texel-transparent geometry
	 * (crossed plants, fancy leaves, glass) whose outline shape reports a hit
	 * but whose rendered pixels let the background through, and through render
	 * sections whose mesh has not been built yet (their pixels show sky/clouds
	 * even though the block exists in world data).
	 */
	private OpaqueHit raycastOpaque(ClientWorld world, MinecraftClient client, Vec3d eyeVec, Vec3d dirVec, Vec3d rayEnd, Set<Long> renderedSections) {
		PlayerEntity player = client.player;
		Vec3d start = eyeVec;
		for (int hop = 0; hop <= MAX_CUTOUT_HOPS; hop++) {
			BlockHitResult hit = world.raycast(new RaycastContext(
				start, rayEnd, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, player
			));
			if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
				return new OpaqueHit(hit, false);
			}
			BlockPos pos = hit.getBlockPos();
			if (!renderedSections.contains(ChunkSectionPos.from(pos).asLong())) {
				Vec3d next = exitRenderedSection(pos, start, dirVec);
				if (next == null || next.squaredDistanceTo(eyeVec) >= rayEnd.squaredDistanceTo(eyeVec)) {
					return new OpaqueHit(BlockHitResult.createMissed(rayEnd, hit.getSide(), pos), false);
				}
				start = next;
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (!cutoutSampler.needsCheck(state, world, pos)
				|| !cutoutSampler.hasQuads(client, state, pos)) {
				return new OpaqueHit(hit, false);
			}
			CutoutSampler.QuadHit quadHit = cutoutSampler.sample(client, world, state, pos, start, dirVec);
			if (quadHit != null) {
				return new OpaqueHit(new BlockHitResult(quadHit.pos(), hit.getSide(), pos, hit.isInsideBlock()), true);
			}
			Vec3d next = cutoutSampler.advancePast(world, pos, state, player, start, dirVec);
			if (next == null || next.distanceTo(start) < 1.0E-6D) {
				return new OpaqueHit(hit, false);
			}
			if (next.squaredDistanceTo(eyeVec) >= rayEnd.squaredDistanceTo(eyeVec)) {
				return new OpaqueHit(BlockHitResult.createMissed(rayEnd, hit.getSide(), BlockPos.ofFloored(rayEnd)), false);
			}
			start = next;
		}
		return new OpaqueHit(BlockHitResult.createMissed(rayEnd, Direction.UP, BlockPos.ofFloored(rayEnd)), false);
	}

	private record OpaqueHit(BlockHitResult hit, boolean cutoutVerified) {
	}

	private record Candidate(
		String kind,
		double depth,
		Vec3d hitPos,
		BlockPos blockPos,
		BlockState blockState,
		Entity entity,
		boolean cutoutChecked,
		Integer hitLight,
		Vec3d direction
	) {
		static final Candidate PADDING = new Candidate("padding", Double.POSITIVE_INFINITY, null, null, null, null, false, null, null);

		String subject() {
			if (kind.equals("block") && blockState != null) {
				return Registries.BLOCK.getId(blockState.getBlock()).toString();
			}
			if (kind.equals("entity") && entity != null) {
				return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
			}
			return null;
		}

		String key() {
			String subject = subject();
			return subject == null ? kind : kind + "|" + subject;
		}
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
			Egocentric ego = view.egocentric(new Vec(entityPos.x - eyeVec.x, entityPos.y - eyeVec.y, entityPos.z - eyeVec.z));
			entities.add(new EntityLabel(
				entity.getId(),
				entity.getUuidAsString(),
				Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getName().getString(),
				entity.hasCustomName(),
				new Pos(entityPos.x, entityPos.y, entityPos.z),
				ego,
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
		Double egoForward, Double egoRight, Double egoUp,
		Integer blockX, Integer blockY, Integer blockZ,
		String blockId,
		String stateKey,
		Integer entityId, String entityUuid, String entityType, String entityName,
		Boolean cutoutChecked,
		Integer hitLight,
		Double support,
		List<String> alsoPresent
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
		Egocentric ego,
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
