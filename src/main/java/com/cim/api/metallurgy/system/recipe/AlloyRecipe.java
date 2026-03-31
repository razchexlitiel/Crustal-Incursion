package com.cim.api.metallurgy.system.recipe;

import com.cim.api.metallurgy.system.Metal;
import net.minecraft.world.item.ItemStack;

public class AlloyRecipe {
    private final AlloySlot[] slots;
    private final Metal outputMetal;
    private final int outputUnits;
    private final int totalHeat;
    private final int heatPerTick;

    public AlloyRecipe(AlloySlot[] slots, Metal outputMetal, int outputUnits, int totalHeat, int heatPerTick) {
        this.slots = slots.clone();
        this.outputMetal = outputMetal;
        this.outputUnits = outputUnits;
        this.totalHeat = totalHeat;
        this.heatPerTick = heatPerTick;
    }

    public boolean matches(ItemStack[] stacks) {
        if (stacks.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            AlloySlot req = slots[i];
            ItemStack stack = stacks[i];
            if (req.item() == null || req.count() == 0) {
                if (!stack.isEmpty()) return false;
            } else {
                if (stack.isEmpty()) return false;
                if (stack.getItem() != req.item()) return false;
                if (stack.getCount() < req.count()) return false;
            }
        }
        return true;
    }

    public AlloySlot[] getSlots() { return slots.clone(); }
    public Metal getOutputMetal() { return outputMetal; }
    public int getOutputUnits() { return outputUnits; }
    public int getTotalHeat() { return totalHeat; }
    public int getHeatPerTick() { return heatPerTick; }
}