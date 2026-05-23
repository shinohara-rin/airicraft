package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class JourneyMapIntegrationProviderTest {
	@TempDir
	private Path tempDir;

	@Test
	void convertsWaypointFieldsToAiricraftWaypoint() {
		MapWaypoint converted = JourneyMapWaypointMapper.toMapWaypoint(
			"guid-1",
			"Home",
			"minecraft:overworld",
			1,
			64,
			2,
			0x3366ff,
			true,
			true,
			false
		);

		assertEquals("journeymap", converted.providerId());
		assertEquals("guid-1", converted.id());
		assertEquals("Home", converted.name());
		assertEquals("minecraft:overworld", converted.dimension());
		assertEquals(1, converted.x());
		assertEquals(64, converted.y());
		assertEquals(2, converted.z());
		assertEquals(0x3366ff, converted.color());
		assertEquals(true, converted.enabled());
		assertEquals(true, converted.showOnMap());
		assertEquals(false, converted.showInWorld());
	}

	@Test
	void mapsChunksToJourneyMapRegionCoordinates() {
		assertEquals(-1, JourneyMapIntegrationProvider.regionCoordinateForChunk(-1));
		assertEquals(-1, JourneyMapIntegrationProvider.regionCoordinateForChunk(-32));
		assertEquals(-2, JourneyMapIntegrationProvider.regionCoordinateForChunk(-33));
		assertEquals(0, JourneyMapIntegrationProvider.regionCoordinateForChunk(0));
		assertEquals(0, JourneyMapIntegrationProvider.regionCoordinateForChunk(31));
		assertEquals(1, JourneyMapIntegrationProvider.regionCoordinateForChunk(32));
	}

	@Test
	void stitchesCachedRegionImages() throws Exception {
		writeTile("-1,-1.png", Color.RED);
		writeTile("0,-1.png", Color.GREEN);
		writeTile("-1,0.png", Color.BLUE);
		writeTile("0,0.png", Color.WHITE);

		BufferedImage stitched = JourneyMapIntegrationProvider.stitchCachedRegionImages(tempDir, -1, -1, 1);

		assertEquals(1536, stitched.getWidth());
		assertEquals(1536, stitched.getHeight());
		assertEquals(Color.RED.getRGB(), stitched.getRGB(512, 512));
		assertEquals(Color.GREEN.getRGB(), stitched.getRGB(1024, 512));
		assertEquals(Color.BLUE.getRGB(), stitched.getRGB(512, 1024));
		assertEquals(Color.WHITE.getRGB(), stitched.getRGB(1024, 1024));
	}

	@Test
	void cachedRegionStitchFailsWhenNoImagesExist() {
		assertThrows(BridgeUnavailableException.class, () -> JourneyMapIntegrationProvider.stitchCachedRegionImages(tempDir, 0, 0, 0));
	}

	@Test
	void centersCachedMapOnPlayerBlockAcrossRegionEdges() throws Exception {
		writeTile("-1,-1.png", Color.RED);
		writeTile("0,-1.png", Color.GREEN);
		writeTile("-1,0.png", Color.BLUE);
		writeTile("0,0.png", Color.WHITE);

		BufferedImage centered = JourneyMapIntegrationProvider.composeCenteredMapImage(tempDir, -1, -1, 5, false, 0.0F);

		assertEquals(5, centered.getWidth());
		assertEquals(5, centered.getHeight());
		assertEquals(Color.RED.getRGB(), centered.getRGB(2, 2));
		assertEquals(Color.GREEN.getRGB(), centered.getRGB(3, 2));
		assertEquals(Color.BLUE.getRGB(), centered.getRGB(2, 3));
		assertEquals(Color.WHITE.getRGB(), centered.getRGB(3, 3));
	}

	@Test
	void cropsTransparentBordersAroundAvailableMapContent() throws Exception {
		writeTile("0,0.png", Color.MAGENTA);

		BufferedImage centered = JourneyMapIntegrationProvider.composeCenteredMapImage(tempDir, 0, 0, 1025, false, 0.0F);

		assertEquals(512, centered.getWidth());
		assertEquals(512, centered.getHeight());
		assertEquals(Color.MAGENTA.getRGB(), centered.getRGB(0, 0));
		assertEquals(Color.MAGENTA.getRGB(), centered.getRGB(511, 511));
	}

	@Test
	void centersLargeCachedMapWithoutPerPixelTileLookups() throws Exception {
		writeTile("0,0.png", Color.MAGENTA);

		BufferedImage centered = assertTimeoutPreemptively(Duration.ofMillis(500), () ->
			JourneyMapIntegrationProvider.composeCenteredMapImage(tempDir, 0, 0, 6144, false, 0.0F)
		);

		assertEquals(512, centered.getWidth());
		assertEquals(512, centered.getHeight());
		assertEquals(Color.MAGENTA.getRGB(), centered.getRGB(0, 0));
	}

	@Test
	void drawsPlayerMarkerAtCenteredMapPosition() throws Exception {
		writeTile("-1,-1.png", Color.DARK_GRAY);

		BufferedImage centered = JourneyMapIntegrationProvider.composeCenteredMapImage(tempDir, -104, -54, 65, true, 0.0F);

		assertEquals(65, centered.getWidth());
		assertEquals(65, centered.getHeight());
		assertEquals(0xffff2d2d, centered.getRGB(32, 32));
	}

	@Test
	void drawsVisibleWaypointsAtWorldCoordinates() throws Exception {
		writeTile("0,0.png", Color.WHITE);
		MapWaypoint waypoint = new MapWaypoint(
			"journeymap",
			"guid-1",
			"Home",
			"minecraft:overworld",
			300,
			64,
			300,
			0x3366ff,
			true,
			true,
			true
		);

		BufferedImage centered = JourneyMapIntegrationProvider.composeCenteredMapImage(
			tempDir,
			256,
			256,
			512,
			null,
			null,
			null,
			List.of(waypoint)
		);

		assertEquals(0xff3366ff, centered.getRGB(300, 300));
	}

	private void writeTile(String name, Color color) throws Exception {
		BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
		for (int z = 0; z < image.getHeight(); z++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, z, color.getRGB());
			}
		}
		ImageIO.write(image, "png", tempDir.resolve(name).toFile());
	}
}
