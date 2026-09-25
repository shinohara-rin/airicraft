package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/**
 * Neuro-symbolic hybrid: the net handles continuous decisions (move direction +
 * target via lookEntity) while a champion AST program supplies the discrete
 * flags (attack timing, shield use, sprint, jump). Rationale: the rule grammar's
 * strength is exactly these condition-driven flags, while the net's weakness in
 * pure form has been its flag heads; the AST's own target selection is bypassed
 * so the net learns where to point.
 *
 * <pre>{@code
 * params = {"net": <NetPolicy spec>, "ast": <AstPolicy program>}
 * </pre>
 */
public final class HybridPolicy implements CombatPolicy {
	private final NetPolicy net = new NetPolicy();
	private final AstPolicy ast = new AstPolicy();

	@Override
	public String id() {
		return "hybrid";
	}

	@Override
	public void reset() {
		net.reset();
		ast.reset();
	}

	@Override
	public void configure(JsonObject params) {
		net.configure(params);
		ast.configure(params);
	}

	@Override
	public Intent decide(JsonObject obs) {
		Intent n = net.decide(obs);
		Intent a = ast.decide(obs);
		Intent.Builder bld = Intent.builder()
				.lookEntity(n.lookEntityId());
		double[] lp = n.lookPos();
		if (lp != null && lp.length >= 3) {
			bld.lookPos(lp[0], lp[1], lp[2]);
		}
		double[] md = n.moveDir();
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
