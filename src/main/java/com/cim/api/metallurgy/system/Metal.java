package com.cim.api.metallurgy.system;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

public class Metal {
    private final ResourceLocation id;
    private final String translationKey;
    private int color;
    private int meltingPoint;
    private final int baseUnits;
    private final int smallUnits;
    private final int blockUnits;
    private final int baseSmeltTime;
    @Nullable private Item ingot;
    @Nullable private Item nugget;
    @Nullable private Block block;

    public Metal(ResourceLocation id, int color, int meltingPoint,
                 int baseUnits, int smallUnits, int blockUnits, int baseSmeltTime) {
        this.id = id;
        this.translationKey = "metal." + id.getNamespace() + "." + id.getPath();
        this.color = color;
        this.meltingPoint = meltingPoint;
        this.baseUnits = baseUnits;
        this.smallUnits = smallUnits;
        this.blockUnits = blockUnits;
        this.baseSmeltTime = baseSmeltTime;
    }

    // Геттеры
    public ResourceLocation getId() { return id; }
    public String getTranslationKey() { return translationKey; }
    public int getColor() { return color; }
    public int getMeltingPoint() { return meltingPoint; }
    public int getBaseUnits() { return baseUnits; }
    public int getSmallUnits() { return smallUnits; }
    public int getBlockUnits() { return blockUnits; }
    public int getBaseSmeltTime() { return baseSmeltTime; }
    @Nullable public Item getIngot() { return ingot; }
    @Nullable public Item getNugget() { return nugget; }
    @Nullable public Block getBlock() { return block; }

    // Сеттеры для связывания с реальными предметами
    public void setIngot(Item ingot) { this.ingot = ingot; }
    public void setNugget(Item nugget) { this.nugget = nugget; }
    public void setBlock(Block block) { this.block = block; }

    public boolean hasIngot() { return ingot != null; }
    public boolean hasNugget() { return nugget != null; }
    public boolean hasBlock() { return block != null; }
}