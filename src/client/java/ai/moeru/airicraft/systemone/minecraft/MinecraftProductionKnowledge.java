package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.SmeltingPlannerService;
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
		harvests.add(new Harvest("minecraft:raw_iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
			List.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), Technique.EXPOSED));
		harvests.add(new Harvest("minecraft:coal", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
			List.of("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), Technique.EXPOSED));
		for (var block : Registries.BLOCK) {
			if (block.getDefaultState().isIn(BlockTags.LOGS) && block.asItem() != Items.AIR) {
				harvests.add(new Harvest(Registries.ITEM.getId(block.asItem()).toString(), List.of(Registries.BLOCK.getId(block).toString()), List.of(), Technique.EXPOSED));
			}
		}
		harvests.sort(Comparator.comparing(Harvest::item));
		var fuels = new ArrayList<Fuel>();
		for (var item : Registries.ITEM) {
			int ticks = client.world.getFuelRegistry().getFuelTicks(item.getDefaultStack());
			if (ticks > 0) fuels.add(new Fuel(Registries.ITEM.getId(item).toString(), ticks));
		}
		fuels.sort(Comparator.comparing(Fuel::item));
		var excavatable = List.of("minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff");
		var supports = List.of(new SupportMaterial("minecraft:cobblestone", "minecraft:cobblestone"), new SupportMaterial("minecraft:dirt", "minecraft:dirt"));
		var searches = List.of(new SearchPrior("minecraft:raw_iron", 16, 64, 96, excavatable, supports), new SearchPrior("minecraft:coal", 48, 48, 64, excavatable, supports));
		// Surface access is independent of geological resource priors. Leaves can be
		// cleared to reach a tree or its drops, but observed footing remains protected.
		var accessMaterials = new java.util.TreeSet<>(excavatable);
		for (var block : Registries.BLOCK) if (block.getDefaultState().isIn(BlockTags.LEAVES)) accessMaterials.add(Registries.BLOCK.getId(block).toString());
		return new ProductionKnowledge("minecraft-recipe-display-v6", CraftingOpportunityResolver.productionRecipes(client.player), harvests,
			SmeltingPlannerService.productionSmelts(client), fuels, searches, List.copyOf(accessMaterials), new ai.moeru.airicraft.systemone.voxel.LightingPolicy.Parameters(7, 10, 8, 80, 4), ai.moeru.airicraft.systemone.voxel.SurvivalPolicy.Parameters.minecraft());
	}
}
