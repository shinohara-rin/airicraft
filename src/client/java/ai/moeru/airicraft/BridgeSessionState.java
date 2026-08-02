package ai.moeru.airicraft;

public record BridgeSessionState(
	int port,
	String token,
	long startedAtEpochMillis,
	long processId
) {
}
