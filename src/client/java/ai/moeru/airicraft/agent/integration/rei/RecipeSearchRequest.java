package ai.moeru.airicraft.agent.integration.rei;

public record RecipeSearchRequest(
	String query,
	RecipeSearchMode mode,
	int maxResults
) {
	public RecipeSearchRequest {
		query = query == null ? "" : query.trim();
		mode = mode == null ? RecipeSearchMode.ALL : mode;
		maxResults = Math.max(1, Math.min(48, maxResults));
	}
}
