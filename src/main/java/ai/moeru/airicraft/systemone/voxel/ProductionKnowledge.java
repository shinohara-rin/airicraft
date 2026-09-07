package ai.moeru.airicraft.systemone.voxel;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Recipe and harvesting priors, separate from all world coordinates and observations. */
public record ProductionKnowledge(String version, List<Recipe> recipes, List<Harvest> harvesting) {
	public ProductionKnowledge { recipes = List.copyOf(recipes); harvesting = List.copyOf(harvesting); }
	public record Cell(int slot, String item) {}
	public record Recipe(String id, String output, int yield, int width, List<Cell> cells) {
		public Recipe {
			cells = List.copyOf(cells);
			if (yield < 1 || (width != 2 && width != 3) || cells.isEmpty()
				|| cells.stream().anyMatch(cell -> cell.slot() < 0 || cell.slot() >= width * width)
				|| cells.stream().map(Cell::slot).distinct().count() != cells.size()) throw new IllegalArgumentException("Invalid crafting pattern");
		}
		public Map<String, Integer> ingredients() {
			var result = new TreeMap<String, Integer>();
			cells.forEach(cell -> result.merge(cell.item(), 1, Integer::sum));
			return result;
		}
	}
	public enum Technique { EXPOSED, LOCAL_STONE }
	public record Harvest(String item, List<String> blocks, List<String> tools, Technique technique) {
		public Harvest { blocks = List.copyOf(blocks); tools = List.copyOf(tools); }
	}
}
