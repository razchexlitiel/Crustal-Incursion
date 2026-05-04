package com.cim.api.fluids.system;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidType;

import java.util.function.Consumer;

public class BaseFluidType extends FluidType {
    private final ResourceLocation stillTexture;
    private final ResourceLocation flowingTexture;
    private final ResourceLocation guiTexture;
    private final int tintColor;
    private final int acidity;
    private final int radiation;

    public BaseFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowingTexture,
                         ResourceLocation guiTexture, int tintColor, int acidity, int radiation) {
        super(properties);
        this.stillTexture = stillTexture;
        this.flowingTexture = flowingTexture;
        this.guiTexture = guiTexture;
        this.tintColor = tintColor;
        this.acidity = acidity;
        this.radiation = radiation;
    }

    // Forge 1.20.1 — initializeClient для настройки клиентских пропертей
    @Override
    public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
        consumer.accept(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return stillTexture;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return flowingTexture;
            }

            @Override
            public int getTintColor() {
                return tintColor;
            }

            public ResourceLocation getRenderOverlayTexture(net.minecraft.world.entity.Entity viewer) {
                return null;
            }
        });
    }

    // === ГЕТТЕРЫ ДЛЯ МОДА ===
    public int getAcidity() { return acidity; }
    public int getRadiation() { return radiation; }
    public ResourceLocation getGuiTexture() { return guiTexture; }
    public int getTintColor() { return tintColor; }
    public ResourceLocation getStillTexture() { return stillTexture; }
    public ResourceLocation getFlowingTexture() { return flowingTexture; }
}