package com.cim.multiblock.industrial;

import com.cim.block.basic.ModBlocks;
import com.cim.block.entity.ModBlockEntities;
import com.cim.item.ModItems;
import com.cim.menu.HeaterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;

import javax.annotation.Nullable;

public class HeaterBlockEntity extends BlockEntity implements MenuProvider {
    // Инвентарь: 0 - вход (топливо), 1 - выход (зола)
    private final ItemStackHandler inventory = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == 0) return isFuel(stack);
            return false; // В выход можно только извлекать
        }
    };

    // Данные для GUI
    private final SimpleContainerData data = new SimpleContainerData(4);
    public static final int DATA_TEMP = 0;
    public static final int DATA_BURN_TIME = 1;
    public static final int DATA_TOTAL_BURN_TIME = 2;
    public static final int DATA_IS_BURNING = 3;

    public static final int MAX_TEMP = 1600;

    // {heatPerTick, burnTicks}
    private static final int[][] TIER_STATS = {
            {1, 100},   // 0: 1°C/тик (было 5), 5 сек
            {2, 150},   // 1: 2°C/тик (было 8), 7.5 сек
            {3, 400},   // 2: 3°C/тик (было 12), 20 сек, +зола
            {4, 600},   // 3: 4°C/тик (было 20), 30 сек, +зола
            {6, 800},   // 4: 6°C/тик (было 30), 40 сек, +зола
            {10, 1200}  // 5: 10°C/тик (было 50), 60 сек, +зола
    };

    private int temperature = 0;
    private int burnTime = 0;
    private int totalBurnTime = 0;
    private int fuelTier = 0;

    public HeaterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEATER_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, HeaterBlockEntity be) {
        boolean changed = false;

        // === ОХЛАЖДЕНИЕ С ЕСТЕСТВЕННЫМИ КОЛЕБАНИЯМИ ===
        int baseCooling = (be.temperature * be.temperature) / 512000;

        // Добавляем "термический шум": случайное отклонение -2..+2 градуса
        // Колебания заметны только при высоких температурах (>200°C),
        // чтобы не мешать начальному разогреву
        int thermalNoise = 0;
        if (be.temperature > 200 && baseCooling > 0) {
            thermalNoise = level.random.nextInt(5) - 2; // -2, -1, 0, +1, +2
        }

        int cooling = Math.max(0, baseCooling + thermalNoise);

        if (cooling > 0 && be.temperature > 0) {
            be.temperature = Math.max(0, be.temperature - cooling);
            changed = true;
        }

        // === НАГРЕВ ===
        if (be.burnTime > 0) {
            be.burnTime--;
            int heatPerTick = TIER_STATS[be.fuelTier][0];

            // Нагреваемся (с учетом колебаний охлаждения выше)
            be.temperature = Math.min(MAX_TEMP, be.temperature + heatPerTick);

            // Выдача золы при сгорании высоких тиров
            if (be.burnTime == 0 && be.fuelTier >= 2) {
                ItemStack ash = new ItemStack(ModItems.FUEL_ASH.get(), 1); // Явно указываем 1 штуку
                ItemStack remaining = be.inventory.insertItem(1, ash, false);

                // Если не влезло в инвентарь - дропаем в мир (не пропадает!)
                if (!remaining.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remaining);
                }
            }
            changed = true;
        } else {
            // Пытаемся взять новое топливо
            ItemStack fuel = be.inventory.getStackInSlot(0);
            if (!fuel.isEmpty()) {
                int tier = be.getFuelTier(fuel);
                if (tier >= 0) {
                    be.fuelTier = tier;
                    be.burnTime = TIER_STATS[tier][1];
                    be.totalBurnTime = be.burnTime;
                    fuel.shrink(1);
                    changed = true;
                }
            }
        }

        // === СИНХРОНИЗАЦИЯ ДАННЫХ ===
        be.data.set(DATA_TEMP, be.temperature);
        be.data.set(DATA_BURN_TIME, be.burnTime);
        be.data.set(DATA_TOTAL_BURN_TIME, be.totalBurnTime);
        be.data.set(DATA_IS_BURNING, be.burnTime > 0 ? 1 : 0);

        if (changed) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }


    public boolean isFuel(ItemStack stack) {
        return getFuelTier(stack) >= 0;
    }

    public int getFuelTier(ItemStack stack) {
        Item item = stack.getItem();

        // Тир 0: дешевое топливо
        if (item == Items.STICK ||
                item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS ||
                item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS ||
                item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS ||
                item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS ||
                item == Items.BAMBOO_PLANKS || item == Items.OAK_SLAB ||
                item == Item.byBlock(Blocks.OAK_LOG)) return 0;

        // Тир 1: обычное топливо
        if (item == Items.COAL || item == Items.CHARCOAL) return 1;

        // Тир 2: блазе род
        if (item == Items.BLAZE_ROD|| item == Items.PORKCHOP) return 2;

        // Тир 3: угольный блок
        if (item == Item.byBlock(Blocks.COAL_BLOCK)) return 3;

        // Тир 4: лава
        if (item == Items.LAVA_BUCKET) return 4;

        // Тир 5: специальное (можно добавить кастомные предметы)
         if (item == ModItems.MORY_LAH.get()) return 5;

        return -1;
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public ContainerData getData() {
        return data;
    }

    public int getTemperature() { return temperature; }
    public int getBurnTime() { return burnTime; }
    public int getTotalBurnTime() { return totalBurnTime; }
    public boolean isBurning() { return burnTime > 0; }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("Temperature", temperature);
        tag.putInt("BurnTime", burnTime);
        tag.putInt("TotalBurnTime", totalBurnTime);
        tag.putInt("FuelTier", fuelTier);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.deserializeNBT(tag.getCompound("Inventory"));
        temperature = tag.getInt("Temperature");
        burnTime = tag.getInt("BurnTime");
        totalBurnTime = tag.getInt("TotalBurnTime");
        fuelTier = tag.getInt("FuelTier");

        // Восстанавливаем данные для GUI
        data.set(DATA_TEMP, temperature);
        data.set(DATA_BURN_TIME, burnTime);
        data.set(DATA_TOTAL_BURN_TIME, totalBurnTime);
        data.set(DATA_IS_BURNING, burnTime > 0 ? 1 : 0);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.cim.heater");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new HeaterMenu(id, inv, this, data);
    }

    // === СИНХРОНИЗАЦИЯ ДЛЯ HUD (критически важно!) ===
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putInt("Temperature", temperature);
        tag.putInt("BurnTime", burnTime);
        tag.putInt("TotalBurnTime", totalBurnTime);
        tag.putInt("FuelTier", fuelTier);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        if (tag.contains("Temperature")) {
            temperature = tag.getInt("Temperature");
            burnTime = tag.getInt("BurnTime");
            totalBurnTime = tag.getInt("TotalBurnTime");
            fuelTier = tag.getInt("FuelTier");

            // Обновляем данные для GUI/HUD
            data.set(DATA_TEMP, temperature);
            data.set(DATA_BURN_TIME, burnTime);
            data.set(DATA_TOTAL_BURN_TIME, totalBurnTime);
            data.set(DATA_IS_BURNING, burnTime > 0 ? 1 : 0);
        }
    }

    // Для немедленной синхронизации когда игрок смотрит на блок
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }
}