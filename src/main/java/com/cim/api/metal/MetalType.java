package com.cim.api.metal;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

public class MetalType {
    private final ResourceLocation id;
    private final String translationKey;
    private final int color;
    private final int meltingPoint; // Температура плавления
    private final int boilingPoint; // Температура кипения (опционально)
    private final Item ingot; // Слиток для обратной конвертации
    private final Item nugget; // Самородок
    private final Fluid fakeFluid; // Фейковая жидкость для совместимости (может быть null)

    public MetalType(ResourceLocation id, String translationKey, int color, int meltingPoint,
                     int boilingPoint, Item ingot, Item nugget) {
        this.id = id;
        this.translationKey = translationKey;
        this.color = color;
        this.meltingPoint = meltingPoint;
        this.boilingPoint = boilingPoint;
        this.ingot = ingot;
        this.nugget = nugget;
        this.fakeFluid = null;
    }

    public ResourceLocation getId() { return id; }
    public String getTranslationKey() { return translationKey; }
    public int getColor() { return color; }
    public int getMeltingPoint() { return meltingPoint; }
    public int getBoilingPoint() { return boilingPoint; }
    public Item getIngot() { return ingot; }
    public Item getNugget() { return nugget; }

    public String getDisplayName() {
        return net.minecraft.network.chat.Component.translatable(translationKey).getString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetalType metalType = (MetalType) o;
        return Objects.equals(id, metalType.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id.toString();
    }

    // Конвертация количества металла в слитки/самородки
    public static class MetalAmount {
        public final int blocks;
        public final int ingots;
        public final int nuggets;
        public final int mb; // остаток в мб (111mb = 1 слиток, 12mb = 1 самородок в твоей системе)

        public MetalAmount(int totalMb) {
            this.blocks = totalMb / 1000;
            int rem = totalMb % 1000;
            this.ingots = rem / 111;
            this.nuggets = (rem % 111) / 12;
            this.mb = totalMb;
        }
    }
}