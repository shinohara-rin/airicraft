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
			JsonArray wArr = l.getAsJsonArray("w");
			JsonArray bArr = l.getAsJsonArray("b");
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
			double[] bv = new double[out];
			for (int o = 0; o < out; o++) {
				bv[o] = bArr.get(o).getAsDouble();
			}
			w.add(wm);
			b.add(bv);
		}
		ready = !w.isEmpty();
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

	/** Push obs onto the frame stack and run the forward pass. null if unconfigured. */
	double[] forwardStack(JsonObject obs) {
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
		return forward(x);
	}

	/** SelectorPolicy: argmax over the first nPrograms outputs. -1 if unconfigured. */
	int pickProgram(JsonObject obs, int nPrograms) {
		double[] y = forwardStack(obs);
		if (y == null) {
			return -1;
		}
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
		double[] y = forwardStack(obs);
		if (y == null) {
			return Intent.IDLE;
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
			if (y[2 + k] > best) {
				best = y[2 + k];
				ti = k;
			}
		}
		JsonObject target = hostiles.get(Math.min(ti, hostiles.size() - 1));

		Intent.Builder bld = Intent.builder()
				.lookEntity(target.get("id").getAsInt())
				.moveDir(y[0], y[1])
				.attack(y[2 + K] > 0)
				.sprint(y[2 + K + 2] > 0)
				.jump(y[2 + K + 3] > 0 && player.has("onGround")
						&& player.get("onGround").getAsBoolean());
		if (y[2 + K + 1] > 0) {
			bld.useHand("off");
		} else {
			bld.stopUsing(true);
		}
		return bld.build();
	}
}
