package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.actions.ActionsetPromotionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ModBridgeServerTest {
	@Test
	void preservesActionsetPromotionExceptionsFromClientThread() {
		ActionsetPromotionException exception = new ActionsetPromotionException("trial_required", "trial required");

		RuntimeException mapped = ModBridgeServer.clientThreadFailure(exception);

		assertSame(exception, mapped);
	}
}
