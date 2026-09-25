package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Feed-forward neural policy for continuous-structure search. The optimizer
 * ships MLP weights via `params` in POST /v1/episode; this class encodes the
 * privileged observation into a fixed feature vector, runs the forward pass,
 * and maps outputs to one tick of semantic intent.
 *
 * <pre>{@code
 * params = {"net": {"layout": "v2", "layers": [
 *   {"w": [out][in] flattened row-major, "b": [out]}, ...
 * ]}}
 * </pre>
 *
 * Input layout v2: STACK frames of (GLOBAL (G) + K nearest hostile slots
 * (F each)) — a short history gives the feed-forward net temporal context
 * (cooldown rhythm, approach vectors) the rule champions get for free from
 * their rule ordering. Missing history pads by repeating the oldest frame.
 * Output heads: move direction (atan2 of 2 outputs), target slot scores (K),
 * attack, shield, sprint, jump — each a sigmoid/logit.
 * tanh on hidden layers; the executor still enforces all legality limits.
 */
public final class NetPolicy implements CombatPolicy {
	// ---- input layout ----------------------------------------------------
	static final int K = 6;            // hostile entity slots, sorted by dist
	static final int F = 14;           // per-slot features
	static final int G = 15;           // global features
	static final int FRAME = G + K * F; // one encoded frame
	static final int STACK = 20;       // frame history fed to the net (~1s)
	static final int INPUT = FRAME * STACK;
	static final int OUTPUT = 2 + K + 4; // moveXY(2) + target scores(K) + atk/shield/sprint/jump(4)

	private List<double[][]> w;   // per layer: out x in
	private List<double[]> b;     // per layer: out
	private boolean ready;
	private final Deque<double[]> frames = new ArrayDeque<>(STACK);

	// "ptr" layout: trunk -> ctx; per-slot head scores each hostile's 14 feats
	// (pointer-style target selection, the neural analog of the AST target
	// grammar); separate move/flag heads over ctx.
	private List<double[][]> ptrW;
	private List<double[]> ptrB;
	private List<double[][]> headW;   // [move 48->2, flags 48->4]
	private List<double[]> headB;

	private static boolean isRanged(String type) {
		return type.contains("skeleton") || type.contains("stray")
				|| type.contains("pillager") || type.contains("witch")
				|| type.contains("blaze") || type.contains("breeze");
	}

	@Override
	public String id() {
		return "net";
	}

	@Override
	public void reset() {
		frames.clear();
	}

	@Override
	public void configure(JsonObject params) {
		JsonElement net = params.get("net");
		if (net == null || !net.isJsonObject()) {
			return;
		}
		JsonObject spec = net.getAsJsonObject();
		JsonArray layers = spec.getAsJsonArray("layers");
		if (layers == null) {
			return;
		}
		w = new ArrayList<>();
		b = new ArrayList<>();
		for (JsonElement le : layers) {
			JsonObject l = le.getAsJsonObject();
			w.add(readW(l));
			b.add(readB(l));
		}
		ready = !w.isEmpty();
		if (spec.has("ptr")) {
			ptrW = new ArrayList<>();
			ptrB = new ArrayList<>();
			for (JsonElement le : spec.getAsJsonArray("ptr")) {
				JsonObject l = le.getAsJsonObject();
				ptrW.add(readW(l));
				ptrB.add(readB(l));
			}
		}
		if (spec.has("heads")) {
			headW = new ArrayList<>();
			headB = new ArrayList<>();
			for (JsonElement le : spec.getAsJsonArray("heads")) {
				JsonObject l = le.getAsJsonObject();
				headW.add(readW(l));
				headB.add(readB(l));
			}
		}
	}

	private static double[][] readW(JsonObject l) {
		JsonArray wArr = l.getAsJsonArray("w");
		JsonArray shape = l.getAsJsonArray("shape"); // [in, out]
		int in = shape.get(0).getAsInt();
		int out = shape.get(1).getAsInt();
		double[][] wm = new double[out][in];
		for (int o = 0; o < out; o++) {
			JsonArray row = wArr.get(o).getAsJsonArray();
			for (int i = 0; i < in; i++) {
				wm[o][i] = row.get(i).getAsDouble();
			}
		}
		return wm;
	}

	private static double[] readB(JsonObject l) {
		JsonArray bArr = l.getAsJsonArray("b");
		JsonArray shape = l.getAsJsonArray("shape");
		double[] bv = new double[shape.get(1).getAsInt()];
		for (int o = 0; o < bv.length; o++) {
			bv[o] = bArr.get(o).getAsDouble();
		}
		return bv;
	}

	private static double[] applyNet(List<double[][]> lw, List<double[]> lb,
			double[] x, boolean tanhLast) {
		double[] a = x;
		for (int li = 0; li < lw.size(); li++) {
			double[][] wm = lw.get(li);
			double[] bv = lb.get(li);
			double[] o = new double[wm.length];
			for (int r = 0; r < wm.length; r++) {
				double s = bv[r];
				double[] row = wm[r];
				for (int c = 0; c < row.length; c++) {
					s += row[c] * a[c];
				}
				o[r] = s;
			}
			if (tanhLast || li < lw.size() - 1) {
				for (int r = 0; r < o.length; r++) {
					o[r] = Math.tanh(o[r]);
				}
			}
			a = o;
		}
		return a;
	}

	// ----------------------------------------------------------- encoding --

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Encode obs into one fixed feature frame (99 dims). */
	static double[] encode(JsonObject obs) {
		double[] x = new double[FRAME];
		JsonObject player = obs.getAsJsonObject("player");
		JsonArray entities = obs.getAsJsonArray("entities");

		List<JsonObject> hostiles = new ArrayList<>();
		double hpSum = 0, creeperFuse = 0, litCreepers = 0, aiming = 0, targeting = 0;
		double nearestDist = 99;
		double px = player.getAsJsonObject("pos").get("x").getAsDouble();
		double pz = player.getAsJsonObject("pos").get("z").getAsDouble();
		for (JsonElement el : entities) {
			JsonObject e = el.getAsJsonObject();
			boolean hostile = e.has("hostile") && e.get("hostile").getAsBoolean();
			boolean isTargeting = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean();
			if (!e.has("health") || (!hostile && !isTargeting)) {
				continue;
			}
			hostiles.add(e);
			double d = e.get("dist").getAsDouble();
			hpSum += e.get("health").getAsDouble();
			if (d < nearestDist) nearestDist = d;
			if (e.has("fuse")) {
				double f = e.get("fuse").getAsDouble();
				if (f > creeperFuse) creeperFuse = f;
				if (f > 0.4) litCreepers++;
			}
			if (e.has("aiming") && e.get("aiming").getAsBoolean()) aiming++;
			if (isTargeting) targeting++;
		}
		hostiles.sort(Comparator.comparingDouble(e -> e.get("dist").getAsDouble()));

		// globals
		x[0] = clamp(player.get("health").getAsDouble() / 20.0, 0, 1);
		x[1] = clamp(player.get("attackCooldown").getAsDouble(), 0, 1);
		x[2] = clamp(player.get("lastAttackedTicks").getAsInt() / 60.0, 0, 1);
		x[3] = player.has("usingItem") && player.get("usingItem").getAsBoolean() ? 1 : 0;
		x[4] = clamp(player.has("useTicks") ? player.get("useTicks").getAsDouble() / 40.0 : 0, 0, 1);
		x[5] = player.has("offhandPct") ? clamp(player.get("offhandPct").getAsDouble(), 0, 1) : 1;
		x[6] = player.has("onGround") && player.get("onGround").getAsBoolean() ? 1 : 0;
		x[7] = player.has("food") ? clamp(player.get("food").getAsInt() / 20.0, 0, 1) : 1;
		x[8] = clamp(hostiles.size() / 9.0, 0, 1);
		x[9] = clamp(nearestDist / 20.0, 0, 1);
		x[10] = clamp(hpSum / 200.0, 0, 1);
		x[11] = clamp(creeperFuse, 0, 1);
		x[12] = clamp(litCreepers / 3.0, 0, 1);
		x[13] = clamp(aiming / 4.0, 0, 1);
		x[14] = clamp(targeting / 9.0, 0, 1);

		// K nearest hostile slots
		for (int k = 0; k < K && k < hostiles.size(); k++) {
			JsonObject e = hostiles.get(k);
			int o = G + k * F;
			JsonObject pos = e.getAsJsonObject("pos");
			x[o] = clamp(e.get("dist").getAsDouble() / 20.0, 0, 1);
			x[o + 1] = clamp((pos.get("x").getAsDouble() - px) / 20.0, -1, 1);
			x[o + 2] = clamp((pos.get("z").getAsDouble() - pz) / 20.0, -1, 1);
			x[o + 3] = clamp(pos.get("y").getAsDouble()
					- player.getAsJsonObject("pos").get("y").getAsDouble(), -1, 1) / 4.0;
			x[o + 4] = clamp(e.get("health").getAsDouble() / 30.0, 0, 1);
			x[o + 5] = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean() ? 1 : 0;
			x[o + 6] = isRanged(e.get("type").getAsString()) ? 1 : 0;
			x[o + 7] = e.has("fuse") ? clamp(e.get("fuse").getAsDouble(), 0, 1) : 0;
			x[o + 8] = e.has("aiming") && e.get("aiming").getAsBoolean() ? 1 : 0;
			x[o + 9] = e.has("playerHits") ? clamp(e.get("playerHits").getAsDouble() / 6.0, 0, 1) : 0;
			x[o + 10] = e.get("type").getAsString().contains("creeper") ? 1 : 0;
			x[o + 11] = e.get("type").getAsString().contains("wither") ? 1 : 0;
			x[o + 12] = e.get("type").getAsString().contains("blaze") ? 1 : 0;
			x[o + 13] = clamp(e.has("speed") ? e.get("speed").getAsDouble() / 5.0 : 0, 0, 1);
		}
		return x;
	}

	// ----------------------------------------------------------- forward --

	private double[] forward(double[] x) {
		double[] a = x;
		for (int l = 0; l < w.size(); l++) {
			double[][] wm = w.get(l);
			double[] bv = b.get(l);
			double[] out = new double[wm.length];
			boolean last = l == w.size() - 1;
			for (int o = 0; o < wm.length; o++) {
				double s = bv[o];
				double[] row = wm[o];
				for (int i = 0; i < row.length && i < a.length; i++) {
					s += row[i] * a[i];
				}
				out[o] = last ? s : Math.tanh(s);
			}
			a = out;
		}
		return a;
	}

	// ------------------------------------------------------------- decide --

	/** Push obs onto the frame stack; returns the stacked input. null if unconfigured. */
	double[] stackInput(JsonObject obs) {
		if (!ready) {
			return null;
		}
		frames.addLast(encode(obs));
		while (frames.size() > STACK) {
			frames.pollFirst();
		}
		double[] x = new double[INPUT];
		int fi = STACK - frames.size();
		for (double[] f : frames) {
			System.arraycopy(f, 0, x, fi * FRAME, FRAME);
			fi++;
		}
		// missing history at the front pads with the oldest frame
		for (int i = 0; i < STACK - frames.size(); i++) {
			System.arraycopy(frames.peekFirst(), 0, x, i * FRAME, FRAME);
		}
		return x;
	}

	/** SelectorPolicy: argmax over the first nPrograms outputs. -1 if unconfigured. */
	int pickProgram(JsonObject obs, int nPrograms) {
		double[] x = stackInput(obs);
		if (x == null) {
			return -1;
		}
		double[] y = forward(x);
		int best = 0;
		for (int i = 1; i < nPrograms && i < y.length; i++) {
			if (y[i] > y[best]) {
				best = i;
			}
		}
		return best;
	}

	@Override
	public Intent decide(JsonObject obs) {
		double[] x = stackInput(obs);
		if (x == null) {
			return Intent.IDLE;
		}
		double[] mv;
		double[] flagOut;
		double[] tgtScores = new double[K];
		if (ptrW != null && headW != null && headW.size() == 2) {
			// pointer layout: tanh(trunk) ctx, per-slot scores, separate heads
			double[] ctx = applyNet(w, b, x, true);
			// the latest frame is obs's own encoding: slot feats at [G + kF, G + kF + F)
			double[] frame = frames.peekLast();
			for (int k = 0; k < K; k++) {
				double[] in = new double[ctx.length + F];
				System.arraycopy(ctx, 0, in, 0, ctx.length);
				System.arraycopy(frame, G + k * F, in, ctx.length, F);
				tgtScores[k] = applyNet(ptrW, ptrB, in, false)[0];
			}
			List<double[][]> mw = new ArrayList<>(); mw.add(headW.get(0));
			List<double[]> mb = new ArrayList<>(); mb.add(headB.get(0));
			mv = applyNet(mw, mb, ctx, false);
			List<double[][]> fw = new ArrayList<>(); fw.add(headW.get(1));
			List<double[]> fb = new ArrayList<>(); fb.add(headB.get(1));
			flagOut = applyNet(fw, fb, ctx, false);
		} else {
			double[] y = forward(x);
			mv = new double[]{y[0], y[1]};
			System.arraycopy(y, 2, tgtScores, 0, K);
			flagOut = new double[]{y[2 + K], y[2 + K + 1], y[2 + K + 2], y[2 + K + 3]};
		}

		JsonObject player = obs.getAsJsonObject("player");
		JsonArray entities = obs.getAsJsonArray("entities");
		List<JsonObject> hostiles = new ArrayList<>();
		for (JsonElement el : entities) {
			JsonObject e = el.getAsJsonObject();
			boolean hostile = e.has("hostile") && e.get("hostile").getAsBoolean();
			boolean isTargeting = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean();
			if (!e.has("health") || (!hostile && !isTargeting)) {
				continue;
			}
			hostiles.add(e);
		}
		if (hostiles.isEmpty()) {
			return Intent.IDLE;
		}
		hostiles.sort(Comparator.comparingDouble(e -> e.get("dist").getAsDouble()));

		// target: argmax over slot scores (falls back to nearest beyond K)
		int ti = 0;
		double best = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < K && k < hostiles.size(); k++) {
			if (tgtScores[k] > best) {
				best = tgtScores[k];
				ti = k;
			}
		}
		JsonObject target = hostiles.get(Math.min(ti, hostiles.size() - 1));

		Intent.Builder bld = Intent.builder()
				.lookEntity(target.get("id").getAsInt())
				.moveDir(mv[0], mv[1])
				.attack(flagOut[0] > 0)
				.sprint(flagOut[2] > 0)
				.jump(flagOut[3] > 0 && player.has("onGround")
						&& player.get("onGround").getAsBoolean());
		if (flagOut[1] > 0) {
			bld.useHand("off");
		} else {
			bld.stopUsing(true);
		}
		return bld.build();
	}
}
