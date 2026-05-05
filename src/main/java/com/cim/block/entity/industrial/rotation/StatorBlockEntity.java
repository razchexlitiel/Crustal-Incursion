package com.cim.block.entity.industrial.rotation;

import com.cim.api.energy.IEnergyConnector;
import com.cim.api.energy.IEnergyProvider;
import com.cim.api.rotation.KineticNetworkManager;
import com.cim.block.basic.industrial.rotation.StatorBlock;
import com.cim.block.entity.ModBlockEntities;
import com.cim.capability.ModCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StatorBlockEntity extends BlockEntity implements IEnergyProvider, IEnergyConnector {
    public static final long MAX_ENERGY = 10000;
    public static final long MAX_EXTRACT = 2000;

    private long energyStored = 0;
    private boolean wasFull = false;

    private final LazyOptional<IEnergyProvider> providerCap = LazyOptional.of(() -> this);
    private final LazyOptional<IEnergyConnector> connectorCap = LazyOptional.of(() -> this);

    public StatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATOR_BE.get(), pos, state);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (canConnectEnergy(side)) {
            if (cap == ModCapabilities.ENERGY_PROVIDER) return providerCap.cast();
            if (cap == ModCapabilities.ENERGY_CONNECTOR) return connectorCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        providerCap.invalidate();
        connectorCap.invalidate();
    }

    // IEnergyConnector
    @Override
    public boolean canConnectEnergy(Direction side) {
        BlockState state = getBlockState();
        if (!state.hasProperty(StatorBlock.FACING)) return true;
        return side != state.getValue(StatorBlock.FACING);
    }

    // IEnergyProvider
    @Override
    public long getEnergyStored() { return energyStored; }

    @Override
    public long getMaxEnergyStored() { return MAX_ENERGY; }

    @Override
    public void setEnergyStored(long energy) {
        this.energyStored = Math.max(0, Math.min(energy, MAX_ENERGY));
        checkFullStateChange();
    }

    @Override
    public long getProvideSpeed() { return MAX_EXTRACT; }

    @Override
    public long extractEnergy(long maxExtract, boolean simulate) {
        long toExtract = Math.min(energyStored, Math.min(maxExtract, MAX_EXTRACT));
        if (!simulate) {
            energyStored -= toExtract;
            setChanged();
            checkFullStateChange();
        }
        return toExtract;
    }

    @Override
    public boolean canExtract() { return energyStored > 0; }

    private void checkFullStateChange() {
        boolean isFull = energyStored >= MAX_ENERGY;
        if (isFull != wasFull) {
            wasFull = isFull;
            requestKineticRecalculation();
        }
    }

    private void requestKineticRecalculation() {
        if (level instanceof ServerLevel serverLevel) {
            Direction facing = getBlockState().getValue(StatorBlock.FACING);
            BlockPos shaftPos = worldPosition.relative(facing);
            var net = KineticNetworkManager.get(serverLevel).getNetworkFor(shaftPos);
            if (net != null) {
                net.requestRecalculation();
            }
        }
    }

    public static <T extends BlockEntity> BlockEntityTicker<T> createTicker() {
        return (level, pos, state, be) -> {
            if (!level.isClientSide && be instanceof StatorBlockEntity stator) {
                stator.tick();
            }
        };
    }

    private void tick() {
        if (level == null || level.isClientSide) return;

        Direction facing = getBlockState().getValue(StatorBlock.FACING);
        BlockPos shaftPos = worldPosition.relative(facing);
        
        if (level.getBlockEntity(shaftPos) instanceof ShaftBlockEntity shaft) {
            if (shaft.hasRotor()) {
                long speed = Math.abs(shaft.getSpeed());
                if (speed > 0 && energyStored < MAX_ENERGY) {
                    long generated = speed * 2;
                    energyStored = Math.min(MAX_ENERGY, energyStored + generated);
                    setChanged();
                    checkFullStateChange();
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("EnergyStored", energyStored);
        tag.putBoolean("WasFull", wasFull);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energyStored = tag.getLong("EnergyStored");
        wasFull = tag.getBoolean("WasFull");
    }
}
