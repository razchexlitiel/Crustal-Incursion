package com.cim.api.metallurgy.system.recipe;


import com.cim.api.metallurgy.system.Metal;
import net.minecraft.world.item.Item;

public record SmeltRecipe(Item input, Metal output, int outputUnits, int minTemp, int heatPerTick, int totalHeat) {}