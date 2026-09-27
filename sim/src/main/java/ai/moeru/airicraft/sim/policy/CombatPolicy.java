package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/**
 * A melee combat policy. Receives a privileged-state observation (possibly stale
 * by the configured obs delay) and returns one tick of semantic intent. The
 * optimizer may only change implementations of this interface.
 */
public interface CombatPolicy {
	String id();

	/** Called once when the episode resets. */
	void reset();

	/** Apply optimizer-supplied tunables before the episode starts (default: none). */
	default void configure(JsonObject params) {}

	Intent decide(JsonObject observation);

	/**
	 * The sampled action produced by the last {@link #decide} call when the
	 * policy runs in sampling mode ({@code params.sample:true}), or null. Used
	 * by the episode recorder to log the exact action that entered the world —
	 * the RL trainer replays obs + this record to recompute logprobs.
	 */
	default JsonObject sampledAction() { return null; }
}
