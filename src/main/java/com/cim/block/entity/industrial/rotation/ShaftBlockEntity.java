package com.cim.block.entity.industrial.rotation;

import com.cim.api.rotation.Rotational;
import com.cim.block.basic.industrial.rotation.ShaftBlock;
import com.cim.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ShaftBlockEntity extends BlockEntity implements Rotational {

    private long speed = 0;

    public ShaftBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHAFT_BE.get(), pos, state);
    }

    @Override
    public long getSpeed() { return speed; }

    @Override
    public long getTorque() {
        // Вал сам по себе не генерирует крутящий момент
        return 0;
    }

    @Override
    public void setSpeed(long speed) {
        if (this.speed != speed) {
            this.speed = speed;
            setChanged();
            // Здесь будет отправка пакета клиенту для обновления Flywheel [cite: 11]
        }
    }

    @Override
    public Direction[] getPropagationDirections() {
        Direction facing = getBlockState().getValue(ShaftBlock.FACING);
        return new Direction[]{facing, facing.getOpposite()};
    }

    @Override
    public long getMaxSpeed() { return 256; } // Можно вынести в конфиг
    @Override
    public long getMaxTorque() { return 1024; }

    @Override
    public boolean isSource() { return false; } // Вал — это просто передатчик
}