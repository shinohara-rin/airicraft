package ai.moeru.airicraft.systemone.voxel;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Recipe and harvesting priors, separate from all world coordinates and observations. */
public record ProductionKnowledge(String version, List<Recipe> recipes, List<Harvest> harvesting, List<Smelt> smelting, List<Fuel> fuels, List<SearchPrior> searches, LightingPolicy.Parameters lighting) {
	public ProductionKnowledge {
		recipes = List.copyOf(recipes); harvesting = List.copyOf(harvesting); smelting = List.copyOf(smelting); fuels = List.copyOf(fuels); searches = List.copyOf(searches);
	}
	public ProductionKnowledge(String version, List<Recipe> recipes, List<Harvest> harvesting, List<Smelt> smelting, List<Fuel> fuels) { this(version, recipes, harvesting, smelting, fuels, List.of(), new LightingPolicy.Parameters(7, 10, 8, 80, 4)); }
	public ProductionKnowledge(String version, List<Recipe> recipes, List<Harvest> harvesting) { this(version, recipes, harvesting, List.of(), List.of()); }
	public record SearchPrior(String item, int preferredY, int radius, int maxSteps, List<String> excavatable) {
		public SearchPrior { excavatable = List.copyOf(excavatable); if (radius < 1 || maxSteps < 1) throw new IllegalArgumentException("Positive search bounds required"); }
	}
	public record Smelt(String id, String input, String output, int yield, String station, int ticks) {
		public Smelt { if (yield < 1 || ticks < 1) throw new IllegalArgumentException("Positive smelting yield and duration required"); }
	}
	public record Fuel(String item, int ticks) {
		public Fuel { if (ticks < 1) throw new IllegalArgumentException("Positive fuel duration required"); }
		public int quantity(int cookTicks) { return Math.ceilDiv(cookTicks, ticks); }
	}
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
	/** Tools are listed in the domain pack's preferred progression order. */
	public record Harvest(String item, List<String> blocks, List<String> tools, Technique technique) {
		public Harvest { blocks = List.copyOf(blocks); tools = List.copyOf(tools); }
	}
}
