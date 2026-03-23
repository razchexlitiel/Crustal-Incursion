package com.cim.api.metal;

import com.cim.main.CrustalIncursionMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MetalRegistry {
    private static final Map<ResourceLocation, MetalType> METALS = new HashMap<>();

    public static MetalType IRON;
    public static MetalType COPPER;
    public static MetalType GOLD;
    public static MetalType STEEL;
    public static MetalType BRONZE;
    public static MetalType ELECTRUM;
    public static MetalType CAST_IRON; // Чугун

    public static void init() {
        IRON = register("iron", 0xFF6B35, 1538, 2862, Items.IRON_INGOT, Items.IRON_NUGGET);
        COPPER = register("copper", 0xB87333, 1085, 2562, Items.COPPER_INGOT, null);
        GOLD = register("gold", 0xFFD700, 1064, 2970, Items.GOLD_INGOT, Items.GOLD_NUGGET);

        // Новые металлы только для сплавов (верхний ряд)
        STEEL = register("steel", 0x71797E, 1370, 2500, Items.IRON_INGOT, Items.IRON_NUGGET); // используем железные предметы как placeholder
        BRONZE = register("bronze", 0xCD7F32, 950, 2300, Items.BRICK, null); // placeholder
        ELECTRUM = register("electrum", 0xD4AF37, 1020, 2400, Items.GOLD_INGOT, Items.GOLD_NUGGET);
        CAST_IRON = register("cast_iron", 0x434B4D, 1200, 2600, Items.IRON_BLOCK, null);
    }

    private static MetalType register(String name, int color, int meltingPoint, int boilingPoint,
                                      net.minecraft.world.item.Item ingot, net.minecraft.world.item.Item nugget) {
        ResourceLocation id = new ResourceLocation(CrustalIncursionMod.MOD_ID, name);
        MetalType metal = new MetalType(id, "metal.cim." + name, color, meltingPoint, boilingPoint, ingot, nugget);
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