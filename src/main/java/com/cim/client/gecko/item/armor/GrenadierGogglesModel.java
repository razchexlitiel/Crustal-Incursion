package com.cim.client.gecko.item.armor;

import com.cim.item.armor.GrenadierGogglesItem;
import com.cim.main.CrustalIncursionMod;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class GrenadierGogglesModel extends GeoModel<GrenadierGogglesItem> {
    @Override
    public ResourceLocation getModelResource(GrenadierGogglesItem animatable) {
        return new ResourceLocation(CrustalIncursionMod.MOD_ID, "geo/grenadier_goggles.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(GrenadierGogglesItem animatable) {
        return new ResourceLocation(CrustalIncursionMod.MOD_ID, "textures/armor/grenadier_goggles.png");
    }

    @Override
    public ResourceLocation getAnimationResource(GrenadierGogglesItem animatable) {
        return new ResourceLocation(CrustalIncursionMod.MOD_ID, "animations/grenadier_goggles.animation.json");
    }

    @Override
    public RenderType getRenderType(GrenadierGogglesItem animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }
}