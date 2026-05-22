package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;

import java.util.List;

@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class AiricraftJourneyMapPlugin implements IClientPlugin {
	private static final String MOD_ID = "airicraft";

	private IClientAPI jmAPI;

	@Override
	public String getModId() {
		return MOD_ID;
	}

	@Override
	public void initialize(IClientAPI jmClientApi) {
		this.jmAPI = jmClientApi;
		MapIntegrationBridge.setProviders(List.of(new JourneyMapIntegrationProvider(jmAPI)));
	}
}
