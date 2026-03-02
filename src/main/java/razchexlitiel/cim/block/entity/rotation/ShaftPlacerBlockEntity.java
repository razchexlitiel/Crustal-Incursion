package razchexlitiel.cim.block.entity.rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import razchexlitiel.cim.api.energy.IEnergyConnector;
import razchexlitiel.cim.api.energy.IEnergyReceiver;
import razchexlitiel.cim.api.energy.LongEnergyWrapper;
import razchexlitiel.cim.api.rotation.RotationNetworkHelper;
import razchexlitiel.cim.api.rotation.RotationSource;
import razchexlitiel.cim.api.rotation.RotationalNode;
import razchexlitiel.cim.block.basic.ModBlocks;
import razchexlitiel.cim.block.basic.rotation.GearPortBlock;
import razchexlitiel.cim.block.basic.rotation.ShaftBlock;
import razchexlitiel.cim.block.basic.rotation.ShaftPlacerBlock;
import razchexlitiel.cim.block.entity.ModBlockEntities;
import razchexlitiel.cim.capability.ModCapabilities;

public class ShaftPlacerBlockEntity extends BlockEntity implements RotationalNode, IEnergyReceiver, IEnergyConnector {
    private long energyStored = 0;
    private final long MAX_ENERGY = 50000;
    private final long ENERGY_PER_SHAFT = 1500;
    private final long ENERGY_PER_PORT = 5000;
    private final long RECEIVE_SPEED = 1000; // как у мотора

    private boolean isSwitchedOn = false;
    private int shaftsAfterLastPort = 0; // количество валов после последнего порта
    private int totalChainLength = 0;     // общая длина цепочки (включая порты)
    private long speed = 0;
    private long torque = 0;

    // Кеш вращения
    private RotationSource cachedSource;
    private long cacheTimestamp;
    private static final long CACHE_LIFETIME = 10;

    // Инвентарь
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final LazyOptional<IItemHandler> inventoryOptional = LazyOptional.of(() -> inventory);

    // Capability энергии
    private final LazyOptional<IEnergyReceiver> energyReceiverOptional = LazyOptional.of(() -> this);
    private final LazyOptional<IEnergyConnector> energyConnectorOptional = LazyOptional.of(() -> this);
    private final LazyOptional<net.minecraftforge.energy.IEnergyStorage> forgeEnergyOptional =
            LazyOptional.of(() -> new LongEnergyWrapper(this, LongEnergyWrapper.BitMode.LOW));

    // ContainerData для GUI
    protected final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> (int) Math.min(energyStored, Integer.MAX_VALUE);
                case 1 -> (int) MAX_ENERGY;
                case 2 -> isSwitchedOn ? 1 : 0;
                case 3 -> shaftsAfterLastPort;
                case 4 -> totalChainLength;
                default -> 0;
            };
        }
        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> energyStored = value;
                case 2 -> isSwitchedOn = value == 1;
                case 3 -> shaftsAfterLastPort = value;
                case 4 -> totalChainLength = value;
            }
        }
        @Override public int getCount() { return 5; }
    };

    public ShaftPlacerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHAFT_PLACER_BE.get(), pos, state);
    }

    // ========== Rotational ==========
    @Override public long getSpeed() { return speed; }
    @Override public long getTorque() { return torque; }
    @Override public void setSpeed(long speed) { this.speed = speed; setChanged(); sync(); }
    @Override public void setTorque(long torque) { this.torque = torque; setChanged(); sync(); }
    @Override public long getMaxSpeed() { return 0; }
    @Override public long getMaxTorque() { return 0; }

    // ========== RotationalNode ==========
    @Override @Nullable public RotationSource getCachedSource() { return cachedSource; }
    @Override public void setCachedSource(@Nullable RotationSource source, long gameTime) {
        this.cachedSource = source;
        this.cacheTimestamp = gameTime;
    }
    @Override public boolean isCacheValid(long currentTime) {
        return cachedSource != null && (currentTime - cacheTimestamp) <= CACHE_LIFETIME;
    }
    @Override public void invalidateCache() {
        if (this.cachedSource != null) {
            this.cachedSource = null;
            if (level != null && !level.isClientSide) {
                invalidateNeighborCaches();
            }
        }
    }
    private void invalidateNeighborCaches() {
        if (level == null || level.isClientSide) return;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            if (level.getBlockEntity(neighborPos) instanceof RotationalNode node) {
                node.invalidateCache();
            }
        }
    }
    @Override
    public Direction[] getPropagationDirections(@Nullable Direction fromDir) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        // Пропускаем вращение только вдоль оси: если запрос пришёл сзади – передаём вперёд, и наоборот.
        if (fromDir == facing.getOpposite()) {
            return new Direction[]{facing};
        } else if (fromDir == facing) {
            return new Direction[]{facing.getOpposite()};
        } else {
            return new Direction[0];
        }
    }

    // ========== IEnergyReceiver / IEnergyConnector ==========
    @Override public long getEnergyStored() { return energyStored; }
    @Override public long getMaxEnergyStored() { return MAX_ENERGY; }
    @Override public void setEnergyStored(long energy) { this.energyStored = Math.max(0, Math.min(energy, MAX_ENERGY)); setChanged(); }
    @Override public long getReceiveSpeed() { return RECEIVE_SPEED; }

    @Override
    public IEnergyReceiver.Priority getPriority() {
        return IEnergyReceiver.Priority.NORMAL; // или другой подходящий приоритет
    }

    @Override public boolean canReceive() { return energyStored < MAX_ENERGY; }
    @Override public long receiveEnergy(long maxReceive, boolean simulate) {
        if (!canReceive()) return 0;
        long received = Math.min(MAX_ENERGY - energyStored, Math.min(RECEIVE_SPEED, maxReceive));
        if (!simulate && received > 0) {
            energyStored += received;
            setChanged();
        }
        return received;
    }

    @Override
    public boolean canConnectEnergy(Direction side) {
        // Запрещаем подключение с лицевой стороны (куда строим)
        return side != getBlockState().getValue(ShaftPlacerBlock.FACING);
    }

    // ========== Capability ==========
    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ModCapabilities.ENERGY_RECEIVER || cap == ModCapabilities.ENERGY_CONNECTOR) {
            if (side == null || canConnectEnergy(side)) {
                if (cap == ModCapabilities.ENERGY_RECEIVER) {
                    return energyReceiverOptional.cast();
                } else {
                    return energyConnectorOptional.cast();
                }
            } else {
                return LazyOptional.empty();
            }
        }
        if (cap == ForgeCapabilities.ENERGY) {
            return forgeEnergyOptional.cast();
        }
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryOptional.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyReceiverOptional.invalidate();
        energyConnectorOptional.invalidate();
        forgeEnergyOptional.invalidate();
        inventoryOptional.invalidate();
    }

    // ========== Тикер ==========
    public static void tick(Level level, BlockPos pos, BlockState state, ShaftPlacerBlockEntity be) {
        if (level.isClientSide) return;

        long currentTime = level.getGameTime();

        // Обновляем кеш вращения (пропускаем вращение)
        if (!be.isCacheValid(currentTime)) {
            RotationSource source = RotationNetworkHelper.findSource(be, null);
            be.setCachedSource(source, currentTime);
        }
        RotationSource src = be.getCachedSource();
        long newSpeed = src != null ? src.speed() : 0;
        long newTorque = src != null ? src.torque() : 0;
        if (be.speed != newSpeed || be.torque != newTorque) {
            be.speed = newSpeed;
            be.torque = newTorque;
            be.setChanged();
            be.sync();
        }

        // Если выключен – не строим
        if (!be.isSwitchedOn) return;

        // Запускаем логику размещения раз в 10 тиков (чтобы не слишком часто)
        if (level.getGameTime() % 10 == 0) {
            be.tryPlaceNext(level);
        }
    }

    // ========== Логика размещения ==========
    private void tryPlaceNext(Level level) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        BlockPos currentPos = worldPosition.relative(facing);

        // 1. Найти конец существующей цепочки
        int existingLength = 0;
        while (existingLength < 25) {
            BlockState state = level.getBlockState(currentPos);
            Block block = state.getBlock();

            if (block instanceof ShaftBlock) {
                // Вал должен смотреть в том же направлении, что и цепочка
                if (state.getValue(ShaftBlock.FACING) == facing) {
                    existingLength++;
                    currentPos = currentPos.relative(facing);
                    continue;
                } else {
                    break; // вал смотрит не туда – цепочка прервана
                }
            } else if (block instanceof GearPortBlock) {
                // Для порта пока не проверяем ориентацию (упрощённо)
                // В идеале нужно убедиться, что порт имеет порты на нужных сторонах,
                // но для автоматически установленных портов это будет верно.
                existingLength++;
                currentPos = currentPos.relative(facing);
                continue;
            } else {
                break; // не вал и не порт
            }
        }

        // Обновляем общую длину цепочки (если изменилась)
        if (totalChainLength != existingLength) {
            totalChainLength = existingLength;
            setChanged();
            sync();
        }

        // Проверяем лимит: не больше 25
        if (totalChainLength >= 25) return;

        // 2. Проверяем, свободно ли место для нового блока
        BlockPos placePos = currentPos;
        if (!level.isEmptyBlock(placePos) && !level.getBlockState(placePos).canBeReplaced()) {
            return; // упёрлись в твёрдый блок
        }

        // 3. Определяем, нужен ли порт (каждые 5 валов после последнего порта)
        boolean needPort = (shaftsAfterLastPort >= 5);

        // 4. Проверяем наличие нужного предмета в инвентаре
        int slotIndex = findSlotForItem(needPort);
        if (slotIndex == -1) return;

        // 5. Проверяем достаточно ли энергии
        long energyCost = needPort ? ENERGY_PER_PORT : ENERGY_PER_SHAFT;
        if (energyStored < energyCost) return;

        // 6. Устанавливаем блок
        if (needPort) {
            BlockState portState = ModBlocks.GEAR_PORT.get().defaultBlockState();
            level.setBlock(placePos, portState, 3);
            // Настраиваем порты: задняя сторона (к разместителю) и передняя (дальше по цепочке)
            BlockEntity be = level.getBlockEntity(placePos);
            if (be instanceof GearPortBlockEntity gear) {
                gear.setupPorts(facing.getOpposite(), facing);
            }
        } else {
            BlockState shaftState = ModBlocks.SHAFT_IRON.get().defaultBlockState()
                    .setValue(ShaftBlock.FACING, facing);
            level.setBlock(placePos, shaftState, 3);
        }

        // 7. Потребляем энергию и предмет
        energyStored -= energyCost;
        inventory.extractItem(slotIndex, 1, false);

        // 8. Обновляем счётчики
        if (needPort) {
            shaftsAfterLastPort = 0;
        } else {
            shaftsAfterLastPort++;
        }
        totalChainLength++;

        setChanged();
        sync();
        // Инвалидируем кеш у соседей, чтобы они пересчитали вращение
        invalidateNeighborCaches();
    }

    // Поиск слота с предметом вала или порта
    private int findSlotForItem(boolean needPort) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (needPort) {
                if (stack.getItem() == ModBlocks.GEAR_PORT.get().asItem()) {
                    return i;
                }
            } else {
                // Проверяем, является ли предмет валом (любой вал)
                if (stack.getItem() == ModBlocks.SHAFT_IRON.get().asItem() ||
                        stack.getItem() == ModBlocks.SHAFT_WOODEN.get().asItem()) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ========== Вспомогательные методы ==========
    public ContainerData getDataAccess() { return data; }
    public IItemHandler getInventory() { return inventory; }

    public void togglePower() {
        this.isSwitchedOn = !this.isSwitchedOn;
        setChanged();
        sync();
        invalidateNeighborCaches();
    }

    // NBT
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("Energy", energyStored);
        tag.putBoolean("SwitchedOn", isSwitchedOn);
        tag.putInt("ShaftsAfterPort", shaftsAfterLastPort);
        tag.putInt("TotalLength", totalChainLength);
        tag.put("Inventory", inventory.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energyStored = tag.getLong("Energy");
        isSwitchedOn = tag.getBoolean("SwitchedOn");
        shaftsAfterLastPort = tag.getInt("ShaftsAfterPort");
        totalChainLength = tag.getInt("TotalLength");
        inventory.deserializeNBT(tag.getCompound("Inventory"));
        cachedSource = null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    private void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}