package ai.moeru.airicraft.dataset;

/**
 * Camera math for dataset captures: maps pixels of the emitted 854x480 image
 * to world-space rays and back. Pure double math with no Minecraft types so it
 * can be mirrored exactly by the offline QA generator.
 */
public final class ViewGeometry {
	public static final int OUTPUT_WIDTH = 854;
	public static final int OUTPUT_HEIGHT = 480;

	private ViewGeometry() {
	}

	/** Minecraft yaw/pitch convention: yaw 0 faces south (+Z), pitch -90 faces up. */
	public static Vec forward(double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double cosPitch = Math.cos(pitch);
		return new Vec(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
	}

	/**
	 * Right/up/forward basis matching a first-person camera. Screen-space +X is
	 * {@code right}, +Y is {@code up}, and the camera looks along {@code forward}
	 * (OpenGL camera-space -Z).
	 */
	public static Basis cameraBasis(double yawDegrees, double pitchDegrees) {
		Vec forward = forward(yawDegrees, pitchDegrees);
		Vec worldUp = new Vec(0.0D, 1.0D, 0.0D);
		Vec right = forward.cross(worldUp);
		if (right.lengthSquared() < 1.0E-10D) {
			// Looking straight up or down: keep the yaw-projected horizontal axis.
			double yaw = Math.toRadians(yawDegrees);
			right = new Vec(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
		}
		right = right.normalize();
		Vec up = right.cross(forward).normalize();
		return new Basis(right, up, forward);
	}

	/**
	 * Direction from the camera through a source-image pixel. {@code srcPixelX/Y}
	 * are continuous pixel coordinates (pixel centers sit at integer + 0.5).
	 */
	public static Vec sourcePixelRay(
		Basis basis,
		Projection projection,
		double srcPixelX,
		double srcPixelY,
		int sourceWidth,
		int sourceHeight
	) {
		double ndcX = 2.0D * (srcPixelX + 0.5D) / sourceWidth - 1.0D;
		double ndcY = 1.0D - 2.0D * (srcPixelY + 0.5D) / sourceHeight;
		// Perspective projection rows: clip_x = m00*x + m02*z, clip_y = m11*y + m12*z.
		// At unit view depth (z = -1): x = (ndcX + m02) / m00, y = (ndcY + m12) / m11.
		double dirX = (ndcX + projection.m02()) / projection.m00();
		double dirY = (ndcY + projection.m12()) / projection.m11();
		return basis.right().scale(dirX)
			.add(basis.up().scale(dirY))
			.add(basis.forward())
			.normalize();
	}

	/**
	 * Project a world point into source-image pixel coordinates.
	 * Returns null when the point is behind the camera's near plane.
	 */
	public static ScreenPoint project(
		Vec cameraPos,
		Basis basis,
		Projection projection,
		Vec worldPos,
		int sourceWidth,
		int sourceHeight
	) {
		Vec rel = worldPos.subtract(cameraPos);
		double depth = rel.dot(basis.forward());
		if (depth <= 0.05D) {
			return null;
		}
		double cx = rel.dot(basis.right());
		double cy = rel.dot(basis.up());
		double ndcX = (projection.m00() * cx - projection.m02() * depth) / depth;
		double ndcY = (projection.m11() * cy - projection.m12() * depth) / depth;
		double srcPixelX = (ndcX + 1.0D) / 2.0D * sourceWidth - 0.5D;
		double srcPixelY = (1.0D - (ndcY + 1.0D) / 2.0D) * sourceHeight - 0.5D;
		return new ScreenPoint(srcPixelX, srcPixelY, depth);
	}

	/** Egocentric decomposition of a world offset into forward/right/up meters. */
	public static Egocentric egocentric(Basis basis, Vec offset) {
		return new Egocentric(
			offset.dot(basis.forward()),
			offset.dot(basis.right()),
			offset.dot(basis.up())
		);
	}

	/** Letterbox transform mirroring {@code LetterboxImageScaler.scaleToCanvas}. */
	public static Letterbox letterbox(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
		double scale = Math.min((double) targetWidth / sourceWidth, (double) targetHeight / sourceHeight);
		int scaledWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
		int scaledHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
		return new Letterbox(
			scale,
			(targetWidth - scaledWidth) / 2,
			(targetHeight - scaledHeight) / 2,
			scaledWidth,
			scaledHeight
		);
	}

	/** Map an output-image pixel to source pixels; null when inside letterbox padding. */
	public static SourcePixel outputToSource(Letterbox letterbox, double outputX, double outputY, int sourceWidth, int sourceHeight) {
		double srcX = (outputX + 0.5D - letterbox.offsetX()) / letterbox.scale();
		double srcY = (outputY + 0.5D - letterbox.offsetY()) / letterbox.scale();
		if (srcX < 0.0D || srcY < 0.0D || srcX >= sourceWidth || srcY >= sourceHeight) {
			return null;
		}
		return new SourcePixel(srcX, srcY);
	}

	public record Vec(double x, double y, double z) {
		public Vec add(Vec other) {
			return new Vec(x + other.x, y + other.y, z + other.z);
		}

		public Vec subtract(Vec other) {
			return new Vec(x - other.x, y - other.y, z - other.z);
		}

		public Vec scale(double factor) {
			return new Vec(x * factor, y * factor, z * factor);
		}

		public double dot(Vec other) {
			return x * other.x + y * other.y + z * other.z;
		}

		public Vec cross(Vec other) {
			return new Vec(
				y * other.z - z * other.y,
				z * other.x - x * other.z,
				x * other.y - y * other.x
			);
		}

		public double lengthSquared() {
			return x * x + y * y + z * z;
		}

		public double length() {
			return Math.sqrt(lengthSquared());
		}

		public Vec normalize() {
			double length = length();
			return length < 1.0E-12D ? new Vec(0.0D, 0.0D, 0.0D) : scale(1.0D / length);
		}
	}

	public record Basis(Vec right, Vec up, Vec forward) {
	}

	/** Rows m00/m11 scale x/y; m02/m12 allow off-center projections. */
	public record Projection(double m00, double m11, double m02, double m12) {
		public static Projection perspective(double fovYDegrees, double aspect) {
			double focal = 1.0D / Math.tan(Math.toRadians(fovYDegrees) / 2.0D);
			return new Projection(focal / aspect, focal, 0.0D, 0.0D);
		}

		public double fovYDegrees() {
			return Math.toDegrees(2.0D * Math.atan(1.0D / m11));
		}
	}

	public record Letterbox(double scale, int offsetX, int offsetY, int scaledWidth, int scaledHeight) {
	}

	public record SourcePixel(double x, double y) {
	}

	public record ScreenPoint(double x, double y, double depth) {
	}

	public record Egocentric(double forward, double right, double up) {
	}
}
