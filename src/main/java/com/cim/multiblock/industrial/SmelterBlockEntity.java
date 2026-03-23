package com.cim.multiblock.industrial;

import com.cim.api.metal.MetalRegistry;
import com.cim.api.metal.MetalType;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmelterBlockEntity extends BlockEntity implements MenuProvider {
    public static final int MAX_TEMP = 1600;
    public static final int TANK_CAPACITY = 4000; // максимум в мб

    private final ItemStackHandler inventory = new ItemStackHandler(8) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
                recalculateSmelting();
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return getSmeltingResult(stack) != null;
        }
    };

    // ЗАМЕНА: вместо Map<Fluid, Integer> используем Map<MetalType, Integer>
    private final Map<MetalType, Integer> metalTank = new HashMap<>();
    private int totalMetalAmount = 0;
    private int temperature = 0;

    private final int[] smeltProgress = new int[2];
    private final int[] smeltMaxProgress = new int[2];
    private final boolean[] isSmelting = new boolean[2];

    // Data: [temp, progTop, maxTop, progBot, maxBot, isTop, isBot] - 7 элементов!
    private final ContainerData data = new SimpleContainerData(7);

    public SmelterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMELTER_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SmelterBlockEntity be) {
        // Тепло (без изменений)
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

        // Плавка
        be.tickRow(0, 0, 4);
        be.tickRow(1, 4, 8);

        // Синхронизация данных
        be.data.set(0, be.temperature);
        be.data.set(1, be.smeltProgress[0]);
        be.data.set(2, be.smeltMaxProgress[0]);
        be.data.set(3, be.smeltProgress[1]);
        be.data.set(4, be.smeltMaxProgress[1]);
        be.data.set(5, be.isSmelting[0] ? 1 : 0);
        be.data.set(6, be.isSmelting[1] ? 1 : 0);

        if (be.isSmelting[0] || be.isSmelting[1] || be.temperature > 0) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private void tickRow(int rowIndex, int startSlot, int endSlot) {
        if (totalMetalAmount >= TANK_CAPACITY) {
            isSmelting[rowIndex] = false;
            return;
        }

        int totalHeatRequired = 0;
        int totalOutputMb = 0;
        Map<MetalType, Integer> outputs = new HashMap<>();
        boolean canSmelt = false;

        for (int i = startSlot; i < endSlot; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                SmeltData data = getSmeltingResult(stack);
                if (data != null) {
                    totalHeatRequired += data.heatRequired;
                    totalOutputMb += data.outputMb;
                    outputs.merge(data.metal, data.outputMb, Integer::sum);
                    canSmelt = true;
                }
            }
        }

        if (!canSmelt) {
            smeltProgress[rowIndex] = 0;
            smeltMaxProgress[rowIndex] = 0;
            isSmelting[rowIndex] = false;
            return;
        }

        // Проверка места перед стартом
        if (smeltMaxProgress[rowIndex] == 0) {
            if (totalMetalAmount + totalOutputMb > TANK_CAPACITY) {
                return;
            }
            smeltMaxProgress[rowIndex] = totalHeatRequired;
        }

        isSmelting[rowIndex] = true;

        if (temperature > 100) {
            int heatPerTick = Math.min(temperature / 20 + 1, 20);
            smeltProgress[rowIndex] += heatPerTick;
            temperature = Math.max(0, temperature - (heatPerTick / 4));

            if (smeltProgress[rowIndex] >= smeltMaxProgress[rowIndex]) {
                completeSmelting(rowIndex, startSlot, endSlot, outputs);
                smeltProgress[rowIndex] = 0;
                smeltMaxProgress[rowIndex] = 0;
            }
        }
    }

    private void completeSmelting(int rowIndex, int startSlot, int endSlot, Map<MetalType, Integer> outputs) {
        for (int i = startSlot; i < endSlot; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                SmeltData data = getSmeltingResult(stack);
                if (data != null) {
                    stack.shrink(1);
                }
            }
        }

        // Добавляем металлы в буфер
        outputs.forEach((metal, amount) -> {
            metalTank.merge(metal, amount, Integer::sum);
            totalMetalAmount += amount;
        });

        setChanged();
    }

    // Обновлённая SmeltData с MetalType вместо Fluid
    public static class SmeltData {
        public final MetalType metal;
        public final int outputMb;
        public final int heatPerTick;
        public final int heatRequired;

        public SmeltData(MetalType metal, int outputMb, int heatPerTick, int heatRequired) {
            this.metal = metal;
            this.outputMb = outputMb;
            this.heatPerTick = heatPerTick;
            this.heatRequired = heatRequired;
        }
    }

    @Nullable
    public static SmeltData getSmeltingResult(ItemStack stack) {
        // Инициализация реестра если не инициализирован
        if (MetalRegistry.IRON == null) MetalRegistry.init();

        if (stack.is(net.minecraft.world.item.Items.IRON_ORE) ||
                stack.is(net.minecraft.world.item.Items.RAW_IRON)) {
            return new SmeltData(MetalRegistry.IRON, 111, 300, 600);
        }
        if (stack.is(net.minecraft.world.item.Items.COPPER_ORE) ||
                stack.is(net.minecraft.world.item.Items.RAW_COPPER)) {
            return new SmeltData(MetalRegistry.COPPER, 111, 200, 400);
        }
        if (stack.is(net.minecraft.world.item.Items.GOLD_ORE) ||
                stack.is(net.minecraft.world.item.Items.RAW_GOLD)) {
            return new SmeltData(MetalRegistry.GOLD, 111, 250, 500);
        }
        return null;
    }

    private void recalculateSmelting() {}

    public ItemStackHandler getInventory() { return inventory; }
    public ContainerData getData() { return data; }
    public int getTemperature() { return temperature; }

    // НОВЫЙ МЕТОД: получить карту металлов
    public Map<MetalType, Integer> getMetalTank() {
        return new HashMap<>(metalTank);
    }

    public int getTotalMetalAmount() { return totalMetalAmount; }

    // Заменяем getFluidStacks на getMetalStacks
    public List<MetalStack> getMetalStacks() {
        List<MetalStack> list = new ArrayList<>();
        metalTank.forEach((metal, amount) -> {
            if (amount > 0) list.add(new MetalStack(metal, amount));
        });
        return list;
    }

    // Вспомогательный класс для передачи данных
    public static class MetalStack {
        public final MetalType metal;
        public final int amount;

        public MetalStack(MetalType metal, int amount) {
            this.metal = metal;
            this.amount = amount;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("Temperature", temperature);
        tag.putIntArray("Progress", smeltProgress);
        tag.putIntArray("MaxProgress", smeltMaxProgress);

        // Сохраняем металлы вместо жидкостей
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

        int[] prog = tag.getIntArray("Progress");
        if (prog.length == 2) System.arraycopy(prog, 0, smeltProgress, 0, 2);
        int[] max = tag.getIntArray("MaxProgress");
        if (max.length == 2) System.arraycopy(max, 0, smeltMaxProgress, 0, 2);

        metalTank.clear();
        totalMetalAmount = 0;

        ListTag metals = tag.getList("Metals", Tag.TAG_COMPOUND);
        for (int i = 0; i < metals.size(); i++) {
            CompoundTag mt = metals.getCompound(i);
            ResourceLocation id = new ResourceLocation(mt.getString("Metal"));
            int amt = mt.getInt("Amount");
            MetalType metal = MetalRegistry.get(id);
            if (metal != null) {
                metalTank.put(metal, amt);
                totalMetalAmount += amt;
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