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
        tooltip.addAll(getFluidPropertiesTooltip(getFluidType()));
    }

    public static List<Component> getFluidPropertiesTooltip(FluidType fluidType) {
        List<Component> tooltip = new ArrayList<>();
        if (fluidType == null) return tooltip;

        tooltip.add(Component.literal("Свойства жидкости:").withStyle(ChatFormatting.GRAY));

        if (fluidType instanceof BaseFluidType baseType) {
            int temp = fluidType.getTemperature();
            int acidity = baseType.getAcidity();
            int radiation = baseType.getRadiation();

            // --- ТЕМПЕРАТУРА ---
            ChatFormatting tempColor = ChatFormatting.WHITE;
            String tempDesc = "Обычная";
            if (temp >= 2000) { tempColor = ChatFormatting.DARK_RED; tempDesc = "Критическая"; }
            else if (temp >= 1000) { tempColor = ChatFormatting.RED; tempDesc = "Экстремальная"; }
            else if (temp >= 500) { tempColor = ChatFormatting.GOLD; tempDesc = "Высокая"; }
            else if (temp > 373) { tempColor = ChatFormatting.YELLOW; tempDesc = "Горячая"; }
            else if (temp < 273) { tempColor = ChatFormatting.AQUA; tempDesc = "Низкая"; }

            tooltip.add(Component.literal("  Температура: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(temp + " K (" + tempDesc + ")").withStyle(tempColor)));

            // --- КИСЛОТНОСТЬ ---
            if (acidity > 0) {
                ChatFormatting acidColor = ChatFormatting.YELLOW;
                String acidDesc = "Следы";
                if (acidity >= 1500) { acidColor = ChatFormatting.DARK_RED; acidDesc = "Экстремальная коррозия"; }
                else if (acidity >= 1000) { acidColor = ChatFormatting.RED; acidDesc = "Высококоррозионная"; }
                else if (acidity >= 500) { acidColor = ChatFormatting.YELLOW; acidDesc = "Коррозионная"; }
                else if (acidity >= 100) { acidColor = ChatFormatting.GOLD; acidDesc = "Слабокоррозионная"; }

                tooltip.add(Component.literal("  Кислотность: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(acidity + " (" + acidDesc + ")").withStyle(acidColor)));
            }

            // --- РАДИАЦИЯ ---
            if (radiation > 0) {
                ChatFormatting radColor = ChatFormatting.GREEN;
                String radDesc = "Следы";
                if (radiation >= 1500) { radColor = ChatFormatting.DARK_RED; radDesc = "Смертельная"; }
                else if (radiation >= 1000) { radColor = ChatFormatting.DARK_GREEN; radDesc = "Гамма-излучение"; }
                else if (radiation >= 500) { radColor = ChatFormatting.GREEN; radDesc = "Ионизированная"; }
                else if (radiation >= 100) { radColor = ChatFormatting.DARK_AQUA; radDesc = "Слабая ионизация"; }

                tooltip.add(Component.literal("  Радиация: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(radiation + " (" + radDesc + ")").withStyle(radColor)));
            }
        } else {
            tooltip.add(Component.literal("  Температура: " + fluidType.getTemperature() + " K")
                    .withStyle(ChatFormatting.GRAY));
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