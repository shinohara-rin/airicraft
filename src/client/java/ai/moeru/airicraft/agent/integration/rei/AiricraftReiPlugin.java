package ai.moeru.airicraft.agent.integration.rei;

import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.registry.ReloadStage;

public final class AiricraftReiPlugin implements REIClientPlugin {
	@Override
	public void preStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
		if (stage == ReloadStage.START) {
			ReiRecipeSearchBridge.clearBackend();
		}
	}

	@Override
	public void postStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
		if (stage == ReloadStage.END) {
			ReiRecipeSearchBridge.setBackend(new ReiRuntimeRecipeSearchBackend());
		}
	}
}
