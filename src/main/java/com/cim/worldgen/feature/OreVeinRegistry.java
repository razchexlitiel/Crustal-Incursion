package com.cim.worldgen.feature;

import com.cim.block.basic.ModBlocks;
import com.cim.main.CrustalIncursionMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public class OreVeinRegistry {
    public static final List<OreEntry> ORES = new ArrayList<>();

    public static class OreEntry {
        public final String name;
        public final Block block;
        public final int veinSize;      // размер жилы
        public final int minY;          // нижняя граница
        public final int maxY;          // верхняя граница
        public final int countPerChunk; // сколько попыток на чанк

        public final ResourceKey<ConfiguredFeature<?, ?>> configuredKey;
        public final ResourceKey<PlacedFeature> placedKey;
        public final ResourceKey<BiomeModifier> biomeModifierKey;

        public OreEntry(String name, Block block, int veinSize, int minY, int maxY, int countPerChunk) {
            this.name = name;
            this.block = block;
            this.veinSize = veinSize;
            this.minY = minY;
            this.maxY = maxY;
            this.countPerChunk = countPerChunk;

            this.configuredKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.CONFIGURED_FEATURE,
                    new ResourceLocation(CrustalIncursionMod.MOD_ID, "ore_" + name)
            );
            this.placedKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.PLACED_FEATURE,
                    new ResourceLocation(CrustalIncursionMod.MOD_ID, "ore_" + name + "_placed")
            );
            this.biomeModifierKey = ResourceKey.create(
                    ForgeRegistries.Keys.BIOME_MODIFIERS,
                    new ResourceLocation(CrustalIncursionMod.MOD_ID, "add_ore_" + name)
            );
        }
    }

    public static void register(String name, Block block, int veinSize, int minY, int maxY, int countPerChunk) {
        ORES.add(new OreEntry(name, block, veinSize, minY, maxY, countPerChunk));
    }

    static {

        register("bauxite", ModBlocks.BAUXITE.get(), 50, 40, 150, 1);

        register("limestone", ModBlocks.LIMESTONE.get(), 33, -10, 150, 2);

        register("dolomite", ModBlocks.DOLOMITE.get(), 8, -30, 100, 2);
    }
}