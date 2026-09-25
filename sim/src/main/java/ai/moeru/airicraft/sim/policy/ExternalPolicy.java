package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/**
 * Policy whose intent is supplied externally per tick (RL / remote control).
 * Returns the last intent posted via /v1/step until a new one arrives.
 *
 * <p>When episode params carry an "ast" program, the posted intents only need
 * the continuous fields (moveDir + lookEntity/lookPos); the discrete flags
 * (attack, shield use, sprint, jump, sneak, stopUsing) are taken from the AST
 * program — the training-time counterpart of policy="hybrid" deployment.
 */
public final class ExternalPolicy implements CombatPolicy {
	private volatile Intent pending = Intent.IDLE;
	private AstPolicy flags;

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
		if (flags != null) {
			flags.reset();
		}
	}

	@Override
	public void configure(JsonObject params) {
		if (params != null && params.has("ast")) {
			flags = new AstPolicy();
			flags.configure(params);
		}
	}

	@Override
	public Intent decide(JsonObject observation) {
		Intent i = pending;
		if (flags == null) {
			return i;
		}
		Intent a = flags.decide(observation);
		Intent.Builder bld = Intent.builder()
				.lookEntity(i.lookEntityId());
		double[] lp = i.lookPos();
		if (lp != null && lp.length >= 3) {
			bld.lookPos(lp[0], lp[1], lp[2]);
		}
		double[] md = i.moveDir();
		if (md != null && md.length >= 2) {
			bld.moveDir(md[0], md[1]);
		}
		return bld
				.attack(a.attack())
				.sprint(a.sprint())
				.jump(a.jump())
				.sneak(a.sneak())
				.useHand(a.useHand())
				.stopUsing(a.stopUsing())
				.build();
	}
}
