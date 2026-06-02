package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

public final class DialogueClearGoalVerification extends VerificationScenario {
	private final BooleanSupplier worldLoaded;
	private final Runnable injectNearbyPlayer;
	private final LongSupplier latestEventSeqNo;
	private final Runnable setupFollowResponse;
	private final Runnable injectFollowRequest;
	private final BooleanSupplier followGoalActive;
	private final Runnable setupClearResponse;
	private final Runnable injectStopRequest;
	private final BooleanSupplier goalCleared;
	private final BooleanSupplier observeStateActive;
	private final LongPredicate goalSetSeenSince;
	private final LongPredicate goalClearedSeenSince;

	private long goalSetBaselineSeqNo;
	private long goalClearedBaselineSeqNo;

	public DialogueClearGoalVerification(
		BooleanSupplier worldLoaded,
		Runnable injectNearbyPlayer,
		LongSupplier latestEventSeqNo,
		Runnable setupFollowResponse,
		Runnable injectFollowRequest,
		BooleanSupplier followGoalActive,
		Runnable setupClearResponse,
		Runnable injectStopRequest,
		BooleanSupplier goalCleared,
		BooleanSupplier observeStateActive,
		LongPredicate goalSetSeenSince,
		LongPredicate goalClearedSeenSince
	) {
		this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
		this.injectNearbyPlayer = Objects.requireNonNull(injectNearbyPlayer, "injectNearbyPlayer");
		this.latestEventSeqNo = Objects.requireNonNull(latestEventSeqNo, "latestEventSeqNo");
		this.setupFollowResponse = Objects.requireNonNull(setupFollowResponse, "setupFollowResponse");
		this.injectFollowRequest = Objects.requireNonNull(injectFollowRequest, "injectFollowRequest");
		this.followGoalActive = Objects.requireNonNull(followGoalActive, "followGoalActive");
		this.setupClearResponse = Objects.requireNonNull(setupClearResponse, "setupClearResponse");
		this.injectStopRequest = Objects.requireNonNull(injectStopRequest, "injectStopRequest");
		this.goalCleared = Objects.requireNonNull(goalCleared, "goalCleared");
		this.observeStateActive = Objects.requireNonNull(observeStateActive, "observeStateActive");
		this.goalSetSeenSince = Objects.requireNonNull(goalSetSeenSince, "goalSetSeenSince");
		this.goalClearedSeenSince = Objects.requireNonNull(goalClearedSeenSince, "goalClearedSeenSince");
	}

	@Override
	public String name() {
		return "dialogue.clear_goal";
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("in world", worldLoaded)
			.action("inject nearby player", injectNearbyPlayer)
			.action("capture goal-set baseline", () -> goalSetBaselineSeqNo = latestEventSeqNo.getAsLong())
			.action("setup follow response", setupFollowResponse)
			.action("inject follow request", injectFollowRequest)
			.waitUntil("follow goal active", 300, followGoalActive)
			.assertThat("planner goal_set emitted", () -> goalSetSeenSince.test(goalSetBaselineSeqNo))
			.action("capture goal-cleared baseline", () -> goalClearedBaselineSeqNo = latestEventSeqNo.getAsLong())
			.action("setup clear response", setupClearResponse)
			.action("inject stop request", injectStopRequest)
			.waitUntil("goal cleared", 300, goalCleared)
			.waitUntil("tree returns to observe", 100, observeStateActive)
			.waitUntil("planner goal_cleared emitted", 300, () -> goalClearedSeenSince.test(goalClearedBaselineSeqNo));
	}
}
