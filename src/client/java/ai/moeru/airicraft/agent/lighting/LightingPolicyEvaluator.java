package ai.moeru.airicraft.agent.lighting;

public final class LightingPolicyEvaluator {
	private LightingPolicyEvaluator() {
	}

	public static boolean shouldPlace(
		LightingPolicy policy,
		boolean miningActive,
		boolean holdingTorchOffhand,
		boolean skyVisible,
		int combinedLightLevel,
		int blockLightLevel,
		boolean nearbyTorch
	) {
		if (policy == null || !policy.enabled() || !miningActive || !holdingTorchOffhand || nearbyTorch) {
			return false;
		}
		if (policy.requireUnderground() && skyVisible) {
			return false;
		}
		int observedLight = policy.mode() == LightingPolicy.Mode.SPAWN_PROOF
			? blockLightLevel
			: combinedLightLevel;
		return observedLight <= policy.maxLightLevel();
	}
}
