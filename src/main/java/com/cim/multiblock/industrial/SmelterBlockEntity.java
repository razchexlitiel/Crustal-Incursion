package com.cim.multiblock.industrial;


import com.cim.api.metallurgy.system.Metal;
import com.cim.api.metallurgy.system.MetalUnits2;
import com.cim.api.metallurgy.system.MetallurgyRegistry;
import com.cim.api.metallurgy.system.recipe.AlloyRecipe;
import com.cim.api.metallurgy.system.recipe.AlloySlot;
import com.cim.api.metallurgy.system.recipe.SmeltRecipe;
import com.cim.block.entity.ModBlockEntities;
import com.cim.menu.SmelterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.*;

public class SmelterBlockEntity extends BlockEntity implements MenuProvider {
    public static final int MAX_TEMP = 1600;
    public static final int BLOCK_CAPACITY = 4;
    public static final int TANK_CAPACITY = BLOCK_CAPACITY * MetalUnits2.UNITS_PER_BLOCK; // 324

    private final ItemStackHandler inventory = new ItemStackHandler(8) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot < 4) return true; // верхний ряд – любые предметы
            return MetallurgyRegistry.getSmeltRecipe(stack.getItem()) != null;
        }
    };

    private final Map<Metal, Integer> metalTank = new LinkedHashMap<>();
    private int totalMetalAmount = 0;
    private int temperature = 0;
    private int lastInventoryHash = 0;

    // Верхний ряд (сплавы)
    private int topProgress = 0;
    private int topMaxProgress = 0;
    private boolean topSmelting = false;
    private int topHeatPerTick = 0;
    private AlloyRecipe currentAlloyRecipe = null;
    private int requiredTempTop = 0;

    // Нижний ряд (обычная плавка)
    private int bottomProgress = 0;
    private int bottomMaxProgress = 0;
    private boolean bottomSmelting = false;
    private int bottomHeatPerTick = 0;
    private Map<SmeltRecipe, Integer> currentBottomRecipes = new HashMap<>();
    private int requiredTempBottom = 0;

    private final ContainerData data = new SimpleContainerData(11) {
        @Override
        public void set(int index, int value) {
            super.set(index, value);
        }
    };

    public SmelterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMELTER_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SmelterBlockEntity be) {
        int currentHash = be.calculateInventoryHash();
        if (currentHash != be.lastInventoryHash) {
            be.lastInventoryHash = currentHash;
            be.resetSmeltingIfChanged();
        }

        // Теплообмен
        int baseCooling = (be.temperature * be.temperature) / 512000;
        if (baseCooling == 0 && be.temperature > 0) baseCooling = 1;
        int thermalNoise = (be.temperature > 200 && baseCooling > 1) ? level.random.nextInt(5) - 2 : 0;
        int cooling = Math.max(1, baseCooling + thermalNoise);

        BlockEntity below = level.getBlockEntity(pos.below());
        if (below instanceof HeaterBlockEntity heater && heater.getTemperature() > be.temperature) {
            int transfer = (heater.getTemperature() - be.temperature) / 10 + 1;
            be.temperature = Math.min(MAX_TEMP, be.temperature + transfer);
        } else if (be.temperature > 0) {
            be.temperature = Math.max(0, be.temperature - cooling);
        }

        be.tickTopRow();
        be.tickBottomRow();

        // Синхронизация данных для GUI
        be.data.set(0, be.temperature);
        be.data.set(1, be.topProgress);
        be.data.set(2, be.topMaxProgress);
        be.data.set(3, be.topSmelting ? 1 : 0);
        be.data.set(4, be.requiredTempTop);
        be.data.set(5, be.bottomProgress);
        be.data.set(6, be.bottomMaxProgress);
        be.data.set(7, be.bottomSmelting ? 1 : 0);
        be.data.set(8, be.requiredTempBottom);
        be.data.set(9, be.currentAlloyRecipe != null ? 1 : 0);
        be.data.set(10, !be.currentBottomRecipes.isEmpty() ? 1 : 0);

        if (be.topSmelting || be.bottomSmelting || be.temperature > 0) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private int calculateInventoryHash() {
        int hash = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() + stack.getCount());
        }
        return hash;
    }

    private void resetSmeltingIfChanged() {
        // Сброс верхнего ряда
        if (topSmelting || currentAlloyRecipe != null) {
            topSmelting = false;
            currentAlloyRecipe = null;
            topProgress = 0;
            topMaxProgress = 0;
            requiredTempTop = 0;
        }
        // Сброс нижнего ряда
        if (bottomSmelting || !currentBottomRecipes.isEmpty()) {
            bottomSmelting = false;
            currentBottomRecipes.clear();
            bottomProgress = 0;
            bottomMaxProgress = 0;
            requiredTempBottom = 0;
        }
    }

    // ==================== Верхний ряд (сплавы) ====================

    private void tickTopRow() {
        if (totalMetalAmount >= TANK_CAPACITY) {
            if (requiredTempTop != 0) requiredTempTop = 0;
            return;
        }

        ItemStack[] topSlots = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            topSlots[i] = inventory.getStackInSlot(i);
        }

        AlloyRecipe recipe = findMatchingAlloyRecipe(topSlots);
        if (recipe == null) {
            if (topSmelting || currentAlloyRecipe != null) {
                topSmelting = false;
                currentAlloyRecipe = null;
                topProgress = 0;
                topMaxProgress = 0;
                requiredTempTop = 0;
            }
            return;
        }

        requiredTempTop = recipe.getOutputMetal().getMeltingPoint();

        if (temperature < requiredTempTop) {
            topSmelting = false;
            topProgress = 0;
            topMaxProgress = recipe.getTotalHeat();
            return;
        }

        if (totalMetalAmount + recipe.getOutputUnits() > TANK_CAPACITY) {
            return;
        }

        if (!topSmelting) {
            topSmelting = true;
            topProgress = 0;
            topMaxProgress = recipe.getTotalHeat();
            topHeatPerTick = recipe.getHeatPerTick();
            currentAlloyRecipe = recipe;
        }

        if (topMaxProgress > 0) {
            int heatPerTick = Math.min(topHeatPerTick, temperature / 20 + 1);
            heatPerTick = Math.min(heatPerTick, temperature);
            topProgress += heatPerTick;
            temperature = Math.max(0, temperature - (heatPerTick / 2));

            if (topProgress >= topMaxProgress) {
                completeAlloyRecipe(currentAlloyRecipe);
                topSmelting = false;
                currentAlloyRecipe = null;
                topProgress = 0;
                topMaxProgress = 0;
                requiredTempTop = 0;
            }
        }
    }

    private AlloyRecipe findMatchingAlloyRecipe(ItemStack[] slots) {
        for (AlloyRecipe recipe : MetallurgyRegistry.getAllAlloyRecipes()) {
            if (recipe.matches(slots)) {
                return recipe;
            }
        }
        return null;
    }

    private void completeAlloyRecipe(AlloyRecipe recipe) {
        for (int i = 0; i < 4; i++) {
            AlloySlot slotReq = recipe.getSlots()[i];
            if (slotReq.item() != null && slotReq.count() > 0) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() == slotReq.item() && stack.getCount() >= slotReq.count()) {
                    stack.shrink(slotReq.count());
                }
            }
        }
        addMetal(recipe.getOutputMetal(), recipe.getOutputUnits());
        setChanged();
    }

    // ==================== Нижний ряд (обычная плавка) ====================

    private void tickBottomRow() {
        if (totalMetalAmount >= TANK_CAPACITY) {
            if (requiredTempBottom != 0) requiredTempBottom = 0;
            return;
        }

        Map<SmeltRecipe, Integer> recipeCounts = new HashMap<>();
        int totalOutput = 0;
        boolean hasAnyItem = false;

        for (int i = 4; i < 8; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                hasAnyItem = true;
                SmeltRecipe recipe = MetallurgyRegistry.getSmeltRecipe(stack.getItem());
                if (recipe != null) {
                    int count = stack.getCount();
                    recipeCounts.merge(recipe, count, Integer::sum);
                    totalOutput += recipe.outputUnits() * count;
                }
            }
        }

        if (!hasAnyItem || recipeCounts.isEmpty()) {
            if (bottomSmelting || !currentBottomRecipes.isEmpty()) {
                bottomSmelting = false;
                currentBottomRecipes.clear();
                bottomProgress = 0;
                bottomMaxProgress = 0;
                requiredTempBottom = 0;
            }
            return;
        }

        int maxMinTemp = recipeCounts.keySet().stream()
                .mapToInt(SmeltRecipe::minTemp)
                .max()
                .orElse(0);
        requiredTempBottom = maxMinTemp;

        if (temperature < maxMinTemp) {
            bottomSmelting = false;
            bottomProgress = 0;
            int totalHeat = recipeCounts.entrySet().stream()
                    .mapToInt(e -> e.getKey().totalHeat() * e.getValue())
                    .sum();
            bottomMaxProgress = totalHeat;
            return;
        }

        if (totalMetalAmount + totalOutput > TANK_CAPACITY) {
            return;
        }

        int totalHeatRequired = 0;
        int totalHeatPerTick = 0;
        for (var entry : recipeCounts.entrySet()) {
            SmeltRecipe recipe = entry.getKey();
            int count = entry.getValue();
            totalHeatRequired += recipe.totalHeat() * count;
            totalHeatPerTick += recipe.heatPerTick() * count;
        }

        if (!bottomSmelting) {
            bottomSmelting = true;
            bottomProgress = 0;
            bottomMaxProgress = totalHeatRequired;
            bottomHeatPerTick = totalHeatPerTick;
            currentBottomRecipes.clear();
            currentBottomRecipes.putAll(recipeCounts);
        }

        if (bottomMaxProgress > 0) {
            int heatPerTick = Math.min(bottomHeatPerTick, temperature / 20 + 1);
            heatPerTick = Math.min(heatPerTick, temperature);
            bottomProgress += heatPerTick;
            temperature = Math.max(0, temperature - (heatPerTick / 2));

            if (bottomProgress >= bottomMaxProgress) {
                completeBottomRecipes(currentBottomRecipes);
                bottomSmelting = false;
                currentBottomRecipes.clear();
                bottomProgress = 0;
                bottomMaxProgress = 0;
                requiredTempBottom = 0;
            }
        }
    }

    private void completeBottomRecipes(Map<SmeltRecipe, Integer> recipeCounts) {
        for (var entry : recipeCounts.entrySet()) {
            SmeltRecipe recipe = entry.getKey();
            int needed = entry.getValue();
            for (int i = 4; i < 8 && needed > 0; i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (stack.getItem() == recipe.input() && !stack.isEmpty()) {
                    int toRemove = Math.min(needed, stack.getCount());
                    stack.shrink(toRemove);
                    needed -= toRemove;
                    addMetal(recipe.output(), recipe.outputUnits() * toRemove);
                }
            }
        }
        setChanged();
    }

    // ==================== Работа с металлами ====================

    private void addMetal(Metal metal, int units) {
        if (units <= 0) return;
        if (totalMetalAmount + units > TANK_CAPACITY) {
            units = TANK_CAPACITY - totalMetalAmount;
            if (units <= 0) return;
        }
        metalTank.merge(metal, units, Integer::sum);
        recalculateTotal();
    }

    private void recalculateTotal() {
        totalMetalAmount = metalTank.values().stream().mapToInt(Integer::intValue).sum();
        if (totalMetalAmount > TANK_CAPACITY) {
            int excess = totalMetalAmount - TANK_CAPACITY;
            for (var entry : metalTank.entrySet()) {
                if (entry.getValue() >= excess) {
                    entry.setValue(entry.getValue() - excess);
                    break;
                }
            }
            recalculateTotal();
        }
    }

    public int extractMetal(Metal metal, int maxUnits) {
        Integer current = metalTank.get(metal);
        if (current == null || current <= 0) return 0;
        int toExtract = Math.min(maxUnits, current);
        if (toExtract <= 0) return 0;
        if (current <= toExtract) {
            metalTank.remove(metal);
        } else {
            metalTank.put(metal, current - toExtract);
        }
        recalculateTotal();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
        return toExtract;
    }

    public Metal getBottomMetal() {
        if (metalTank.isEmpty()) return null;
        return metalTank.keySet().iterator().next();
    }

    // ==================== Геттеры ====================

    public ItemStackHandler getInventory() { return inventory; }
    public ContainerData getData() { return data; }
    public int getTemperature() { return temperature; }
    public Map<Metal, Integer> getMetalTank() { return Collections.unmodifiableMap(metalTank); }
    public int getTotalMetalAmount() { return totalMetalAmount; }
    public int getBlockCapacity() { return BLOCK_CAPACITY; }
    public int getRequiredTempTop() { return requiredTempTop; }
    public int getRequiredTempBottom() { return requiredTempBottom; }
    public int getTopProgress() { return topProgress; }
    public int getTopMaxProgress() { return topMaxProgress; }
    public boolean isTopSmelting() { return topSmelting; }
    public int getBottomProgress() { return bottomProgress; }
    public int getBottomMaxProgress() { return bottomMaxProgress; }
    public boolean isBottomSmelting() { return bottomSmelting; }

    public List<MetalStack> getMetalStacks() {
        List<MetalStack> list = new ArrayList<>();
        metalTank.forEach((metal, amount) -> {
            if (amount > 0) list.add(new MetalStack(metal, amount));
        });
        list.sort((a, b) -> Integer.compare(b.amount, a.amount));
        return list;
    }

    public static class MetalStack {
        public final Metal metal;
        public final int amount;
        public MetalStack(Metal metal, int amount) { this.metal = metal; this.amount = amount; }
        public String getFormattedAmount() {
            return MetalUnits2.convertFromUnits(amount).totalUnits() + " ед.";
        }
    }

    // ==================== NBT ====================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("Temperature", temperature);
        tag.putInt("TopProgress", topProgress);
        tag.putInt("TopMaxProgress", topMaxProgress);
        tag.putInt("BottomProgress", bottomProgress);
        tag.putInt("BottomMaxProgress", bottomMaxProgress);
        tag.putInt("RequiredTempTop", requiredTempTop);
        tag.putInt("RequiredTempBottom", requiredTempBottom);

        ListTag metals = new ListTag();
        metalTank.forEach((metal, amount) -> {
            if (amount > 0) {
                CompoundTag mt = new CompoundTag();
                mt.putString("Metal", metal.getId().toString());
                mt.putInt("Amount", amount);
                metals.add(mt);
            }
        });
        tag.put("Metals", metals);
        tag.putInt("TotalMetal", totalMetalAmount);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.deserializeNBT(tag.getCompound("Inventory"));
        temperature = tag.getInt("Temperature");
        topProgress = tag.getInt("TopProgress");
        topMaxProgress = tag.getInt("TopMaxProgress");
        bottomProgress = tag.getInt("BottomProgress");
        bottomMaxProgress = tag.getInt("BottomMaxProgress");
        requiredTempTop = tag.getInt("RequiredTempTop");
        requiredTempBottom = tag.getInt("RequiredTempBottom");

        metalTank.clear();
        ListTag metals = tag.getList("Metals", Tag.TAG_COMPOUND);
        for (int i = 0; i < metals.size(); i++) {
            CompoundTag mt = metals.getCompound(i);
            ResourceLocation id = new ResourceLocation(mt.getString("Metal"));
            int amt = mt.getInt("Amount");
            MetallurgyRegistry.get(id).ifPresent(metal -> metalTank.put(metal, amt));
        }
        recalculateTotal();
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        load(tag);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) handleUpdateTag(pkt.getTag());
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.cim.smelter");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new SmelterMenu(id, inv, this, data);
    }
}