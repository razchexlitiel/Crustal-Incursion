package com.cim.block.entity.industrial.rotation;

import com.cim.api.rotation.KineticNetwork;
import com.cim.api.rotation.KineticNetworkManager;
import com.cim.api.rotation.Rotational;
import com.cim.api.rotation.ShaftDiameter;
import com.cim.api.rotation.ShaftMaterial;
import com.cim.block.basic.industrial.rotation.BearingBlock;
import com.cim.block.basic.industrial.rotation.ShaftBlock;
import com.cim.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;

public class BearingBlockEntity extends BlockEntity implements Rotational {

    private long speed = 0;
    private long lastSyncedSpeed = 0;

    // Данные о вставленном вале
    private boolean hasShaft = false;
    private ShaftMaterial shaftMaterial = null;
    private ShaftDiameter shaftDiameter = null;

    public BearingBlockEntity(BlockPos pos, BlockState state) {
        // Убедись, что ModBlockEntities.BEARING_BE ссылается на твой DeferredRegister
        super(ModBlockEntities.BEARING_BE.get(), pos, state);
    }

    // --- ЛОГИКА ВАЛА ---

    public boolean hasShaft() { return hasShaft; }
    public ShaftMaterial getShaftMaterial() { return shaftMaterial; }
    public ShaftDiameter getShaftDiameter() { return shaftDiameter; }

    public void insertShaft(ShaftMaterial material, ShaftDiameter diameter) {
        this.hasShaft = true;
        this.shaftMaterial = material;
        this.shaftDiameter = diameter;
        setChanged(); // Больше никакого syncToClient() здесь!
        // Метод level.setBlock() в BearingBlock сам идеально отправит нужный пакет без гонок.
    }

    public void removeShaft() {
        this.hasShaft = false;
        this.shaftMaterial = null;
        this.shaftDiameter = null;
        setChanged(); // И здесь тоже убираем ручной синк
    }

    @Override
    public boolean canConnectMechanically(Direction direction, Rotational neighbor) {
        // 1. Если в самом подшипнике нет вала, он вообще не может ни с чем соединиться по оси
        if (!this.hasShaft()) {
            return false;
        }

        // 2. Если сосед — вал, проверяем строгое соответствие диаметров
        if (neighbor instanceof ShaftBlockEntity shaftBE) {
            if (shaftBE.getBlockState().getBlock() instanceof ShaftBlock shaftBlock) {
                // Сравниваем диаметр вала в подшипнике и диаметр вала-соседа
                return shaftBlock.getDiameter() == this.getShaftDiameter();
            }
        }

        // 3. Если сосед — другой подшипник или мотор
        if (neighbor instanceof BearingBlockEntity otherBearing) {
            // Два подшипника соединятся, только если в обоих одинаковые валы
            return otherBearing.hasShaft() && otherBearing.getShaftDiameter() == this.getShaftDiameter();
        }

        return true;
    }

    // --- КИНЕТИКА (Rotational) ---

    @Override
    public void setSpeed(long speed) {
        if (this.speed != speed) {
            this.speed = speed;
            setChanged();
            if (shouldSyncSpeed()) {
                this.lastSyncedSpeed = this.speed;
                syncToClient();
            }
        }
    }

    private boolean shouldSyncSpeed() {
        if (this.speed == 0 && this.lastSyncedSpeed != 0) return true;
        if (this.speed != 0 && this.lastSyncedSpeed == 0) return true;
        long diff = Math.abs(this.speed - this.lastSyncedSpeed);
        long threshold = Math.max(2, Math.abs(this.lastSyncedSpeed) / 20); // 5% порог
        return diff >= threshold;
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Direction[] getPropagationDirections() {
        // Если вала нет — подшипник ничего не передает!
        if (!hasShaft) {
            return new Direction[0];
        }
        Direction facing = getBlockState().getValue(BearingBlock.FACING);
        return new Direction[]{facing, facing.getOpposite()};
    }

    @Override
    public long getInertiaContribution() {
        long baseInertia = 10; // Масса самого подшипника
        if (hasShaft && shaftMaterial != null && shaftDiameter != null) {
            baseInertia += (long) (shaftMaterial.baseInertia() * shaftDiameter.inertiaMod);
        }
        return baseInertia;
    }

    @Override
    public long getFrictionContribution() {
        return 1; // Подшипник смазан, трение минимальное
    }

    @Override
    public long getMaxTorqueTolerance() { return 10000; } // Каркас крепкий
    @Override
    public long getMaxSpeed() { return 1024; }

    @Override
    public long getMaxTorque() {
        // Если вал вставлен, подшипник держит столько же, сколько этот вал
        if (hasShaft() && getShaftMaterial() != null && getShaftDiameter() != null) {
            return (long) (getShaftMaterial().baseTorque() * getShaftDiameter().torqueMod);
        }
        // Если вала нет, сам по себе каркас подшипника очень крепкий
        return 10000;
    }

    @Override
    public long getSpeed() { return speed; }
    @Override
    public long getVisualSpeed() {
        // 1. Если вал не вставлен, подшипник визуально не должен крутиться
        if (!this.hasShaft) {
            return 0;
        }

        BlockState state = getBlockState();

        // ЗАЩИТА: Если блок превратился в воздух при выгрузке
        if (!state.hasProperty(BearingBlock.FACING)) {
            return 0;
        }

        // 2. Синхронизируем направление вращения с мировой осью мотора и валов
        Direction facing = state.getValue(BearingBlock.FACING);
        if (facing == Direction.SOUTH || facing == Direction.EAST || facing == Direction.UP) {
            return -this.speed;
        }

        return this.speed;
    }
    @Override
    public long getTorque() { return 0; }
    @Override
    public boolean isSource() { return false; }

    // --- СИНХРОНИЗАЦИЯ NBT ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("Speed", this.speed);
        tag.putLong("LastSyncedSpeed", this.lastSyncedSpeed);
        tag.putBoolean("HasShaft", this.hasShaft);

        if (this.hasShaft && this.shaftMaterial != null && this.shaftDiameter != null) {
            tag.putString("ShaftMaterial", this.shaftMaterial.name());
            tag.putString("ShaftDiameter", this.shaftDiameter.name());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.speed = tag.getLong("Speed");
        this.lastSyncedSpeed = tag.getLong("LastSyncedSpeed");
        this.hasShaft = tag.getBoolean("HasShaft");

        if (this.hasShaft) {
            String matName = tag.getString("ShaftMaterial");
            String diaName = tag.getString("ShaftDiameter");

            // Восстанавливаем материал по имени
            this.shaftMaterial = switch (matName) {
                case "duralumin" -> ShaftMaterial.DURALUMIN;
                case "steel" -> ShaftMaterial.STEEL;
                case "titanium" -> ShaftMaterial.TITANIUM;
                case "tungsten_carbide" -> ShaftMaterial.TUNGSTEN_CARBIDE;
                default -> ShaftMaterial.IRON;
            };

            // Восстанавливаем диаметр
            try {
                this.shaftDiameter = ShaftDiameter.valueOf(diaName);
            } catch (IllegalArgumentException e) {
                this.shaftDiameter = ShaftDiameter.MEDIUM; // Fallback
            }
        }
    }

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

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            KineticNetwork net = KineticNetworkManager.get((ServerLevel) level).getNetworkFor(worldPosition);
            if (net != null) {
                this.speed = net.getSpeed();
                this.lastSyncedSpeed = this.speed;
                syncToClient();
                net.requestRecalculation();
            }
        }
    }

    @Override
    public void handleUpdateTag(net.minecraft.nbt.CompoundTag tag) {
        super.handleUpdateTag(tag);
        if (level != null && level.isClientSide) {
            // Коротко и ясно, IDE это съест:
            Minecraft.getInstance().execute(() -> {
                if (!this.isRemoved()) {
                    requestModelDataUpdate();
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
                }
            });
        }
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        super.onDataPacket(net, pkt);
        if (level != null && level.isClientSide) {
            Minecraft.getInstance().execute(() -> {
                if (!this.isRemoved()) {
                    requestModelDataUpdate();
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
                }
            });
        }
    }
}
