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
import razchexlitiel.cim.block.basic.rotation.DrillHeadBlock;
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
    private final long RECEIVE_SPEED = 1000;

    private boolean isSwitchedOn = false;
    private int shaftsAfterLastPort = 0;
    private int totalChainLength = 0;
    private boolean hasDrillHead = false; // есть ли головка в цепочке
    private long speed = 0;
    private long torque = 0;

    private RotationSource cachedSource;
    private long cacheTimestamp;
    private static final long CACHE_LIFETIME = 10;

    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final LazyOptional<IItemHandler> inventoryOptional = LazyOptional.of(() -> inventory);
    @Nullable
    private BlockPos miningPortPos;

    private final LazyOptional<IEnergyReceiver> energyReceiverOptional = LazyOptional.of(() -> this);
    private final LazyOptional<IEnergyConnector> energyConnectorOptional = LazyOptional.of(() -> this);
    private final LazyOptional<net.minecraftforge.energy.IEnergyStorage> forgeEnergyOptional =
            LazyOptional.of(() -> new LongEnergyWrapper(this, LongEnergyWrapper.BitMode.LOW));

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

    private void updateMiningPortPos(Level level) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        BlockPos backPos = worldPosition.relative(facing.getOpposite());
        BlockEntity be = level.getBlockEntity(backPos);
        if (be instanceof MiningPortBlockEntity) {
            miningPortPos = backPos;
        } else {
            miningPortPos = null;
        }
    }

    @Override
    public Direction[] getPropagationDirections(@Nullable Direction fromDir) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
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
    @Override public IEnergyReceiver.Priority getPriority() { return IEnergyReceiver.Priority.NORMAL; }
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

        if (!be.isSwitchedOn) return;

        // Обновляем информацию о цепочке раз в 10 тиков
        if (level.getGameTime() % 10 == 0) {
            be.updateChainInfo(level);
        }

        // Если головки нет – строим автоматически
        if (!be.hasDrillHead && level.getGameTime() % 10 == 0) {
            be.tryPlaceNext(level);
        }

        // Обновляем порт раз в 20 тиков
        if (level.getGameTime() % 20 == 0) {
            be.updateMiningPortPos(level);
        }
    }

    // ========== Логика цепочки ==========
    private void updateChainInfo(Level level) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        BlockPos currentPos = worldPosition.relative(facing);
        int length = 0;
        boolean foundDrill = false;

        while (length < 25) {
            BlockState state = level.getBlockState(currentPos);
            Block block = state.getBlock();

            if (block instanceof ShaftBlock) {
                if (state.getValue(ShaftBlock.FACING) == facing) {
                    length++;
                    currentPos = currentPos.relative(facing);
                    continue;
                } else {
                    break;
                }
            } else if (block instanceof GearPortBlock) {
                length++;
                currentPos = currentPos.relative(facing);
                continue;
            } else if (block instanceof DrillHeadBlock) {
                if (state.getValue(DrillHeadBlock.FACING) == facing) {
                    length++;
                    foundDrill = true;
                    currentPos = currentPos.relative(facing);
                    continue;
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        this.totalChainLength = length;
        this.hasDrillHead = foundDrill;
        setChanged();
    }

    // ========== Автоматическое размещение (когда нет головки) ==========
    private void tryPlaceNext(Level level) {
        Direction facing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        BlockPos currentPos = worldPosition.relative(facing);

        // 1. Найти конец существующей цепочки (пропускаем уже учтённые блоки)
        int existingLength = 0;
        while (existingLength < 25) {
            BlockState state = level.getBlockState(currentPos);
            Block block = state.getBlock();

            if (block instanceof ShaftBlock) {
                if (state.getValue(ShaftBlock.FACING) == facing) {
                    existingLength++;
                    currentPos = currentPos.relative(facing);
                    continue;
                } else {
                    break;
                }
            } else if (block instanceof GearPortBlock) {
                existingLength++;
                currentPos = currentPos.relative(facing);
                continue;
            } else if (block instanceof DrillHeadBlock) {
                // До головки не должны дойти, т.к. hasDrillHead = false, но на всякий случай
                return;
            } else {
                break;
            }
        }

        // Проверяем лимит
        if (existingLength >= 25) return;

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
        invalidateNeighborCaches();
    }

    // ========== Вызов от головки при её перемещении ==========
    public void placeShaftAt(BlockPos pos, Direction facing) {
        if (level == null || level.isClientSide) return;

        // Проверяем, что позиция находится прямо перед разместителем (по направлению)
        Direction placerFacing = getBlockState().getValue(ShaftPlacerBlock.FACING);
        if (!pos.equals(worldPosition.relative(placerFacing, totalChainLength + 1))) {
            return; // не та позиция
        }

        if (!level.isEmptyBlock(pos) && !level.getBlockState(pos).canBeReplaced()) return;

        int slotIndex = findShaftSlot();
        if (slotIndex == -1) return;

        if (energyStored < ENERGY_PER_SHAFT) return;

        BlockState shaftState = ModBlocks.SHAFT_IRON.get().defaultBlockState()
                .setValue(ShaftBlock.FACING, facing);
        level.setBlock(pos, shaftState, 3);

        energyStored -= ENERGY_PER_SHAFT;
        inventory.extractItem(slotIndex, 1, false);

        shaftsAfterLastPort++;
        totalChainLength++;

        setChanged();
        sync();
        invalidateNeighborCaches();
    }

    private int findShaftSlot() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && (stack.getItem() == ModBlocks.SHAFT_IRON.get().asItem() ||
                    stack.getItem() == ModBlocks.SHAFT_WOODEN.get().asItem())) {
                return i;
            }
        }
        return -1;
    }

    private int findSlotForItem(boolean needPort) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (needPort) {
                if (stack.getItem() == ModBlocks.GEAR_PORT.get().asItem()) {
                    return i;
                }
            } else {
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
    @Nullable public BlockPos getMiningPortPos() { return miningPortPos; }

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
        tag.putBoolean("HasDrillHead", hasDrillHead);
        tag.put("Inventory", inventory.serializeNBT());
        if (miningPortPos != null) tag.putLong("MiningPortPos", miningPortPos.asLong());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energyStored = tag.getLong("Energy");
        isSwitchedOn = tag.getBoolean("SwitchedOn");
        shaftsAfterLastPort = tag.getInt("ShaftsAfterPort");
        totalChainLength = tag.getInt("TotalLength");
        hasDrillHead = tag.getBoolean("HasDrillHead");
        inventory.deserializeNBT(tag.getCompound("Inventory"));
        if (tag.contains("MiningPortPos")) {
            miningPortPos = BlockPos.of(tag.getLong("MiningPortPos"));
        } else {
            miningPortPos = null;
        }
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