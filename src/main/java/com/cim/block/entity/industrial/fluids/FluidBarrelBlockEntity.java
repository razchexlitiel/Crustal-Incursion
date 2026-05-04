package com.cim.block.entity.industrial.fluids;

import com.cim.api.fluids.system.BarrelTier;
import com.cim.api.fluids.system.FluidNetworkManager;
import com.cim.api.fluids.system.FluidPropertyHelper;
import com.cim.block.basic.ModBlocks;
import com.cim.block.basic.industrial.fluids.FluidBarrelBlock;
import com.cim.block.entity.ModBlockEntities;
import com.cim.item.ModItems;
import com.cim.menu.FluidBarrelMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FluidBarrelBlockEntity extends BlockEntity implements MenuProvider {

    public static final int MAX_TRANSFER_RATE = 200;
    public static final int TOTAL_SLOTS = 5;
    public static final int FILL_IN_SLOT = 0;
    public static final int FILL_OUT_SLOT = 1;
    public static final int DRAIN_IN_SLOT = 2;
    public static final int DRAIN_OUT_SLOT = 3;
    public static final int PROTECTOR_SLOT = 4;

    public int mode = 0;
    public String fluidFilter = "none";

    public FluidTank fluidTank;
    public IFluidHandler networkFluidHandler;

    public final ItemStackHandler itemHandler = new ItemStackHandler(TOTAL_SLOTS) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }

        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == FILL_IN_SLOT) return stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).isPresent();
            if (slot == DRAIN_IN_SLOT) {
                if (stack.getItem() instanceof com.cim.item.tools.InfiniteFluidBarrelItem) return true;
                return stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).isPresent();
            }
            if (slot == PROTECTOR_SLOT) {
                Item item = stack.getItem();
                return item == ModItems.PROTECTOR_STEEL.get()
                        || item == ModItems.PROTECTOR_LEAD.get()
                        || item == ModItems.PROTECTOR_TUNGSTEN.get();
            }
            return false;
        }

        @Override public void deserializeNBT(CompoundTag nbt) {
            super.deserializeNBT(nbt);
            if (this.getSlots() != TOTAL_SLOTS) this.setSize(TOTAL_SLOTS);
        }
    };

    private LazyOptional<IFluidHandler> lazyFluidHandler = LazyOptional.empty();
    private LazyOptional<IItemHandler> lazyItemHandler = LazyOptional.empty();

    protected final ContainerData data = new ContainerData() {
        @Override public int get(int index) { return mode; }
        @Override public void set(int index, int value) { mode = value; }
        @Override public int getCount() { return 1; }
    };

    public FluidBarrelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLUID_BARREL_BE.get(), pos, state);
        int cap = getTier().getCapacity();
        this.fluidTank = createTank(cap);
        this.networkFluidHandler = createNetworkHandler();
    }

    private FluidTank createTank(int capacity) {
        return new FluidTank(capacity) {
            @Override protected void onContentsChanged() {
                setChanged();
                if (level != null && !level.isClientSide) {
                    level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
                }
            }
            @Override public boolean isFluidValid(FluidStack stack) {
                if (fluidFilter.equals("none")) return false;
                ResourceLocation loc = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                if (loc != null && !loc.toString().equals(fluidFilter)) return false;
                return super.isFluidValid(stack);
            }
        };
    }

    private IFluidHandler createNetworkHandler() {
        return new IFluidHandler() {
            @Override public int getTanks() { return fluidTank.getTanks(); }
            @Override public @NotNull FluidStack getFluidInTank(int tank) { return fluidTank.getFluidInTank(tank); }
            @Override public int getTankCapacity(int tank) { return fluidTank.getTankCapacity(tank); }
            @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) { return fluidTank.isFluidValid(tank, stack); }

            @Override public int fill(FluidStack resource, FluidAction action) {
                if (mode == 2 || mode == 3) return 0;
                FluidStack toFill = resource.copy();
                toFill.setAmount(Math.min(toFill.getAmount(), MAX_TRANSFER_RATE));
                return fluidTank.fill(toFill, action);
            }
            @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
                if (mode == 1 || mode == 3) return FluidStack.EMPTY;
                int maxDrain = Math.min(resource.getAmount(), MAX_TRANSFER_RATE);
                FluidStack toDrain = resource.copy(); toDrain.setAmount(maxDrain);
                return fluidTank.drain(toDrain, action);
            }
            @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
                if (mode == 1 || mode == 3) return FluidStack.EMPTY;
                return fluidTank.drain(Math.min(maxDrain, MAX_TRANSFER_RATE), action);
            }
        };
    }

    // ==================== ТИР И ЗАЩИТА ====================

    public BarrelTier getTier() {
        Block b = getBlockState().getBlock();
        return (b instanceof FluidBarrelBlock barrel) ? barrel.getTier() : BarrelTier.IRON;
    }

    private int[] getProtectorBonus() {
        ItemStack stack = itemHandler.getStackInSlot(PROTECTOR_SLOT);
        if (stack.isEmpty()) return new int[]{0,0,0};
        Item it = stack.getItem();
        if (it == ModItems.PROTECTOR_STEEL.get()) return new int[]{50, 75, 5};
        if (it == ModItems.PROTECTOR_LEAD.get()) return new int[]{125, 225, 25};
        if (it == ModItems.PROTECTOR_TUNGSTEN.get()) return new int[]{250, 750, 30};
        return new int[]{0,0,0};
    }

    public int getTotalCorrosionResistance() { return getTier().getCorrosionResistance() + getProtectorBonus()[0]; }
    public int getTotalHeatResistance()     { return getTier().getHeatResistance()     + getProtectorBonus()[1]; }
    public int getTotalRadiationResistance(){ return getTier().getRadiationResistance()+ getProtectorBonus()[2]; }

    // ==================== ТИК ====================

    public static void tick(Level level, BlockPos pos, BlockState state, FluidBarrelBlockEntity be) {
        if (level.isClientSide) {
            be.spawnLeakParticles();
            return;
        }
        be.processBuckets();
        be.processLeaking();
        be.checkDamage();
    }

    // --- УТЕЧКА ---
    private void processLeaking() {
        if (!getTier().isLeaking() || fluidTank.isEmpty()) return;
        if (level.getGameTime() % 20 != 0) return; // раз в секунду

        int rate = getTier().getLeakRate();
        if (rate > 0) fluidTank.drain(rate, IFluidHandler.FluidAction.EXECUTE);
    }

    // --- ЧАСТИЦЫ (только клиент) ---
    @OnlyIn(Dist.CLIENT)
    private void spawnLeakParticles() {
        if (!getTier().isLeaking() || fluidTank.isEmpty()) return;

        RandomSource rnd = level.random;
        int chance = (getTier() == BarrelTier.CORRUPTED) ? 3 : 15;
        if (rnd.nextInt(chance) != 0) return;

        FluidStack fluid = fluidTank.getFluid();
        int tint = IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor();
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;

        double x = worldPosition.getX() + 0.1 + rnd.nextDouble() * 0.8;
        double y = worldPosition.getY() + 0.95;
        double z = worldPosition.getZ() + 0.1 + rnd.nextDouble() * 0.8;
        double vy = -0.25 - rnd.nextDouble() * 0.15;

        level.addParticle(new DustParticleOptions(new Vector3f(r, g, b), 1.0f), x, y, z, 0.0, vy, 0.0);
    }

    // --- ПОВРЕЖДЕНИЕ ---
    private void checkDamage() {
        if (fluidTank.isEmpty()) return;

        FluidStack fluid = fluidTank.getFluid();
        int acid = FluidPropertyHelper.getCorrosivity(fluid);
        int temp = FluidPropertyHelper.getTemperature(fluid);
        int rad  = FluidPropertyHelper.getRadioactivity(fluid);

        int cRes = getTotalCorrosionResistance();
        int hRes = getTotalHeatResistance();
        int rRes = getTotalRadiationResistance();

        boolean exceeded = acid > cRes || temp > hRes || rad > rRes;
        boolean doubled  = acid > cRes * 2 || temp > hRes * 2 || rad > rRes * 2;

        Block current = getBlockState().getBlock();
        if (current == ModBlocks.CORRUPTED_BARREL.get()) return;

        Block target = null;
        if (doubled) {
            target = ModBlocks.CORRUPTED_BARREL.get();
        } else if (exceeded && current != ModBlocks.LEAKING_BARREL.get()) {
            target = ModBlocks.LEAKING_BARREL.get();
        }

        if (target != null) {
            CompoundTag tag = saveWithoutMetadata();
            level.setBlock(worldPosition, target.defaultBlockState(), 3);

            BlockEntity newBe = level.getBlockEntity(worldPosition);
            if (newBe instanceof FluidBarrelBlockEntity newBarrel) {
                newBarrel.load(tag);
                // Обрезаем переполнение, если стальная (24к) превратилась в протекающую (16к)
                int cap = newBarrel.getTier().getCapacity();
                if (newBarrel.fluidTank.getFluidAmount() > cap) {
                    newBarrel.fluidTank.getFluid().setAmount(cap);
                }
                newBarrel.setChanged();
            }
        }
    }

    // ==================== ВЁДРА / КРАФТ СЛОТОВ ====================

    private void processBuckets() {
        ItemStack drainIn = itemHandler.getStackInSlot(DRAIN_IN_SLOT);
        if (!drainIn.isEmpty()) {
            if (drainIn.getItem() instanceof com.cim.item.tools.InfiniteFluidBarrelItem) {
                if (!this.fluidFilter.equals("none")) {
                    Fluid filterFluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(this.fluidFilter));
                    if (filterFluid != null && filterFluid != Fluids.EMPTY) {
                        int space = fluidTank.getSpace();
                        if (space > 0) fluidTank.fill(new FluidStack(filterFluid, Math.min(space, MAX_TRANSFER_RATE)), IFluidHandler.FluidAction.EXECUTE);
                    }
                }
            } else {
                var result = FluidUtil.tryEmptyContainer(drainIn, fluidTank, fluidTank.getSpace(), null, true);
                if (result.isSuccess()) {
                    if (insertOrMerge(DRAIN_OUT_SLOT, result.getResult())) drainIn.shrink(1);
                }
            }
        }

        if (fluidTank.getFluidAmount() > 0) {
            ItemStack fillIn = itemHandler.getStackInSlot(FILL_IN_SLOT);
            if (!fillIn.isEmpty()) {
                var result = FluidUtil.tryFillContainer(fillIn, fluidTank, fluidTank.getFluidAmount(), null, true);
                if (result.isSuccess()) {
                    if (insertOrMerge(FILL_OUT_SLOT, result.getResult())) fillIn.shrink(1);
                }
            }
        }
    }

    private boolean insertOrMerge(int slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        ItemStack existing = itemHandler.getStackInSlot(slot);
        if (existing.isEmpty()) {
            itemHandler.setStackInSlot(slot, stack.copy());
            return true;
        } else if (ItemStack.isSameItemSameTags(existing, stack) && existing.getCount() + stack.getCount() <= existing.getMaxStackSize()) {
            existing.grow(stack.getCount());
            return true;
        }
        return false;
    }

    public void setFilter(String newFilter) {
        this.fluidFilter = newFilter;
        if (!newFilter.equals("none") && !fluidTank.isEmpty()) {
            ResourceLocation cur = ForgeRegistries.FLUIDS.getKey(fluidTank.getFluid().getFluid());
            if (cur != null && !cur.toString().equals(newFilter)) fluidTank.setFluid(FluidStack.EMPTY);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            FluidNetworkManager manager = FluidNetworkManager.get((ServerLevel) level);
            manager.removeNode(this.getBlockPos());
            manager.addNode(this.getBlockPos());
        }
    }

    public void changeMode() {
        this.mode = (this.mode + 1) % 4;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    @Override public void onLoad() {
        super.onLoad();
        lazyFluidHandler = LazyOptional.of(() -> networkFluidHandler);
        lazyItemHandler = LazyOptional.of(() -> itemHandler);
        if (level != null && !level.isClientSide) FluidNetworkManager.get((ServerLevel) level).addNode(worldPosition);
    }

    @Override public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) FluidNetworkManager.get((ServerLevel) this.level).removeNode(this.getBlockPos());
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        lazyFluidHandler.invalidate();
        lazyItemHandler.invalidate();
    }

    @Override public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER) return lazyFluidHandler.cast();
        if (cap == ForgeCapabilities.ITEM_HANDLER) return lazyItemHandler.cast();
        return super.getCapability(cap, side);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        fluidTank.readFromNBT(tag);
        itemHandler.deserializeNBT(tag.getCompound("Inventory"));
        mode = tag.getInt("Mode");
        if (tag.contains("FluidFilter")) this.fluidFilter = tag.getString("FluidFilter");
        // защита от переполнения при "даунгрейде" блока
        if (fluidTank.getFluidAmount() > getTier().getCapacity()) {
            fluidTank.getFluid().setAmount(getTier().getCapacity());
        }
    }

    @Override public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        fluidTank.writeToNBT(tag);
        tag.put("Inventory", itemHandler.serializeNBT());
        tag.putInt("Mode", mode);
        tag.putString("FluidFilter", this.fluidFilter);
    }

    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }

    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new FluidBarrelMenu(id, inv, this, this.data);
    }

    @Override public Component getDisplayName() {
        return Component.translatable("block.cim." + getTier().name().toLowerCase() + "_barrel");
    }
}