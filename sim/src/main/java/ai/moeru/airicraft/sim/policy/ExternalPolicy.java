package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/**
 * Policy whose intent is supplied externally per tick (RL / remote control).
 * Returns the last intent posted via /v1/step until a new one arrives.
 */
public final class ExternalPolicy implements CombatPolicy {
	private volatile Intent pending = Intent.IDLE;

	public void set(Intent intent) {
		pending = intent == null ? Intent.IDLE : intent;
	}

	@Override
	public String id() {
		return "external";
	}

	@Override
	public void reset() {
		pending = Intent.IDLE;
	}

	@Override
	public Intent decide(JsonObject observation) {
		return pending;
	}
}
