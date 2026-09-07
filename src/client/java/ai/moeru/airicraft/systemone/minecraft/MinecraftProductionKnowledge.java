package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;

/** Minecraft priors are adapted from active registries; no world positions enter this catalog. */
final class MinecraftProductionKnowledge {
	static ProductionKnowledge capture(MinecraftClient client) {
		var harvests = new ArrayList<Harvest>();
		harvests.add(new Harvest("minecraft:cobblestone", List.of("minecraft:stone", "minecraft:cobblestone"),
			List.of("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), Technique.LOCAL_STONE));
		for (var block : Registries.BLOCK) {
			if (block.getDefaultState().isIn(BlockTags.LOGS) && block.asItem() != Items.AIR) {
				harvests.add(new Harvest(Registries.ITEM.getId(block.asItem()).toString(), List.of(Registries.BLOCK.getId(block).toString()), List.of(), Technique.EXPOSED));
			}
		}
		harvests.sort(Comparator.comparing(Harvest::item));
		return new ProductionKnowledge("minecraft-recipe-display-v1", CraftingOpportunityResolver.productionRecipes(client.player), harvests);
	}
}
