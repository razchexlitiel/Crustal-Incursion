package com.cim.api.metallurgy;

import com.cim.api.metallurgy.system.Metal;
import com.cim.api.metallurgy.system.MetalUnits2;
import com.cim.api.metallurgy.system.MetallurgyRegistry;
import com.cim.api.metallurgy.system.recipe.AlloyRecipe;
import com.cim.api.metallurgy.system.recipe.AlloySlot;
import com.cim.api.resource.ResourceRegistry;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class ModMetallurgy {

    public static void init() {
        // ============ СТАЛЬ ============
        Metal steel = MetallurgyRegistry.registerMetal("steel", 0x71797E, 1470,
                MetalUnits2.UNITS_PER_INGOT, MetalUnits2.UNITS_PER_NUGGET, MetalUnits2.UNITS_PER_BLOCK,
                200); // время плавки базовой единицы (слитка) – 10 секунд

        // Привязка предметов из ResourceRegistry
        steel.setIngot(ResourceRegistry.getMainUnit("steel"));
        steel.setNugget(ResourceRegistry.getSmallUnit("steel"));
        steel.setBlock(ResourceRegistry.getBlock("steel"));

        AlloyRecipe steelAlloy = new AlloyRecipe(
                new AlloySlot[]{
                        new AlloySlot(Items.IRON_INGOT, 2),
                        new AlloySlot(Items.COAL, 2),
                        new AlloySlot(null, 0),
                        new AlloySlot(null, 0)
                },
                steel,
                MetalUnits2.UNITS_PER_INGOT * 2, // 18 единиц
                6000,   // totalHeat (300 тиков * 20)
                20      // heatPerTick
        );
        MetallurgyRegistry.addAlloyRecipe(steelAlloy);
        // Кастомный рецепт: железный меч → 1 слиток стали
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_SWORD, steel, MetalUnits2.UNITS_PER_INGOT, 100); // 5 секунд



        // ============ АЛЮМИНИЙ ============
        Metal aluminum = MetallurgyRegistry.registerMetal("aluminum", 0xE0E0E0, 660,
                MetalUnits2.UNITS_PER_INGOT, MetalUnits2.UNITS_PER_NUGGET, MetalUnits2.UNITS_PER_BLOCK,
                150); // 7.5 секунд
        aluminum.setIngot(ResourceRegistry.getMainUnit("aluminum"));
        aluminum.setNugget(ResourceRegistry.getSmallUnit("aluminum"));
        aluminum.setBlock(ResourceRegistry.getBlock("aluminum"));

        // (здесь можно добавить сплавной рецепт для алюминия, если нужно)

        // ============ ВАНИЛЬНОЕ ЗОЛОТО ============
        Metal gold = MetallurgyRegistry.registerMetal("gold", 0xFFD700, 1064,
                MetalUnits2.UNITS_PER_INGOT, MetalUnits2.UNITS_PER_NUGGET, MetalUnits2.UNITS_PER_BLOCK,
                200);

        gold.setIngot(Items.GOLD_INGOT);
        gold.setNugget(Items.GOLD_NUGGET);
        gold.setBlock(Blocks.GOLD_BLOCK);
        // Кастомные рецепты для золота
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_SWORD, gold, MetalUnits2.UNITS_PER_INGOT, 80);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_HELMET, gold, MetalUnits2.UNITS_PER_INGOT * 2, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_CHESTPLATE, gold, MetalUnits2.UNITS_PER_INGOT * 5, 120);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_LEGGINGS, gold, MetalUnits2.UNITS_PER_INGOT * 4, 110);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_BOOTS, gold, MetalUnits2.UNITS_PER_INGOT * 2, 80);

        // ============ ЖЕЛЕЗО (пример) ============
        Metal iron = MetallurgyRegistry.registerMetal("iron", 0xB87333, 958,
                MetalUnits2.UNITS_PER_INGOT, MetalUnits2.UNITS_PER_NUGGET, MetalUnits2.UNITS_PER_BLOCK,
                200);
        iron.setIngot(Items.IRON_INGOT);
        iron.setNugget(Items.IRON_NUGGET);
        iron.setBlock(Blocks.IRON_BLOCK);

        // Рецепты для железных предметов (можно добавить)
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_ORE, iron, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.DEEPSLATE_IRON_ORE, iron, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.RAW_IRON, iron, MetalUnits2.UNITS_PER_INGOT, 80);
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_SWORD, iron, MetalUnits2.UNITS_PER_INGOT, 60);

        // ============ МЕДЬ (пример) ============
        Metal copper = MetallurgyRegistry.registerMetal("copper", 0xFF6B35, 1085,
                MetalUnits2.UNITS_PER_INGOT, 0, MetalUnits2.UNITS_PER_BLOCK,
                200);
        copper.setIngot(Items.COPPER_INGOT);
        copper.setBlock(Blocks.COPPER_BLOCK);
        // У меди нет самородка, поэтому smallUnits = 0 и nugget не задаём

        MetallurgyRegistry.addSmeltRecipe(Items.COPPER_ORE, copper, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.DEEPSLATE_COPPER_ORE, copper, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.RAW_COPPER, copper, MetalUnits2.UNITS_PER_INGOT, 80);

        // ============ НЕЗЕРИТ ============
        Metal netherite = MetallurgyRegistry.registerMetal("netherite", 0x383038, 1200,
                MetalUnits2.UNITS_PER_INGOT, 0, MetalUnits2.UNITS_PER_BLOCK,
                250);
        netherite.setIngot(Items.NETHERITE_INGOT);
        netherite.setBlock(Blocks.NETHERITE_BLOCK);

        MetallurgyRegistry.addSmeltRecipe(Items.NETHERITE_SCRAP, netherite, MetalUnits2.UNITS_PER_INGOT, 120);
    }
}