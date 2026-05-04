package com.cim.api.fluids.system;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fluids.FluidStack;

public class FluidPropertyHelper {
    private static final String TAG_CORROSIVITY = "Corrosivity";
    private static final String TAG_RADIOACTIVITY = "Radioactivity";
    private static final String TAG_TEMPERATURE = "Temperature";

    // Установка свойств с валидацией
    public static FluidStack setProperties(FluidStack stack, int corrosivity, int radioactivity, int temperature) {
        if (stack.isEmpty()) return stack;

        CompoundTag nbt = stack.getOrCreateTag();
        nbt.putInt(TAG_CORROSIVITY, Math.max(0, corrosivity));       // От 0 и выше
        nbt.putInt(TAG_RADIOACTIVITY, Math.max(0, radioactivity));   // От 0 и выше
        nbt.putInt(TAG_TEMPERATURE, temperature);                    // Температура может быть любой
        return stack;
    }

    // Чтение свойств
    public static int getCorrosivity(FluidStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_CORROSIVITY) : 0;
    }

    public static int getRadioactivity(FluidStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_RADIOACTIVITY) : 0;
    }

    public static int getTemperature(FluidStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_TEMPERATURE) : stack.getFluid().getFluidType().getTemperature();
    }


    public static FluidStack createSulfuricAcid(int amount) {
        // Серная кислота: 600 коррозии, комнатная температура
        return setProperties(new FluidStack(com.cim.api.fluids.ModFluids.SULFURIC_ACID_SOURCE.get(), amount), 600, 0, 300);
    }

    public static FluidStack createSteam(int amount) {
        // Пар: 373 К, безопасен
        return setProperties(new FluidStack(com.cim.api.fluids.ModFluids.STEAM_SOURCE.get(), amount), 0, 0, 373);
    }
}