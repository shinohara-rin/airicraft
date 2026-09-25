package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Mixture-of-experts selector: the net outputs a score per champion AST
 * program each tick; the argmax program's own decide() supplies the whole
 * intent. Lets the selector learn which evolved grammar wins in each state
 * (e.g. shield-kite vs rush) while inheriting the champions' full target
 * switching and flag logic — the piece the flat hybrid could not learn.
 *
 * <pre>{@code
 * params = {"net": {"layout":"sel","layers":[...]}, "programs": [<ast>, ...]}
 * }</pre>
 *
 * Uses NetPolicy's frame encoding/stacking directly (same INPUT layout); only
 * the output interpretation differs — first NPROG outputs are program scores.
 */
public final class SelectorPolicy implements CombatPolicy {
	private final NetPolicy net = new NetPolicy();
	private final List<AstPolicy> programs = new ArrayList<>();
	private int lastPick = -1;

	@Override
	public String id() {
		return "selector";
	}

	@Override
	public void reset() {
		net.reset();
		lastPick = -1;
		for (AstPolicy p : programs) {
			p.reset();
		}
	}

	@Override
	public void configure(JsonObject params) {
		net.configure(params);
		programs.clear();
		if (params != null && params.has("programs") && params.get("programs").isJsonArray()) {
			for (JsonElement el : params.getAsJsonArray("programs")) {
				JsonObject wrap = new JsonObject();
				wrap.add("ast", el);
				AstPolicy p = new AstPolicy();
				p.configure(wrap);
				programs.add(p);
			}
		}
	}

	@Override
	public Intent decide(JsonObject obs) {
		if (programs.isEmpty()) {
			return Intent.IDLE;
		}
		int pick = net.pickProgram(obs, programs.size());
		if (pick < 0 || pick >= programs.size()) {
			pick = 0;
		}
		lastPick = pick;
		return programs.get(pick).decide(obs);
	}
}
