package com.cim.block.entity.industrial.rotation;

import com.cim.api.rotation.Rotational;
import com.cim.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.cim.block.basic.industrial.rotation.MotorElectroBlock;

public class MotorElectroBlockEntity extends BlockEntity implements Rotational {

    // 0.5 оборотов в секунду. В нашей системе это будет базовая скорость.
    private final long speedConstant = 1; // Условные единицы скорости
    private final long torqueConstant = 100;
    private boolean reversed = false;

    public MotorElectroBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MOTOR_ELECTRO_BE.get(), pos, state);
    }

    @Override
    public long getSpeed() {
        return reversed ? -speedConstant : speedConstant;
    }

    // Добавь метод переключения
    public void toggleDirection() {
        this.reversed = !this.reversed;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Reversed", this.reversed);
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        this.reversed = tag.getBoolean("Reversed");
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag() {
        net.minecraft.nbt.CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }



    @Override
    public long getTorque() { return torqueConstant; }

    @Override
    public void setSpeed(long speed) {
        // Для мотора скорость задается внутренне, но метод нужен для интерфейса
    }

    @Override
    public boolean isSource() { return true; } // Это источник! [cite: 16]

    @Override
    public Direction[] getPropagationDirections() {
        // Энергия выходит только с той стороны, куда смотрит вал (FACING)
        return new Direction[]{ getBlockState().getValue(MotorElectroBlock.FACING) };
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Когда чанк загружается на сервере, регистрируем блок в сети
        if (level != null && !level.isClientSide) {
            com.cim.api.rotation.KineticNetworkManager.get((net.minecraft.server.level.ServerLevel) level)
                    .updateNetworkAfterPlace(worldPosition);
        }
    }

    @Override
    public long getMaxSpeed() { return 256; }
    @Override
    public long getMaxTorque() { return 1024; }
}
