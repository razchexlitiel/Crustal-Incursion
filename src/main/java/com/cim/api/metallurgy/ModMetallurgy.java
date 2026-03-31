package com.cim.api.metallurgy;

import com.cim.api.metallurgy.system.Metal;
import com.cim.api.metallurgy.system.MetalUnits2;
import com.cim.api.metallurgy.system.MetallurgyRegistry;
import com.cim.api.metallurgy.system.recipe.AlloyRecipe;
import com.cim.api.metallurgy.system.recipe.AlloySlot;
import com.cim.main.ResourceRegistry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class ModMetallurgy {

    public static void init() {

        // === ЗОЛОТО ===
        Metal gold = registerMetalWithItems("gold", 0xFFD700, 1064, 200, Items.GOLD_INGOT, Items.GOLD_NUGGET, Blocks.GOLD_BLOCK);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_SWORD, gold, MetalUnits2.UNITS_PER_INGOT, 80);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_HELMET, gold, MetalUnits2.UNITS_PER_INGOT * 2, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_CHESTPLATE, gold, MetalUnits2.UNITS_PER_INGOT * 5, 120);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_LEGGINGS, gold, MetalUnits2.UNITS_PER_INGOT * 4, 110);
        MetallurgyRegistry.addSmeltRecipe(Items.GOLDEN_BOOTS, gold, MetalUnits2.UNITS_PER_INGOT * 2, 80);

        // === ЖЕЛЕЗО ===
        Metal iron = registerMetalWithItems("iron", 0xB87333, 958, 200, Items.IRON_INGOT, Items.IRON_NUGGET, Blocks.IRON_BLOCK);
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_ORE, iron, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.DEEPSLATE_IRON_ORE, iron, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.RAW_IRON, iron, MetalUnits2.UNITS_PER_INGOT, 80);
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_SWORD, iron, MetalUnits2.UNITS_PER_INGOT, 60);

        // === МЕДЬ ===
        Metal copper = registerMetalWithItems("copper", 0xFF6B35, 1085, 200, Items.COPPER_INGOT, null, Blocks.COPPER_BLOCK);
        MetallurgyRegistry.addSmeltRecipe(Items.COPPER_ORE, copper, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.DEEPSLATE_COPPER_ORE, copper, MetalUnits2.UNITS_PER_INGOT, 100);
        MetallurgyRegistry.addSmeltRecipe(Items.RAW_COPPER, copper, MetalUnits2.UNITS_PER_INGOT, 80);

        // === НЕЗЕРИТ ===
        Metal netherite = registerMetalWithItems("netherite", 0x383038, 1200, 250, Items.NETHERITE_INGOT, null, Blocks.NETHERITE_BLOCK);


        // КАСТОМ МЕТАЛЛЫ


        // === СТАЛЬ ===
        Metal steel = registerMetalWithItems("steel", 0x71797E, 1440, 200, ResourceRegistry.getMainUnit("steel"), ResourceRegistry.getSmallUnit("steel"), ResourceRegistry.getBlock("steel"));
        // сплав
        AlloyRecipe steelAlloy = new AlloyRecipe(
                new AlloySlot[]{
                        new AlloySlot(Items.IRON_INGOT, 2),
                        new AlloySlot(Items.COAL, 2),
                        new AlloySlot(null, 0),
                        new AlloySlot(null, 0)
                },
                steel,
                MetalUnits2.UNITS_PER_INGOT * 2,
                6000, 20
        );
        MetallurgyRegistry.addAlloyRecipe(steelAlloy);
        MetallurgyRegistry.addSmeltRecipe(Items.IRON_SWORD, steel, MetalUnits2.UNITS_PER_INGOT, 100);


        // === АЛЮМИНИЙ ===
        Metal aluminum = registerMetalWithItems("aluminum", 0xE0E0E0, 660, 150, ResourceRegistry.getMainUnit("aluminum"), ResourceRegistry.getSmallUnit("aluminum"), ResourceRegistry.getBlock("aluminum"));






        // ГЕНЕРАЦИЯ СТАНДАРТНЫХ РЕЦЕПТОВ (слитки, самородки, блоки) – только для зарегистрированных металлов
        MetallurgyRegistry.generateStandardRecipes();
    }

//    ШАБЛОН ДЛЯ ДОБАВЛЕНИЯ МЕТАЛЛА

//    // 1. Зарегистрируйте металл с предметами
//    Metal newMetal = registerMetalWithItems("metal_name",
//            0xRRGGBB,          // цвет в hex
//            meltingPoint,      // температура плавления
//            baseSmeltTime,     // время плавки слитка (тики)
//            ResourceRegistry.getMainUnit("metal_name"), // слиток из ResourceRegistry
//            ResourceRegistry.getSmallUnit("metal_name"), // самородок (или null)
//            ResourceRegistry.getBlock("metal_name"));    // блок (или null)
//
//    // 2. (опционально) добавьте сплавной рецепт
//    AlloyRecipe alloy = new AlloyRecipe(
//            new AlloySlot[]{
//                    new AlloySlot(Items.IRON_INGOT, 2),
//                    new AlloySlot(Items.COAL, 2),
//                    new AlloySlot(null, 0),
//                    new AlloySlot(null, 0)
//            },
//            newMetal,
//            MetalUnits2.UNITS_PER_INGOT * 2,
//            6000, 20
//    );
//    MetallurgyRegistry.addAlloyRecipe(alloy);
//
//    // 3. (опционально) добавьте кастомные рецепты (например, для инструментов)
//    MetallurgyRegistry.addSmeltRecipe(Items.IRON_SWORD, newMetal, MetalUnits2.UNITS_PER_INGOT, 100);
//
//    // 4. После всех регистраций и привязок, в самом конце init() уже вызывается
//    //    MetallurgyRegistry.generateStandardRecipes(); – она автоматически добавит рецепты
//    //    для слитка, самородка и блока, если они есть.


    /**
     * Универсальный метод для регистрации металла с привязкой предметов.
     * @param name идентификатор
     * @param color цвет
     * @param meltingPoint температура плавления
     * @param baseSmeltTime время плавки слитка (тики)
     * @param ingot слиток (может быть null)
     * @param nugget самородок (может быть null)
     * @param block блок (может быть null)
     */
    private static Metal registerMetalWithItems(String name, int color, int meltingPoint, int baseSmeltTime,
                                                Item ingot, Item nugget, Block block) {
        Metal metal = MetallurgyRegistry.registerMetal(name, color, meltingPoint,
                MetalUnits2.UNITS_PER_INGOT,
                nugget != null ? MetalUnits2.UNITS_PER_NUGGET : 0,
                block != null ? MetalUnits2.UNITS_PER_BLOCK : 0,
                baseSmeltTime);
        if (ingot != null) metal.setIngot(ingot);
        if (nugget != null) metal.setNugget(nugget);
        if (block != null) metal.setBlock(block);
        return metal;
    }
}