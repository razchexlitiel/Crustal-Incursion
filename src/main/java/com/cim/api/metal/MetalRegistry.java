package com.cim.api.metal;

import com.cim.main.CrustalIncursionMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MetalRegistry {
    private static final Map<ResourceLocation, MetalType> METALS = new HashMap<>();

    // Стандартные металлы
    public static MetalType IRON;
    public static MetalType COPPER;
    public static MetalType GOLD;
    public static MetalType LEAD; // если есть в моде
    public static MetalType STEEL; // если есть в моде

    public static void init() {
        IRON = register("iron", 0xFF6B35, 1538, 2862, Items.IRON_INGOT, Items.IRON_NUGGET);
        COPPER = register("copper", 0xB87333, 1085, 2562, Items.COPPER_INGOT, null); // самородка меди нет в ванилле
        GOLD = register("gold", 0xFFD700, 1064, 2970, Items.GOLD_INGOT, Items.GOLD_NUGGET);
    }

    private static MetalType register(String name, int color, int meltingPoint, int boilingPoint,
                                      net.minecraft.world.item.Item ingot, net.minecraft.world.item.Item nugget) {
        ResourceLocation id = new ResourceLocation(CrustalIncursionMod.MOD_ID, name);
        MetalType metal = new MetalType(
                id,
                "metal.cim." + name,
                color,
                meltingPoint,
                boilingPoint,
                ingot,
                nugget
        );
        METALS.put(id, metal);
        return metal;
    }

    public static MetalType get(ResourceLocation id) {
        return METALS.get(id);
    }

    public static MetalType get(String name) {
        return METALS.get(new ResourceLocation(CrustalIncursionMod.MOD_ID, name));
    }

    public static Collection<MetalType> getAllMetals() {
        return METALS.values();
    }

    public static boolean isRegistered(ResourceLocation id) {
        return METALS.containsKey(id);
    }
}