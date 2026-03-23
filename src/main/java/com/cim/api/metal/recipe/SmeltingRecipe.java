package com.cim.api.metal.recipe;

import com.cim.api.metal.MetalType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

public class SmeltingRecipe {
    // Цвета слотов для визуального различия (индексы 0-3)
    public enum SlotColor {
        RED(0), YELLOW(1), GREEN(2), BLUE(3);

        public final int index;

        SlotColor(int index) {
            this.index = index;
        }

        public static SlotColor byIndex(int index) {
            return switch(index) {
                case 0 -> RED;
                case 1 -> YELLOW;
                case 2 -> GREEN;
                case 3 -> BLUE;
                default -> RED;
            };
        }
    }

    // Тип рецепта
    public enum RecipeType {
        ALLOY,        // Сплав (комбинация металлов)
        SPECIAL,      // Специальный рецепт (уникальные предметы)
        PURIFICATION  // Очистка/переплавка
    }

    private final String id;
    private final RecipeType type;
    private final ItemStack[] inputs; // 4 слота, null = пустой слот
    private final MetalType outputMetal;
    private final int outputAmount; // в мб (111 = 1 слиток)
    private final int minTemperature;
    private final int heatPerTick; // потребление тепла за тик
    private final int totalHeatRequired; // общее время × тепло (но удобнее иметь общее тепло)
    private final String description; // описание для JEI/тултипов

    public SmeltingRecipe(String id, RecipeType type, ItemStack[] inputs,
                          MetalType outputMetal, int outputAmount,
                          int minTemperature, int heatPerTick, int totalHeatRequired,
                          String description) {
        if (inputs.length != 4) {
            throw new IllegalArgumentException("Recipe must have exactly 4 input slots!");
        }
        this.id = id;
        this.type = type;
        this.inputs = inputs;
        this.outputMetal = outputMetal;
        this.outputAmount = outputAmount;
        this.minTemperature = minTemperature;
        this.heatPerTick = heatPerTick;
        this.totalHeatRequired = totalHeatRequired;
        this.description = description;
    }

    public boolean matches(ItemStack[] rowInputs) {
        if (rowInputs.length != 4) return false;

        for (int i = 0; i < 4; i++) {
            ItemStack required = inputs[i];
            ItemStack provided = rowInputs[i];

            if (required == null || required.isEmpty()) {
                // Пустой слот в рецепте - проверяем что и в печи пусто
                if (!provided.isEmpty()) return false;
            } else {
                // Непустой слот - проверяем совпадение
                if (provided.isEmpty()) return false;
                if (!ItemStack.isSameItemSameTags(required, provided)) return false;
                if (provided.getCount() < required.getCount()) return false;
            }
        }
        return true;
    }

    // Проверка частичного совпадения (для подсветки слотов в GUI)
    public boolean matchesPartial(int slot, ItemStack stack) {
        ItemStack required = inputs[slot];
        if (required == null || required.isEmpty()) return stack.isEmpty();
        return ItemStack.isSameItemSameTags(required, stack) && stack.getCount() >= required.getCount();
    }

    public boolean isValidForTemperature(int temperature) {
        return temperature >= minTemperature;
    }

    // Геттеры
    public String getId() { return id; }
    public RecipeType getType() { return type; }
    public ItemStack[] getInputs() { return inputs; }
    public MetalType getOutputMetal() { return outputMetal; }
    public int getOutputAmount() { return outputAmount; }
    public int getMinTemperature() { return minTemperature; }
    public int getHeatPerTick() { return heatPerTick; }
    public int getTotalHeatRequired() { return totalHeatRequired; }
    public String getDescription() { return description; }

    public ItemStack getInputForSlot(SlotColor color) {
        return inputs[color.index];
    }

    public static class Builder {
        private String id;
        private RecipeType type = RecipeType.ALLOY;
        private final ItemStack[] inputs = new ItemStack[4];
        private MetalType output;
        private int amount;
        private int temp;
        private int heatTick;
        private int totalHeat;
        private String desc = "";

        public Builder(String id) {
            this.id = id;
            Arrays.fill(inputs, ItemStack.EMPTY);
        }

        public Builder type(RecipeType type) {
            this.type = type;
            return this;
        }

        public Builder slot(SlotColor color, Item item, int count) {
            this.inputs[color.index] = new ItemStack(item, count);
            return this;
        }

        public Builder slot(SlotColor color, ItemStack stack) {
            this.inputs[color.index] = stack.copy();
            return this;
        }

        public Builder output(MetalType metal, int mb) {
            this.output = metal;
            this.amount = mb;
            return this;
        }

        public Builder output(MetalType metal, int blocks, int ingots, int nuggets) {
            this.output = metal;
            this.amount = blocks * 1000 + ingots * 111 + nuggets * 12;
            return this;
        }

        public Builder temperature(int temp) {
            this.temp = temp;
            return this;
        }

        public Builder heat(int perTick, int total) {
            this.heatTick = perTick;
            this.totalHeat = total;
            return this;
        }

        public Builder description(String desc) {
            this.desc = desc;
            return this;
        }

        public SmeltingRecipe build() {
            Objects.requireNonNull(output, "Output metal cannot be null!");
            return new SmeltingRecipe(id, type, inputs, output, amount, temp, heatTick, totalHeat, desc);
        }
    }
}