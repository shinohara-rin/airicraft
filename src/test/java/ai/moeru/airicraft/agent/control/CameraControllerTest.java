package ai.moeru.airicraft.agent.control;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraControllerTest {
	@Test
	void lookRotationComputesYawAndClampsPitch() {
		CameraController.Rotation east = CameraController.lookRotation(Vec3d.ZERO, new Vec3d(1.0D, 0.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, east.yaw(), 0.001F);
		assertEquals(0.0F, east.pitch(), 0.001F);

		CameraController.Rotation vertical = CameraController.lookRotation(Vec3d.ZERO, new Vec3d(0.0D, 10.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, vertical.pitch(), 0.001F);
		assertTrue(CameraController.lookRotation(Vec3d.ZERO, Vec3d.ZERO).isEmpty());
	}

	@Test
	void applyRotationUpdatesAllPlayerRotationFields() {
		MutableRotationTarget target = new MutableRotationTarget();

		CameraController.applyRotation(target, new CameraController.Rotation(45.0F, 120.0F));

		assertEquals(45.0F, target.anglesYaw, 0.001F);
		assertEquals(90.0F, target.anglesPitch, 0.001F);
		assertEquals(45.0F, target.yaw, 0.001F);
		assertEquals(90.0F, target.pitch, 0.001F);
		assertEquals(45.0F, target.headYaw, 0.001F);
		assertEquals(45.0F, target.bodyYaw, 0.001F);
		assertEquals(45.0F, target.lastYaw, 0.001F);
		assertEquals(90.0F, target.lastPitch, 0.001F);
		assertEquals(45.0F, target.renderYaw, 0.001F);
		assertEquals(45.0F, target.lastRenderYaw, 0.001F);
		assertEquals(90.0F, target.renderPitch, 0.001F);
		assertEquals(90.0F, target.lastRenderPitch, 0.001F);
	}

	@Test
	void lerpAdvancesAndFinishesAtTargetRotation() {
		CameraController controller = new CameraController();
		controller.startMotion(
			new CameraController.Rotation(170.0F, 0.0F),
			new CameraController.Rotation(-170.0F, 40.0F),
			4,
			"test"
		);

		CameraController.Rotation first = controller.tickMotion().orElseThrow();
		assertEquals(175.0F, first.yaw(), 0.001F);
		assertEquals(10.0F, first.pitch(), 0.001F);
		assertTrue(controller.activeReason().isPresent());

		controller.tickMotion();
		controller.tickMotion();
		CameraController.Rotation finalRotation = controller.tickMotion().orElseThrow();
		assertEquals(-170.0F, finalRotation.yaw(), 0.001F);
		assertEquals(40.0F, finalRotation.pitch(), 0.001F);
		assertTrue(controller.activeReason().isEmpty());
	}

	@Test
	void clearCancelsPendingMotion() {
		CameraController controller = new CameraController();
		controller.startMotion(
			new CameraController.Rotation(0.0F, 0.0F),
			new CameraController.Rotation(90.0F, 30.0F),
			3,
			"test"
		);

		controller.clear();

		assertTrue(controller.activeReason().isEmpty());
		assertEquals(Optional.empty(), controller.tickMotion());
	}

	private static final class MutableRotationTarget implements CameraController.MutableRotation {
		private float anglesYaw;
		private float anglesPitch;
		private float yaw;
		private float pitch;
		private float headYaw;
		private float bodyYaw;
		private float lastYaw;
		private float lastPitch;
		private float renderYaw;
		private float lastRenderYaw;
		private float renderPitch;
		private float lastRenderPitch;

		@Override
		public void setAngles(float yaw, float pitch) {
			this.anglesYaw = yaw;
			this.anglesPitch = pitch;
		}

		@Override
		public void setYaw(float yaw) {
			this.yaw = yaw;
		}

		@Override
		public void setPitch(float pitch) {
			this.pitch = pitch;
		}

		@Override
		public void setHeadYaw(float yaw) {
			this.headYaw = yaw;
		}

		@Override
		public void setBodyYaw(float yaw) {
			this.bodyYaw = yaw;
		}

		@Override
		public void setLastYaw(float yaw) {
			this.lastYaw = yaw;
		}

		@Override
		public void setLastPitch(float pitch) {
			this.lastPitch = pitch;
		}

		@Override
		public void setRenderYaw(float yaw) {
			this.renderYaw = yaw;
		}

		@Override
		public void setLastRenderYaw(float yaw) {
			this.lastRenderYaw = yaw;
		}

		@Override
		public void setRenderPitch(float pitch) {
			this.renderPitch = pitch;
		}

		@Override
		public void setLastRenderPitch(float pitch) {
			this.lastRenderPitch = pitch;
		}
	}
}
