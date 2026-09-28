package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

	// "egt" layout (see policy_egt.py): entity-set encoder + GRU memory.
	static final int EK = 8;           // hostile slots fed to the entity encoder
	static final int EEF = 18;         // per-entity numeric feats
	static final int EGE = 16;         // egt globals
	static final int ETE = 8;          // type embedding dim
	// entity embed / GRU hidden dims are derived from the loaded spec so
	// differently-sized egt checkpoints deploy without recompiling.
	private int ede = 32;
	private int edh = 48;
	private static final double[] DIST_CENTERS = {1.0, 3.0, 5.0, 8.0, 14.0};
	private static final double DIST_SIGMA = 2.0;

	private List<double[][]> w;   // per layer: out x in
	private List<double[]> b;     // per layer: out
	private boolean ready;
	private final Deque<double[]> frames = new ArrayDeque<>(STACK);

	// sampling mode (params.sample:true): actions are drawn from the policy
	// distributions instead of thresholded, and the exact sampled action plus
	// its log-prob under the deployed weights is exposed via sampledAction()
	// for offline PPO/GRPO ratio recomputation.
	private boolean sample;
	private double[] mvStd = new double[]{Math.exp(-1.0), Math.exp(-1.0)};
	private final Random rng = new Random();
	private JsonObject lastSampled;

	// "ptr" layout: trunk -> ctx; per-slot head scores each hostile's 14 feats
	// (pointer-style target selection, the neural analog of the AST target
	// grammar); separate move/flag heads over ctx.
	private List<double[][]> ptrW;
	private List<double[]> ptrB;
	private List<double[][]> headW;   // [move 48->2, flags 48->4]
	private List<double[]> headB;

	// egt layout state
	private boolean egtReady;
	private List<double[][]> egtEntW; private List<double[]> egtEntB;
	private List<double[][]> egtGlobW; private List<double[]> egtGlobB;
	private double[][] egtAttq;       // [D_E][D_E], y = W x (no bias)
	private double[][] egtWih; private double[][] egtWhh;
	private double[] egtBih; private double[] egtBhh;
	private List<double[][]> egtPtrW; private List<double[]> egtPtrB;
	private List<double[][]> egtHeadW; private List<double[]> egtHeadB;  // mv, fl, v
	private double[][] egtTypeEmb;    // [NT][TE]
	private Map<String, Integer> egtTypeMap;
	private double[] egtHidden = new double[48];

	private static boolean isRanged(String type) {
		return type.contains("skeleton") || type.contains("stray")
				|| type.contains("pillager") || type.contains("witch")
				|| type.contains("blaze") || type.contains("breeze");
	}

	// exact base-name match for egt (avoids wither_skeleton -> "skeleton")
	private static boolean isRangedExact(String type) {
		String base = type.substring(type.lastIndexOf('.') + 1);
		return base.equals("skeleton") || base.equals("stray")
				|| base.equals("pillager") || base.equals("witch")
				|| base.equals("blaze") || base.equals("breeze");
	}

	@Override
	public String id() {
		return "net";
	}

	@Override
	public void reset() {
		frames.clear();
		java.util.Arrays.fill(egtHidden, 0.0);
	}

	@Override
	public JsonObject sampledAction() {
		return lastSampled;
	}

	@Override
	public void configure(JsonObject params) {
		sample = params.has("sample") && params.get("sample").getAsBoolean();
		JsonElement net = params.get("net");
		if (net == null || !net.isJsonObject()) {
			return;
		}
		JsonObject spec = net.getAsJsonObject();
		if (spec.has("logStd")) {
			JsonArray ls = spec.getAsJsonArray("logStd");
			mvStd = new double[]{Math.exp(ls.get(0).getAsDouble()),
					Math.exp(ls.get(1).getAsDouble())};
		}
		if (spec.has("layout") && "egt".equals(spec.get("layout").getAsString())) {
			configureEgt(spec);
			return;
		}
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

	private void configureEgt(JsonObject spec) {
		egtTypeEmb = readMatrix(spec.getAsJsonArray("typeEmb"));
		egtTypeMap = new HashMap<>();
		JsonArray vocab = spec.getAsJsonArray("typeVocab");
		for (int i = 0; i < vocab.size(); i++) {
			egtTypeMap.put(vocab.get(i).getAsString(), i);
		}
		egtEntW = readLayers(spec.getAsJsonArray("ent"));
		egtGlobW = readLayers(spec.getAsJsonArray("glob"));
		egtEntB = readBiases(spec.getAsJsonArray("ent"));
		egtGlobB = readBiases(spec.getAsJsonArray("glob"));
		egtAttq = readMatrix(spec.getAsJsonArray("attq"));
		JsonObject gru = spec.getAsJsonObject("gru");
		egtWih = readMatrix(gru.getAsJsonArray("wih"));
		egtWhh = readMatrix(gru.getAsJsonArray("whh"));
		egtBih = readVec(gru.getAsJsonArray("bih"));
		egtBhh = readVec(gru.getAsJsonArray("bhh"));
		egtPtrW = readLayers(spec.getAsJsonArray("ptr"));
		egtPtrB = readBiases(spec.getAsJsonArray("ptr"));
		egtHeadW = readLayers(spec.getAsJsonArray("heads"));
		egtHeadB = readBiases(spec.getAsJsonArray("heads"));
		ede = egtGlobW.get(0).length;
		edh = egtBih.length / 3;
		egtHidden = new double[edh];
		egtReady = true;
	}

	private static double[][] readMatrix(JsonArray arr) {
		double[][] m = new double[arr.size()][];
		for (int i = 0; i < arr.size(); i++) {
			JsonArray row = arr.get(i).getAsJsonArray();
			m[i] = new double[row.size()];
			for (int j = 0; j < row.size(); j++) {
				m[i][j] = row.get(j).getAsDouble();
			}
		}
		return m;
	}

	private static double[] readVec(JsonArray arr) {
		double[] v = new double[arr.size()];
		for (int i = 0; i < arr.size(); i++) {
			v[i] = arr.get(i).getAsDouble();
		}
		return v;
	}

	private static List<double[][]> readLayers(JsonArray arr) {
		List<double[][]> out = new ArrayList<>();
		for (JsonElement le : arr) {
			out.add(readW(le.getAsJsonObject()));
		}
		return out;
	}

	private static List<double[]> readBiases(JsonArray arr) {
		List<double[]> out = new ArrayList<>();
		for (JsonElement le : arr) {
			out.add(readB(le.getAsJsonObject()));
		}
		return out;
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

	// ------------------------------------------------- egt encode/forward --

	private static List<JsonObject> hostilesOf(JsonObject obs) {
		List<JsonObject> hostiles = new ArrayList<>();
		for (JsonElement el : obs.getAsJsonArray("entities")) {
			JsonObject e = el.getAsJsonObject();
			boolean hostile = e.has("hostile") && e.get("hostile").getAsBoolean();
			boolean isTargeting = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean();
			if (e.has("health") && (hostile || isTargeting)) {
				hostiles.add(e);
			}
		}
		hostiles.sort(Comparator.comparingDouble(e -> e.get("dist").getAsDouble()));
		return hostiles;
	}

	private static double[] matvec(double[][] wm, double[] bv, double[] x) {
		double[] o = new double[wm.length];
		for (int r = 0; r < wm.length; r++) {
			double s = bv == null ? 0.0 : bv[r];
			double[] row = wm[r];
			for (int c = 0; c < row.length; c++) {
				s += row[c] * x[c];
			}
			o[r] = s;
		}
		return o;
	}

	private static void tanhInPlace(double[] a) {
		for (int i = 0; i < a.length; i++) {
			a[i] = Math.tanh(a[i]);
		}
	}

	/** Mirror of policy_egt.encode_egt: globals + per-entity feats + type ids. */
	private double[] forwardEgt(JsonObject obs, List<JsonObject> hostiles) {
		JsonObject p = obs.getAsJsonObject("player");
		double px = p.getAsJsonObject("pos").get("x").getAsDouble();
		double py = p.getAsJsonObject("pos").get("y").getAsDouble();
		double pz = p.getAsJsonObject("pos").get("z").getAsDouble();
		double pvx = p.getAsJsonObject("vel").get("x").getAsDouble();
		double pvz = p.getAsJsonObject("vel").get("z").getAsDouble();
		double yaw = Math.toRadians(p.has("yaw") ? p.get("yaw").getAsDouble() : 0.0);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // MC facing in (x,z)

		double ux = 0, uz = 0, lit = 0, aiming = 0, targeting = 0;
		double hpSum = 0, nd = 99.0;
		double[][] ef = new double[EK][EEF];
		int[] tid = new int[EK];
		java.util.Arrays.fill(tid, egtTypeEmb.length - 1);   // "other"

		int n = Math.min(hostiles.size(), EK);
		for (int k = 0; k < n; k++) {
			JsonObject e = hostiles.get(k);
			double dx = e.getAsJsonObject("pos").get("x").getAsDouble() - px;
			double dz = e.getAsJsonObject("pos").get("z").getAsDouble() - pz;
			double d = Math.max(e.get("dist").getAsDouble(), 1e-6);
			double ex = dx / d, ez = dz / d;
			ux += ex; uz += ez;
			hpSum += e.get("health").getAsDouble();
			if (d < nd) nd = d;
			String t = e.get("type").getAsString();
			double fuz = e.has("fuse") ? e.get("fuse").getAsDouble() : 0.0;
			double aim = e.has("aiming") && e.get("aiming").getAsBoolean() ? 1 : 0;
			double tgt = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean() ? 1 : 0;
			if (fuz > 0.4) lit++;
			aiming += aim;
			targeting += tgt;

			double cosb = fx * ex + fz * ez;
			double sinb = fx * ez - fz * ex;
			double rvx = e.getAsJsonObject("vel").get("x").getAsDouble() - pvx;
			double rvz = e.getAsJsonObject("vel").get("z").getAsDouble() - pvz;
			double vrad = rvx * ex + rvz * ez;
			double vtan = rvx * ez - rvz * ex;
			double spd = Math.hypot(e.getAsJsonObject("vel").get("x").getAsDouble(),
					e.getAsJsonObject("vel").get("z").getAsDouble());

			double[] row = ef[k];
			row[0] = clamp(d / 20.0, 0, 1);
			for (int i = 0; i < DIST_CENTERS.length; i++) {
				double dd = d - DIST_CENTERS[i];
				row[1 + i] = Math.exp(-(dd * dd) / (2 * DIST_SIGMA * DIST_SIGMA));
			}
			row[6] = sinb;
			row[7] = cosb;
			row[8] = clamp((e.getAsJsonObject("pos").get("y").getAsDouble() - py) / 4.0, -1, 1);
			row[9] = clamp(vrad / 5.0, -1, 1);
			row[10] = clamp(vtan / 5.0, -1, 1);
			row[11] = clamp(spd / 5.0, 0, 1);
			row[12] = clamp(e.get("health").getAsDouble()
					/ Math.max(e.has("maxHealth") ? e.get("maxHealth").getAsDouble() : 20.0, 1.0), 0, 1);
			row[13] = e.has("playerHits") ? clamp(e.get("playerHits").getAsDouble() / 6.0, 0, 1) : 0;
			row[14] = tgt;
			row[15] = isRangedExact(t) ? 1 : 0;
			row[16] = clamp(fuz, 0, 1);
			row[17] = aim;
			String base = t.substring(t.lastIndexOf('.') + 1);
			tid[k] = egtTypeMap.getOrDefault(base, egtTypeEmb.length - 1);
		}

		double encirc = n > 0 ? 1.0 - Math.hypot(ux, uz) / n : 0.0;
		double[] g = new double[EGE];
		g[0] = clamp(p.get("health").getAsDouble() / 20.0, 0, 1);
		g[1] = clamp(p.get("attackCooldown").getAsDouble(), 0, 1);
		g[2] = clamp(p.get("lastAttackedTicks").getAsInt() / 60.0, 0, 1);
		g[3] = p.has("usingItem") && p.get("usingItem").getAsBoolean() ? 1 : 0;
		g[4] = clamp(p.has("useTicks") ? p.get("useTicks").getAsDouble() / 40.0 : 0, 0, 1);
		g[5] = p.has("offhandPct") ? clamp(p.get("offhandPct").getAsDouble(), 0, 1) : 1;
		g[6] = p.has("onGround") && p.get("onGround").getAsBoolean() ? 1 : 0;
		g[7] = p.has("food") ? clamp(p.get("food").getAsInt() / 20.0, 0, 1) : 1;
		g[8] = clamp(hostiles.size() / 9.0, 0, 1);
		g[9] = clamp(encirc, 0, 1);
		g[10] = clamp(lit / 3.0, 0, 1);
		g[11] = clamp(aiming / 4.0, 0, 1);
		g[12] = clamp(targeting / 9.0, 0, 1);
		g[13] = clamp(hpSum / 200.0, 0, 1);
		g[14] = clamp(nd / 20.0, 0, 1);
		g[15] = p.has("sprinting") && p.get("sprinting").getAsBoolean() ? 1 : 0;

		// entity encoder
		double[][] E = new double[EK][ede];
		for (int k = 0; k < EK; k++) {
			double[] in = new double[EEF + ETE];
			System.arraycopy(ef[k], 0, in, 0, EEF);
			System.arraycopy(egtTypeEmb[tid[k]], 0, in, EEF, ETE);
			double[] a = matvec(egtEntW.get(0), egtEntB.get(0), in);
			tanhInPlace(a);
			a = matvec(egtEntW.get(1), egtEntB.get(1), a);
			tanhInPlace(a);
			E[k] = a;
		}
		// global encoder + cross-attention (single head, player-state query)
		double[] gv = matvec(egtGlobW.get(0), egtGlobB.get(0), g);
		tanhInPlace(gv);
		gv = matvec(egtGlobW.get(1), egtGlobB.get(1), gv);
		tanhInPlace(gv);
		double[] q = matvec(egtAttq, null, gv);
		double[] wts = new double[n];
		double mx = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < n; k++) {
			double s = 0;
			for (int i = 0; i < ede; i++) s += E[k][i] * q[i];
			wts[k] = s / Math.sqrt(ede);
			if (wts[k] > mx) mx = wts[k];
		}
		double wsum = 0;
		for (int k = 0; k < n; k++) {
			wts[k] = Math.exp(wts[k] - mx);
			wsum += wts[k];
		}
		double[] attn = new double[ede];
		double[] meanE = new double[ede];
		for (int k = 0; k < n; k++) {
			double w = wsum > 0 ? wts[k] / wsum : 0;
			for (int i = 0; i < ede; i++) {
				attn[i] += w * E[k][i];
				meanE[i] += E[k][i] / n;
			}
		}
		double[] fused = new double[3 * ede];
		System.arraycopy(gv, 0, fused, 0, ede);
		System.arraycopy(attn, 0, fused, ede, ede);
		System.arraycopy(meanE, 0, fused, 2 * ede, ede);

		// GRUCell update — PyTorch gate order [r; z; n]:
		//   r = sig(W_ir x + b_ir + W_hr h + b_hr),   z likewise
		//   n = tanh(W_in x + b_in + r * (W_hn h + b_hn))
		//   h' = (1 - z) * n + z * h
		double[] h = egtHidden;
		double[] hn = new double[edh];    // W_hn h + b_hn (reset gate applies later)
		double[] hnew = new double[edh];
		for (int i = 0; i < edh; i++) {
			double xr = egtBih[i], xz = egtBih[edh + i], xn = egtBih[2 * edh + i];
			double hr = egtBhh[i], hz = egtBhh[edh + i], hh = egtBhh[2 * edh + i];
			for (int c = 0; c < fused.length; c++) {
				xr += egtWih[i][c] * fused[c];
				xz += egtWih[edh + i][c] * fused[c];
				xn += egtWih[2 * edh + i][c] * fused[c];
			}
			for (int c = 0; c < edh; c++) {
				hr += egtWhh[i][c] * h[c];
				hz += egtWhh[edh + i][c] * h[c];
				hh += egtWhh[2 * edh + i][c] * h[c];
			}
			hn[i] = hh;
			double r = 1.0 / (1.0 + Math.exp(-(xr + hr)));
			double z = 1.0 / (1.0 + Math.exp(-(xz + hz)));
			double ng = Math.tanh(xn + r * hn[i]);
			hnew[i] = (1.0 - z) * ng + z * h[i];
		}
		System.arraycopy(hnew, 0, egtHidden, 0, edh);

		// heads: move(2), flags(4) over h; ptr scorer over [h | E_k]
		double[] mv = matvec(egtHeadW.get(0), egtHeadB.get(0), hnew);
		double[] fl = matvec(egtHeadW.get(1), egtHeadB.get(1), hnew);
		double[] out = new double[2 + EK + 4];
		out[0] = mv[0]; out[1] = mv[1];
		for (int k = 0; k < EK; k++) {
			if (k >= n) {
				out[2 + k] = -1e9;   // dead slot: never argmax
				continue;
			}
			double[] pin = new double[edh + ede];
			System.arraycopy(hnew, 0, pin, 0, edh);
			System.arraycopy(E[k], 0, pin, edh, ede);
			double[] pa = matvec(egtPtrW.get(0), egtPtrB.get(0), pin);
			tanhInPlace(pa);
			out[2 + k] = matvec(egtPtrW.get(1), egtPtrB.get(1), pa)[0];
		}
		for (int i = 0; i < 4; i++) {
			out[2 + EK + i] = fl[i];
		}
		return out;
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
		lastSampled = null;
		if (egtReady) {
			return decideEgt(obs);
		}
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
			if (sample) {
				lastSampled = sampleAction(mv, tgtScores, 0, flagOut);
				return decodeIntent(lastSampled, null, player);
			}
			return Intent.IDLE;
		}
		hostiles.sort(Comparator.comparingDouble(e -> e.get("dist").getAsDouble()));

		int nLive = Math.min(K, hostiles.size());
		if (sample) {
			lastSampled = sampleAction(mv, tgtScores, nLive, flagOut);
			return decodeIntent(lastSampled, hostiles, player);
		}
		lastSampled = null;

		// target: argmax over slot scores (falls back to nearest beyond K)
		int ti = 0;
		double best = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < nLive; k++) {
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

	// ----------------------------------------------------------- sampling --

	private static double sigmoid(double x) {
		return 1.0 / (1.0 + Math.exp(-x));
	}

	/**
	 * Draw a joint action from the policy distributions and compute its logp:
	 * mv ~ Normal(mean, mvStd) per dim, flags ~ Bernoulli(sigmoid(logit)),
	 * target ~ Categorical(softmax over live slot scores). The record keeps the
	 * RAW samples (pre legality gating) so a replayer can recompute the same
	 * logp under updated weights.
	 */
	private JsonObject sampleAction(double[] mv, double[] scores, int nLive,
			double[] flagOut) {
		double lp = 0.0;
		double[] a = new double[2];
		for (int i = 0; i < 2; i++) {
			a[i] = mv[i] + mvStd[i] * rng.nextGaussian();
			double z = (a[i] - mv[i]) / mvStd[i];
			lp += -0.5 * z * z - Math.log(mvStd[i]) - 0.9189385332046727; // log sqrt(2pi)
		}
		int ti = -1;
		if (nLive > 0) {
			double mx = Double.NEGATIVE_INFINITY;
			for (int k = 0; k < nLive; k++) {
				if (scores[k] > mx) mx = scores[k];
			}
			double sum = 0;
			double[] p = new double[nLive];
			for (int k = 0; k < nLive; k++) {
				p[k] = Math.exp(scores[k] - mx);
				sum += p[k];
			}
			double u = rng.nextDouble() * sum;
			double acc = 0;
			ti = nLive - 1;
			for (int k = 0; k < nLive; k++) {
				acc += p[k];
				if (u <= acc) {
					ti = k;
					break;
				}
			}
			lp += Math.log(p[ti] / sum);
		}
		int[] fl = new int[4];
		for (int i = 0; i < 4; i++) {
			double p = sigmoid(flagOut[i]);
			fl[i] = rng.nextDouble() < p ? 1 : 0;
			lp += Math.log(fl[i] == 1 ? p : 1.0 - p);
		}
		return sampledJson(a, ti, fl, lp);
	}

	private static JsonObject sampledJson(double[] mv, int tgt, int[] fl,
			double lp) {
		JsonObject s = new JsonObject();
		JsonArray a = new JsonArray();
		a.add(mv[0]); a.add(mv[1]);
		s.add("mv", a);
		s.addProperty("tgt", tgt);
		if (fl != null) {
			JsonArray f = new JsonArray();
			for (int v : fl) f.add(v);
			s.add("flags", f);
		}
		s.addProperty("logp", lp);
		return s;
	}

	/** Decode a sampled action record into the same Intent shape as argmax. */
	private Intent decodeIntent(JsonObject sm, List<JsonObject> hostiles,
			JsonObject player) {
		JsonArray mvA = sm.getAsJsonArray("mv");
		JsonArray flA = sm.getAsJsonArray("flags");
		boolean atk = flA != null && flA.get(0).getAsInt() == 1;
		boolean use = flA != null && flA.get(1).getAsInt() == 1;
		boolean spr = flA != null && flA.get(2).getAsInt() == 1;
		boolean jmp = flA != null && flA.get(3).getAsInt() == 1;
		Intent.Builder bld = Intent.builder()
				.moveDir(mvA.get(0).getAsDouble(), mvA.get(1).getAsDouble())
				.attack(atk)
				.sprint(spr)
				.jump(jmp && player.has("onGround")
						&& player.get("onGround").getAsBoolean());
		int ti = sm.get("tgt").getAsInt();
		if (hostiles != null && ti >= 0) {
			bld.lookEntity(hostiles.get(Math.min(ti, hostiles.size() - 1))
					.get("id").getAsInt());
		}
		if (use) {
			bld.useHand("off");
		} else {
			bld.stopUsing(true);
		}
		return bld.build();
	}

	private Intent decideEgt(JsonObject obs) {
		List<JsonObject> hostiles = hostilesOf(obs);
		double[] y = forwardEgt(obs, hostiles);   // also advances the GRU state
		JsonObject player = obs.getAsJsonObject("player");
		int n = Math.min(EK, hostiles.size());
		if (sample) {
			double[] mv = {y[0], y[1]};
			double[] scores = new double[EK];
			System.arraycopy(y, 2, scores, 0, EK);
			double[] fl = new double[4];
			System.arraycopy(y, 2 + EK, fl, 0, 4);
			lastSampled = sampleAction(mv, scores, n, fl);
			return decodeIntent(lastSampled, n > 0 ? hostiles : null, player);
		}
		lastSampled = null;
		Intent.Builder bld = Intent.builder()
				.moveDir(y[0], y[1])
				.attack(y[2 + EK] > 0)
				.sprint(y[2 + EK + 2] > 0)
				.jump(y[2 + EK + 3] > 0 && player.has("onGround")
						&& player.get("onGround").getAsBoolean());
		if (!hostiles.isEmpty()) {
			int ti = 0;
			double best = Double.NEGATIVE_INFINITY;
			for (int k = 0; k < n; k++) {
				if (y[2 + k] > best) {
					best = y[2 + k];
					ti = k;
				}
			}
			bld.lookEntity(hostiles.get(ti).get("id").getAsInt());
		}
		if (y[2 + EK + 1] > 0) {
			bld.useHand("off");
		} else {
			bld.stopUsing(true);
		}
		return bld.build();
	}
}
