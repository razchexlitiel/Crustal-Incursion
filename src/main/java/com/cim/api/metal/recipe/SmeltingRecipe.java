package com.cim.api.metal.recipe;

import com.cim.api.metal.Metal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

public class SmeltingRecipe {
    private final String id;
    private final Slot[] inputs; // 4 слота
    private final Metal output;
    private final int outputMb;
    private final int minTemp;
    private final int heatPerTick;
    private final int totalHeat;
    private final String description;

    public SmeltingRecipe(String id, Slot[] inputs, Metal output, int outputMb,
                          int minTemp, int heatPerTick, int totalHeat, String description) {
        if (inputs.length != 4) throw new IllegalArgumentException("Need 4 slots!");
        this.id = id;
        this.inputs = Arrays.copyOf(inputs, 4);
        this.output = output;
        this.outputMb = outputMb;
        this.minTemp = minTemp;
        this.heatPerTick = heatPerTick;
        this.totalHeat = totalHeat;
        this.description = description;
    }

    public boolean matches(ItemStack[] provided) {
        if (provided.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            Slot required = inputs[i];
            ItemStack stack = provided[i];

            if (required.isEmpty()) {
                if (!stack.isEmpty()) return false;
            } else {
                if (stack.isEmpty()) return false;
                if (stack.getItem() != required.item()) return false;
                if (stack.getCount() < required.count()) return false;
            }
        }
        return true;
    }

    // Геттеры
    public String getId() { return id; }
    public Metal getOutput() { return output; }
    public int getOutputMb() { return outputMb; }
    public int getMinTemp() { return minTemp; }
    public int getHeatPerTick() { return heatPerTick; }
    public int getTotalHeat() { return totalHeat; }
    public String getDescription() { return description; }

    public Slot getSlot(int index) { return inputs[index]; }

    // Упрощённые слоты вместо enum
    public record Slot(Item item, int count) {
        public static final Slot EMPTY = new Slot(null, 0);
        public boolean isEmpty() { return item == null || count == 0; }
    }

    // Константы для индексов (вместо enum)
    public static final class SlotPos {
        public static final int RED = 0, YELLOW = 1, GREEN = 2, BLUE = 3;
    }
}