package com.cim.client.renderer;

import com.cim.entity.mobs.GrenadierZombieEntity;
import com.cim.main.CrustalIncursionMod;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;

import com.cim.entity.mobs.GrenadierZombieEntity;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;

public class GrenadierZombieRenderer extends ZombieRenderer {

    public GrenadierZombieRenderer(EntityRendererProvider.Context context) {
        super(context, ModelLayers.ZOMBIE, ModelLayers.ZOMBIE_INNER_ARMOR,
                ModelLayers.ZOMBIE_OUTER_ARMOR);
    }

}