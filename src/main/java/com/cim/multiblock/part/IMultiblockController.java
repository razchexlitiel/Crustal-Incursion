package com.cim.multiblock.part;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;

public interface IMultiblockController {
    InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit, BlockPos clickedPos);
    void destroyMultiblock();
}