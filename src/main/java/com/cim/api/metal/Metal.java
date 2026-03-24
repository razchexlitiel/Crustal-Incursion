package com.cim.api.metal;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

public class Metal {
    private final ResourceLocation id;
    private final String translationKey;
    private final int color;
    private final int meltingPoint;

    @Nullable private final Item ingot;
    @Nullable private final Item nugget;
    @Nullable private final Block block; // Добавляем блок

    public Metal(ResourceLocation id, int color, int meltingPoint,
                 @Nullable Item ingot, @Nullable Item nugget, @Nullable Block block) {
        this.id = id;
        this.translationKey = "metal." + id.getNamespace() + "." + id.getPath();
        this.color = color;
        this.meltingPoint = meltingPoint;
        this.ingot = ingot;
        this.nugget = nugget;
        this.block = block;
    }

    public ResourceLocation getId() { return id; }
    public String getTranslationKey() { return translationKey; }
    public int getColor() { return color; }
    public int getMeltingPoint() { return meltingPoint; }
    @Nullable public Item getIngot() { return ingot; }
    @Nullable public Item getNugget() { return nugget; }
    @Nullable public Block getBlock() { return block; }

    public boolean hasIngot() { return ingot != null; }
    public boolean hasNugget() { return nugget != null; }
    public boolean hasBlock() { return block != null; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Metal metal)) return false;
        return id.equals(metal.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return id.toString(); }
}