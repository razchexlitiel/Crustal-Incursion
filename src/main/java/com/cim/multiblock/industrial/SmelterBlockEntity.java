package com.cim.multiblock.industrial;

import com.cim.api.metal.MetalRegistry;
import com.cim.api.metal.MetalType;
import com.cim.api.metal.recipe.SmeltingRecipe;
import com.cim.api.metal.recipe.SmeltingRecipeRegistry;
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
            // Верхний ряд (0-3) - проверяем есть ли рецепт или это часть рецепта
            // Нижний ряд (4-7) - проверяем есть ли простой рецепт
            if (slot < 4) {
                // Для верхнего ряда принимаем любые предметы, проверка на валидность рецепта будет в тике
                return true;
            } else {
                return SmeltingRecipeRegistry.findSimpleRecipe(stack) != null;
            }
        }
    };

    private final Map<MetalType, Integer> metalTank = new HashMap<>();
    private int totalMetalAmount = 0;
    private int temperature = 0;

    // Прогресс для двух рядов
    private final int[] smeltProgress = new int[2];
    private final int[] smeltMaxProgress = new int[2];
    private final boolean[] isSmelting = new boolean[2];
    private final int[] currentHeatConsumption = new int[2]; // Текущее потребление тепла

    // Информация о текущем рецепте (для GUI и логики)
    private SmeltingRecipe currentTopRecipe = null;
    private SmeltingRecipeRegistry.SimpleSmeltRecipe currentBottomRecipe = null;

    private final ContainerData data = new SimpleContainerData(9); // +2 для типа рецепта

    public SmelterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMELTER_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SmelterBlockEntity be) {
        // Инициализация рецептов если нужно
        if (SmeltingRecipeRegistry.getAllRecipes().isEmpty()) {
            SmeltingRecipeRegistry.init();
        }

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

        // Плавка верхнего ряда (рецепты)
        be.tickTopRow();

        // Плавка нижнего ряда (обычная)
        be.tickBottomRow();

        // Синхронизация данных
        be.data.set(0, be.temperature);
        be.data.set(1, be.smeltProgress[0]);
        be.data.set(2, be.smeltMaxProgress[0]);
        be.data.set(3, be.smeltProgress[1]);
        be.data.set(4, be.smeltMaxProgress[1]);
        be.data.set(5, be.isSmelting[0] ? 1 : 0);
        be.data.set(6, be.isSmelting[1] ? 1 : 0);
        be.data.set(7, be.currentTopRecipe != null ? 1 : 0); // есть ли рецепт вверху
        be.data.set(8, be.currentBottomRecipe != null ? 1 : 0); // есть ли рецепт внизу

        if (be.isSmelting[0] || be.isSmelting[1] || be.temperature > 0) {
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private void tickTopRow() {
        // Проверяем вместимость перед началом
        if (totalMetalAmount >= TANK_CAPACITY) {
            isSmelting[0] = false;
            currentTopRecipe = null;
            return;
        }

        ItemStack[] inputs = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            inputs[i] = inventory.getStackInSlot(i);
        }

        // Ищем рецепт
        SmeltingRecipe recipe = SmeltingRecipeRegistry.findRecipe(inputs);

        // Если нет рецепта - сбрасываем прогресс
        if (recipe == null) {
            if (isSmelting[0]) {
                // Была плавка, но рецепт сломали - сброс
                smeltProgress[0] = 0;
                smeltMaxProgress[0] = 0;
                isSmelting[0] = false;
                currentTopRecipe = null;
            }
            return;
        }

        // Проверка температуры
        if (temperature < recipe.getMinTemperature()) {
            return; // Ждем нагрева
        }

        // Проверка вместимости для этого конкретного рецепта
        int potentialAmount = totalMetalAmount + recipe.getOutputAmount();
        if (potentialAmount > TANK_CAPACITY) {
            // Не хватит места для результата этого рецепта
            return;
        }

        // Начинаем или продолжаем плавку
        if (!isSmelting[0] || currentTopRecipe != recipe) {
            // Новая плавка или смена рецепта
            isSmelting[0] = true;
            currentTopRecipe = recipe;
            smeltProgress[0] = 0;
            smeltMaxProgress[0] = recipe.getTotalHeatRequired();
            currentHeatConsumption[0] = recipe.getHeatPerTick();
        }

        // Процесс плавки
        if (smeltMaxProgress[0] > 0) {
            int heatPerTick = Math.min(currentHeatConsumption[0], temperature / 20 + 1);
            // Ограничиваем чтобы не уйти в минус слишком сильно
            heatPerTick = Math.min(heatPerTick, temperature);

            smeltProgress[0] += heatPerTick;
            temperature = Math.max(0, temperature - (heatPerTick / 2)); // Потребление тепла

            if (smeltProgress[0] >= smeltMaxProgress[0]) {
                // Плавка завершена
                completeTopRecipe(recipe);
                smeltProgress[0] = 0;
                smeltMaxProgress[0] = 0;
                isSmelting[0] = false;
                currentTopRecipe = null;
            }
        }
    }

    private void tickBottomRow() {
        // Аналогичная проверка вместимости
        if (totalMetalAmount >= TANK_CAPACITY) {
            isSmelting[1] = false;
            currentBottomRecipe = null;
            return;
        }

        // Собираем предметы нижнего ряда и их рецепты
        Map<SmeltingRecipeRegistry.SimpleSmeltRecipe, Integer> recipeCounts = new HashMap<>();
        int totalOutput = 0;

        for (int i = 4; i < 8; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                var recipe = SmeltingRecipeRegistry.findSimpleRecipe(stack);
                if (recipe != null) {
                    recipeCounts.merge(recipe, 1, Integer::sum);
                    totalOutput += recipe.outputMb;
                }
            }
        }

        if (recipeCounts.isEmpty()) {
            if (isSmelting[1]) {
                smeltProgress[1] = 0;
                smeltMaxProgress[1] = 0;
                isSmelting[1] = false;
                currentBottomRecipe = null;
            }
            return;
        }

        // Проверяем поместится ли результат всех текущих предметов
        if (totalMetalAmount + totalOutput > TANK_CAPACITY) {
            return;
        }

        // Проверяем минимальную температуру для всех рецептов
        int maxMinTemp = recipeCounts.keySet().stream()
                .mapToInt(r -> r.minTemp)
                .max()
                .orElse(0);

        if (temperature < maxMinTemp) {
            return;
        }

        // Рассчитываем общее потребление тепла и время
        int totalHeatRequired = 0;
        int totalHeatPerTick = 0;

        for (var entry : recipeCounts.entrySet()) {
            var recipe = entry.getKey();
            int count = entry.getValue();
            totalHeatRequired += recipe.totalHeat * count;
            totalHeatPerTick += recipe.heatPerTick * count;
        }

        if (!isSmelting[1]) {
            isSmelting[1] = true;
            smeltProgress[1] = 0;
            smeltMaxProgress[1] = totalHeatRequired;
            currentHeatConsumption[1] = totalHeatPerTick;
        }

        // Плавка
        if (smeltMaxProgress[1] > 0) {
            int heatPerTick = Math.min(currentHeatConsumption[1], temperature / 20 + 1);
            heatPerTick = Math.min(heatPerTick, temperature);

            smeltProgress[1] += heatPerTick;
            temperature = Math.max(0, temperature - (heatPerTick / 2));

            if (smeltProgress[1] >= smeltMaxProgress[1]) {
                completeBottomRecipes(recipeCounts);
                smeltProgress[1] = 0;
                smeltMaxProgress[1] = 0;
                isSmelting[1] = false;
            }
        }
    }

    private void completeTopRecipe(SmeltingRecipe recipe) {
        // Уменьшаем предметы в верхнем ряду
        for (int i = 0; i < 4; i++) {
            ItemStack required = recipe.getInputs()[i];
            if (required != null && !required.isEmpty()) {
                ItemStack current = inventory.getStackInSlot(i);
                current.shrink(required.getCount());
            }
        }

        // Добавляем результат
        addMetal(recipe.getOutputMetal(), recipe.getOutputAmount());
        setChanged();
    }

    private void completeBottomRecipes(Map<SmeltingRecipeRegistry.SimpleSmeltRecipe, Integer> counts) {
        // Уменьшаем предметы и добавляем металлы
        for (var entry : counts.entrySet()) {
            var recipe = entry.getKey();
            int needed = entry.getValue();
            int processed = 0;

            for (int i = 4; i < 8 && processed < needed; i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (stack.getItem() == recipe.input) {
                    stack.shrink(1);
                    processed++;
                    addMetal(recipe.output, recipe.outputMb);
                }
            }
        }
        setChanged();
    }

    // Унифицированный метод добавления металла с автоконвертацией
    private void addMetal(MetalType metal, int amount) {
        metalTank.merge(metal, amount, Integer::sum);
        totalMetalAmount += amount;
        normalizeMetalAmounts(); // Фикс конвертации единиц
    }

    // ФИКС: Нормализация количеств (9 самородков → 1 слиток, 9 слитков → 1 блок)
    private void normalizeMetalAmounts() {
        for (var entry : metalTank.entrySet()) {
            MetalType metal = entry.getKey();
            int amount = entry.getValue();

            // Конвертация в "стандартные" единицы
            // 111 мб = 1 слиток, 12 мб = 1 самородок (из твоего кода)
            // 9 слитков = 1 блок (1000 мб)
            // Значит: 9 * 111 = 999 мб ≈ 1000 мб (1 блок)
            // И 9 * 12 = 108 мб ≈ 111 мб (1 слиток) - тут неточность в системе

            // Правильная конвертация:
            int blocks = amount / 1000;
            int remainder = amount % 1000;
            int ingots = remainder / 111;
            int nuggets = (remainder % 111) / 12;
            int leftover = (remainder % 111) % 12; // Мелочь < 12 мб

            // Конвертация самородков в слитки
            if (nuggets >= 9) {
                ingots += nuggets / 9;
                nuggets = nuggets % 9;
            }

            // Конвертация слитков в блоки
            if (ingots >= 9) {
                blocks += ingots / 9;
                ingots = ingots % 9;
            }

            // Пересчитываем общее количество
            int newAmount = blocks * 1000 + ingots * 111 + nuggets * 12 + leftover;
            entry.setValue(newAmount);
        }

        // Пересчитываем общую сумму
        recalculateTotal();
    }

    private void recalculateTotal() {
        totalMetalAmount = metalTank.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void recalculateSmelting() {
        // Сброс при изменении инвентаря
        if (isSmelting[0] && currentTopRecipe != null) {
            ItemStack[] inputs = new ItemStack[4];
            for (int i = 0; i < 4; i++) inputs[i] = inventory.getStackInSlot(i);
            if (!currentTopRecipe.matches(inputs)) {
                smeltProgress[0] = 0;
                isSmelting[0] = false;
                currentTopRecipe = null;
            }
        }
    }

    public ItemStackHandler getInventory() { return inventory; }
    public ContainerData getData() { return data; }
    public int getTemperature() { return temperature; }
    public Map<MetalType, Integer> getMetalTank() { return new HashMap<>(metalTank); }
    public int getTotalMetalAmount() { return totalMetalAmount; }

    public List<MetalStack> getMetalStacks() {
        List<MetalStack> list = new ArrayList<>();
        metalTank.forEach((metal, amount) -> {
            if (amount > 0) list.add(new MetalStack(metal, amount));
        });
        // Сортируем по количеству (больше сверху)
        list.sort((a, b) -> Integer.compare(b.amount, a.amount));
        return list;
    }

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

        if (currentTopRecipe != null) {
            tag.putString("CurrentTopRecipe", currentTopRecipe.getId());
        }

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

        if (tag.contains("CurrentTopRecipe")) {
            String recipeId = tag.getString("CurrentTopRecipe");
            // Восстановление рецепта из ID (нужно добавить поиск по ID в реестр)
        }

        metalTank.clear();
        ListTag metals = tag.getList("Metals", Tag.TAG_COMPOUND);
        for (int i = 0; i < metals.size(); i++) {
            CompoundTag mt = metals.getCompound(i);
            ResourceLocation id = new ResourceLocation(mt.getString("Metal"));
            int amt = mt.getInt("Amount");
            MetalType metal = MetalRegistry.get(id);
            if (metal != null) {
                metalTank.put(metal, amt);
            }
        }
        recalculateTotal();
    }

    // ... остальные методы (getUpdateTag, getDisplayName и т.д.) без изменений ...

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

    // В класс SmelterBlockEntity добавь:
    @Nullable
    public static Object getSmeltingResult(ItemStack stack) {
        // Инициализация если нужно
        if (SmeltingRecipeRegistry.getAllSimpleRecipes().isEmpty()) {
            SmeltingRecipeRegistry.init();
        }

        // Проверяем есть ли простой рецепт для этого предмета (для нижнего ряда)
        var simple = SmeltingRecipeRegistry.findSimpleRecipe(stack);
        if (simple != null) return simple;

        // Для верхнего ряда - проверяем может ли предмет быть частью любого рецепта
        // Пока возвращаем true для любого непустого стака (точная проверка в тике)
        return stack.isEmpty() ? null : stack;
    }
}