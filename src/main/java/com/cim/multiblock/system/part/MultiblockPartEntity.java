package com.cim.multiblock.system.part;

import com.cim.block.entity.ModBlockEntities;
import com.cim.multiblock.system.MultiblockBlockEntity;
import com.cim.multiblock.system.MultiblockPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockEntity для частей мультиблока (не контроллеров)
 */
public class MultiblockPartEntity extends MultiblockBlockEntity {

    public MultiblockPartEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MULTIBLOCK_PART.get(), pos, state);
    }

    @Override
    public MultiblockPattern getPattern() {
        // Части не имеют своего паттерна, возвращаем null
        // (или можно получать от контроллера при необходимости)
        return null;
    }

    @Override
    protected Block getPartBlock() {
        return null; // Части не создают других частей
    }

    @Override
    protected void onMultiblockBreak() {
        // Части не инициируют глобальное разрушение
    }

    @Override
    public void tickMultiblock() {
        // Части не тикают самостоятельно
    }

    @Override
    public InteractionResult onMultiblockUse(net.minecraft.world.entity.player.Player player,
                                             net.minecraft.world.InteractionHand hand,
                                             net.minecraft.world.phys.BlockHitResult hit) {
        return InteractionResult.PASS;
    }
}