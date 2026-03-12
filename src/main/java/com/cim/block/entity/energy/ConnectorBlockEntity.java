package com.cim.block.entity.energy;

import com.cim.api.energy.IEnergyConnector;
import com.cim.block.basic.energy.ConnectorBlock;
import com.cim.block.entity.ModBlockEntities;
import com.cim.capability.ModCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class ConnectorBlockEntity extends BlockEntity implements IEnergyConnector {

    private final LazyOptional<IEnergyConnector> connectorCap = LazyOptional.of(() -> this);

    @Nullable
    private BlockPos connectedTo = null;

    public ConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONNECTOR_BE.get(), pos, state);
    }

    // ========== P2P СОЕДИНЕНИЕ ==========

    public boolean isConnected() {
        return connectedTo != null;
    }

    @Nullable
    public BlockPos getConnectedTo() {
        return connectedTo;
    }

    // ========== P2P СОЕДИНЕНИЕ ==========

    public void connectTo(BlockPos other) {
        this.connectedTo = other;
        setChanged();
        syncToClient();

        // === ИСПРАВЛЕНИЕ ===
        // Уведомляем менеджер сетей, что появилась новая связь.
        // Удаляем и заново добавляем узел. Менеджер увидит P2P-связь
        // и автоматически объединит две раздельные сети в одну.
        if (level != null && !level.isClientSide) {
            com.cim.api.energy.EnergyNetworkManager manager = com.cim.api.energy.EnergyNetworkManager.get((net.minecraft.server.level.ServerLevel) level);
            manager.removeNode(worldPosition);
            manager.addNode(worldPosition);
        }
    }

    public void disconnect() {
        BlockPos oldConnection = this.connectedTo;
        this.connectedTo = null;
        setChanged();
        syncToClient();

        // === ИСПРАВЛЕНИЕ ===
        // Уведомляем менеджер о разрыве провода.
        if (level != null && !level.isClientSide && oldConnection != null) {
            com.cim.api.energy.EnergyNetworkManager manager = com.cim.api.energy.EnergyNetworkManager.get((net.minecraft.server.level.ServerLevel) level);

            // "Передергиваем" текущий коннектор. Из-за этого сеть вызовет verifyConnectivity()
            // и, если связь реально прервалась, аккуратно разделит одну сеть на две.
            manager.removeNode(worldPosition);
            manager.addNode(worldPosition);

            // На всякий случай обновляем и второй конец оборванного провода
            manager.removeNode(oldConnection);
            manager.addNode(oldConnection);
        }
    }

    public void onRemoved() {
        if (connectedTo != null && level != null && !level.isClientSide) {
            BlockEntity other = level.getBlockEntity(connectedTo);
            if (other instanceof ConnectorBlockEntity otherConn) {
                otherConn.disconnect();
            }
            connectedTo = null;
        }
    }

    // ========== ТОЧКА КРЕПЛЕНИЯ ПРОВОДА ==========

    public Vec3 getWireAttachmentPoint() {
        Direction facing = getBlockState().getValue(ConnectorBlock.FACING);

        // Математически идеальный центр верхушки коннектора (6/16 от основания)
        double lx = 0.5 - 0.125 * facing.getStepX();
        double ly = 0.5 - 0.125 * facing.getStepY();
        double lz = 0.5 - 0.125 * facing.getStepZ();

        return new Vec3(
                worldPosition.getX() + lx,
                worldPosition.getY() + ly,
                worldPosition.getZ() + lz
        );
    }

    // ========== IEnergyConnector ==========

    @Override
    public boolean canConnectEnergy(Direction side) {
        Direction facing = getBlockState().getValue(ConnectorBlock.FACING);
        return side == facing.getOpposite();
    }

    // ========== Capabilities ==========

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ModCapabilities.ENERGY_CONNECTOR) {
            return connectorCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        connectorCap.invalidate();
    }

    // ========== NBT ==========

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (connectedTo != null) {
            tag.put("ConnectedTo", NbtUtils.writeBlockPos(connectedTo));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("ConnectedTo")) {
            connectedTo = NbtUtils.readBlockPos(tag.getCompound("ConnectedTo"));
        } else {
            connectedTo = null;
        }
    }

    // ========== Синхронизация с клиентом ==========

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            com.cim.api.energy.EnergyNetworkManager.get(
                    (net.minecraft.server.level.ServerLevel) level).addNode(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            com.cim.api.energy.EnergyNetworkManager.get(
                    (net.minecraft.server.level.ServerLevel) level).removeNode(worldPosition);
        }
    }

    // ========== ИСПРАВЛЕНИЕ ИСЧЕЗНОВЕНИЯ ПРОВОДА ==========

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        if (connectedTo != null) {
            // Создаем огромную невидимую коробку рендера, которая охватывает оба коннектора.
            // Теперь игра не перестанет рендерить провод, пока мы смотрим хотя бы на один из блоков
            // или на сам провод между ними.
            return new net.minecraft.world.phys.AABB(worldPosition, connectedTo).inflate(1.0);
        }

        // Если провода нет, возвращаем стандартный размер 1x1x1
        return super.getRenderBoundingBox();
    }
}