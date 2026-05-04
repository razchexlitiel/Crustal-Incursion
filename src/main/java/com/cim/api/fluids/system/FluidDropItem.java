package com.cim.api.fluids.system;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class FluidDropItem extends Item {
    private final Supplier<FluidType> fluidTypeSupplier;

    public FluidDropItem(Supplier<FluidType> fluidTypeSupplier, Properties properties) {
        super(properties);
        this.fluidTypeSupplier = fluidTypeSupplier;
    }

    public FluidType getFluidType() {
        return fluidTypeSupplier.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        FluidType fluidType = getFluidType();
        if (fluidType == null) return;

        if (fluidType instanceof BaseFluidType baseType) {
            int temp = fluidType.getTemperature();
            int acidity = baseType.getAcidity();
            int radiation = baseType.getRadiation();

            tooltip.add(Component.literal(""));
            tooltip.add(Component.literal("Свойства жидкости:").withStyle(ChatFormatting.GRAY));

            // Температура
            String tempStr = temp + " K";
            ChatFormatting tempColor = ChatFormatting.WHITE;
            if (temp > 1000) tempColor = ChatFormatting.RED;
            else if (temp > 373) tempColor = ChatFormatting.GOLD;
            else if (temp < 273) tempColor = ChatFormatting.AQUA;
            tooltip.add(Component.literal("  Температура: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(tempStr).withStyle(tempColor)));

            // Кислотность
            if (acidity > 0) {
                String acidStr = acidity >= 50 ? "Высококоррозионная" : acidity >= 25 ? "Коррозионная" : "Низкокоррозионная";
                ChatFormatting acidColor = acidity >= 50 ? ChatFormatting.DARK_RED : ChatFormatting.YELLOW;
                tooltip.add(Component.literal("  Кислотность: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(acidStr + " (" + acidity + ")").withStyle(acidColor)));
            }

            // Радиация
            if (radiation > 0) {
                String radStr = radiation >= 50 ? "Гамма-излучение" : radiation >= 25 ? "Ионизированная" : "Слабая ионизация";
                ChatFormatting radColor = radiation >= 50 ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN;
                tooltip.add(Component.literal("  Радиация: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(radStr + " (" + radiation + ")").withStyle(radColor)));
            }
        } else {
            tooltip.add(Component.literal("  Температура: " + fluidType.getTemperature() + " K")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    public static List<Component> getFluidPropertiesTooltip(FluidType fluidType) {
        List<Component> tooltip = new ArrayList<>();
        if (fluidType == null) return tooltip;

        if (fluidType instanceof BaseFluidType baseType) {
            int temp = fluidType.getTemperature();
            int acidity = baseType.getAcidity();
            int radiation = baseType.getRadiation();

            tooltip.add(Component.literal("Свойства жидкости:").withStyle(ChatFormatting.GRAY));

            // Температура
            String tempStr = temp + " K";
            ChatFormatting tempColor = ChatFormatting.WHITE;
            if (temp > 1000) tempColor = ChatFormatting.RED;
            else if (temp > 373) tempColor = ChatFormatting.GOLD;
            else if (temp < 273) tempColor = ChatFormatting.AQUA;
            tooltip.add(Component.literal("  Температура: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(tempStr).withStyle(tempColor)));

            // Кислотность... и так далее (весь твой код тултипа)
            if (acidity > 0) {
                String acidStr = acidity >= 50 ? "Высококоррозионная" : acidity >= 25 ? "Коррозионная" : "Низкокоррозионная";
                ChatFormatting acidColor = acidity >= 50 ? ChatFormatting.DARK_RED : ChatFormatting.YELLOW;
                tooltip.add(Component.literal("  Кислотность: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(acidStr + " (" + acidity + ")").withStyle(acidColor)));
            }
            if (radiation > 0) {
                String radStr = radiation >= 50 ? "Гамма-излучение" : radiation >= 25 ? "Ионизированная" : "Слабая ионизация";
                ChatFormatting radColor = radiation >= 50 ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN;
                tooltip.add(Component.literal("  Радиация: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(radStr + " (" + radiation + ")").withStyle(radColor)));
            }
        } else {
            tooltip.add(Component.literal("  Температура: " + fluidType.getTemperature() + " K").withStyle(ChatFormatting.GRAY));
        }
        return tooltip;
    }


    public int getFluidTintColor() {
        FluidType type = getFluidType();
        if (type instanceof BaseFluidType base) {
            return base.getTintColor();
        }
        return 0xFFFFFFFF;
    }
}