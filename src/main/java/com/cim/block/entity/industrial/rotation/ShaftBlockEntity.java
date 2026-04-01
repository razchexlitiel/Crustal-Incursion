package com.cim.block.entity.industrial.rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.cim.block.entity.ModBlockEntities;

public class ShaftBlockEntity extends BlockEntity {

    // Скорость будет устанавливаться нашим будущим Менеджером Сетей
    private float speed = 0;

    public ShaftBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHAFT_BE.get(), pos, state);
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
        // Здесь мы будем вызывать обновление инстанса Flywheel
        setChanged();
    }
}
