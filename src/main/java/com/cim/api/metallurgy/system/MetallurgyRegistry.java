package com.cim.api.metallurgy.system;



import com.cim.api.metallurgy.system.recipe.AlloyRecipe;
import com.cim.api.metallurgy.system.recipe.SmeltRecipe;
import com.cim.main.CrustalIncursionMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.*;

public class MetallurgyRegistry {
    private static final Map<ResourceLocation, Metal> METALS = new LinkedHashMap<>();
    private static final Map<Item, SmeltRecipe> SMELT_RECIPES = new HashMap<>();
    private static final List<AlloyRecipe> ALLOY_RECIPES = new ArrayList<>();

    public static final int DEFAULT_HEAT_PER_TICK = 10;

    public static Metal registerMetal(String name, int color, int meltingPoint,
                                      int baseUnits, int smallUnits, int blockUnits,
                                      int baseSmeltTimeTicks) {
        ResourceLocation id = new ResourceLocation(CrustalIncursionMod.MOD_ID, name);
        Metal metal = new Metal(id, color, meltingPoint, baseUnits, smallUnits, blockUnits);
        METALS.put(id, metal);

        // Автоматически генерируем рецепты, если позже будут привязаны предметы
        // Но сами рецепты будут добавлены позже, когда будут известны ingot/nugget/block
        return metal;
    }

    public static void addSmeltRecipe(Item input, Metal output, int outputUnits, int timeTicks) {
        int totalHeat = DEFAULT_HEAT_PER_TICK * timeTicks;
        SmeltRecipe recipe = new SmeltRecipe(input, output, outputUnits, output.getMeltingPoint(),
                DEFAULT_HEAT_PER_TICK, totalHeat);
        SMELT_RECIPES.put(input, recipe);
    }

    public static void addAlloyRecipe(AlloyRecipe recipe) {
        ALLOY_RECIPES.add(recipe);
    }

    public static SmeltRecipe getSmeltRecipe(Item input) {
        return SMELT_RECIPES.get(input);
    }

    public static List<AlloyRecipe> getAllAlloyRecipes() {
        return Collections.unmodifiableList(ALLOY_RECIPES);
    }

    public static Optional<Metal> get(ResourceLocation id) {
        return Optional.ofNullable(METALS.get(id));
    }

    public static Collection<Metal> getAllMetals() {
        return Collections.unmodifiableCollection(METALS.values());
    }
}