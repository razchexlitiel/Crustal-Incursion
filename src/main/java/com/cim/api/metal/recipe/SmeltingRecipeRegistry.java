package com.cim.api.metal.recipe;

import com.cim.api.metal.MetalRegistry;
import com.cim.api.metal.MetalType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmeltingRecipeRegistry {
    private static final List<SmeltingRecipe> RECIPES = new ArrayList<>();
    private static final Map<String, SmeltingRecipe> RECIPE_BY_ID = new HashMap<>();

    // Обычные рецепты плавки (не комбинационные) - для нижнего ряда
    private static final List<SimpleSmeltRecipe> SIMPLE_RECIPES = new ArrayList<>();

    public static class SimpleSmeltRecipe {
        public final Item input;
        public final MetalType output;
        public final int outputMb;
        public final int minTemp;
        public final int heatPerTick;
        public final int totalHeat;

        public SimpleSmeltRecipe(Item input, MetalType output, int outputMb,
                                 int minTemp, int heatPerTick, int totalHeat) {
            this.input = input;
            this.output = output;
            this.outputMb = outputMb;
            this.minTemp = minTemp;
            this.heatPerTick = heatPerTick;
            this.totalHeat = totalHeat;
        }
    }

    public static void init() {
        // Убедимся что металлы инициализированы
        if (MetalRegistry.IRON == null) MetalRegistry.init();

        // === СПЕЦИАЛЬНЫЕ РЕЦЕПТЫ ДЛЯ ВЕРХНЕГО РЯДА ===

        // Пример: Сталь (комбинация железа и угля в определенных слотах)
        register(new SmeltingRecipe.Builder("steel_alloy")
                .type(SmeltingRecipe.RecipeType.ALLOY)
                .slot(SmeltingRecipe.SlotColor.RED, Items.IRON_INGOT, 3)      // 3 слитка железа
                .slot(SmeltingRecipe.SlotColor.YELLOW, Items.COAL, 2)          // 2 угля
                .slot(SmeltingRecipe.SlotColor.GREEN, Items.IRON_INGOT, 3)     // ещё 3 железа
                .slot(SmeltingRecipe.SlotColor.BLUE, Items.REDSTONE, 1)        // катализатор
                .output(MetalRegistry.STEEL, 0, 6, 0) // 6 слитков стали (666 мб)
                .temperature(1200)
                .heat(5, 1500)
                .description("Сплав железа с углем при высокой температуре")
                .build());

        // Пример: Бронза (медь + олово, если есть олово в моде, пока используем золото как заглушку)
        register(new SmeltingRecipe.Builder("bronze_alloy")
                .type(SmeltingRecipe.RecipeType.ALLOY)
                .slot(SmeltingRecipe.SlotColor.RED, Items.COPPER_INGOT, 3)
                .slot(SmeltingRecipe.SlotColor.YELLOW, Items.GOLD_INGOT, 1)    // временно золото вместо олова
                .slot(SmeltingRecipe.SlotColor.GREEN, Items.COPPER_INGOT, 2)
                .slot(SmeltingRecipe.SlotColor.BLUE, ItemStack.EMPTY)          // пусто
                .output(MetalRegistry.BRONZE, 0, 4, 3) // 4 слитка + 3 самородка бронзы
                .temperature(900)
                .heat(3, 800)
                .description("Сплав меди и олова")
                .build());

        // Пример: Электрум (золото + серебро, серебра нет - используем железо)
        register(new SmeltingRecipe.Builder("electrum_alloy")
                .type(SmeltingRecipe.RecipeType.ALLOY)
                .slot(SmeltingRecipe.SlotColor.RED, Items.GOLD_INGOT, 2)
                .slot(SmeltingRecipe.SlotColor.YELLOW, Items.IRON_NUGGET, 4)   // типа серебряные самородки
                .slot(SmeltingRecipe.SlotColor.GREEN, Items.GOLD_INGOT, 2)
                .slot(SmeltingRecipe.SlotColor.BLUE, Items.REDSTONE, 2)
                .output(MetalRegistry.ELECTRUM, 0, 4, 0)
                .temperature(1000)
                .heat(4, 1000)
                .description("Сплав золота и серебра")
                .build());

        // Пример: Чугун (железо + уголь в другой конфигурации)
        register(new SmeltingRecipe.Builder("cast_iron")
                .type(SmeltingRecipe.RecipeType.ALLOY)
                .slot(SmeltingRecipe.SlotColor.RED, Items.IRON_INGOT, 2)
                .slot(SmeltingRecipe.SlotColor.YELLOW, Items.COAL_BLOCK, 1)    // блок угля!
                .slot(SmeltingRecipe.SlotColor.GREEN, Items.IRON_INGOT, 2)
                .slot(SmeltingRecipe.SlotColor.BLUE, Items.IRON_INGOT, 2)
                .output(MetalRegistry.CAST_IRON, 1, 0, 0) // 1 блок чугуна
                .temperature(1400)
                .heat(8, 2000)
                .description("Высокоуглеродистое железо")
                .build());

        // === ОБЫЧНЫЕ РЕЦЕПТЫ ДЛЯ НИЖНЕГО РЯДА ===

        // Железо
        addSimple(Items.IRON_ORE, MetalRegistry.IRON, 111, 800, 3, 600);
        addSimple(Items.RAW_IRON, MetalRegistry.IRON, 111, 600, 3, 400);
        addSimple(Items.IRON_INGOT, MetalRegistry.IRON, 111, 1000, 2, 200);
        addSimple(Items.IRON_BLOCK, MetalRegistry.IRON, 111, 1000, 2, 200);

        // Медь
        addSimple(Items.COPPER_ORE, MetalRegistry.COPPER, 111, 700, 3, 500);
        addSimple(Items.RAW_COPPER, MetalRegistry.COPPER, 111, 500, 3, 350);

        // Золото
        addSimple(Items.GOLD_ORE, MetalRegistry.GOLD, 111, 750, 3, 550);
        addSimple(Items.RAW_GOLD, MetalRegistry.GOLD, 111, 550, 3, 350);

        // Утильсырьё - переплавка инструментов/брони
        addSimple(Items.IRON_HELMET, MetalRegistry.IRON, 5 * 111, 900, 4, 800);    // 5 слитков
        addSimple(Items.IRON_CHESTPLATE, MetalRegistry.IRON, 8 * 111, 900, 4, 1200);
        addSimple(Items.IRON_LEGGINGS, MetalRegistry.IRON, 7 * 111, 900, 4, 1000);
        addSimple(Items.IRON_BOOTS, MetalRegistry.IRON, 4 * 111, 900, 4, 600);
    }

    private static void register(SmeltingRecipe recipe) {
        RECIPES.add(recipe);
        RECIPE_BY_ID.put(recipe.getId(), recipe);
    }

    private static void addSimple(Item input, MetalType output, int mb, int temp, int heatTick, int totalHeat) {
        SIMPLE_RECIPES.add(new SimpleSmeltRecipe(input, output, mb, temp, heatTick, totalHeat));
    }

    @Nullable
    public static SmeltingRecipe findRecipe(ItemStack[] rowInputs) {
        for (SmeltingRecipe recipe : RECIPES) {
            if (recipe.matches(rowInputs)) {
                return recipe;
            }
        }
        return null;
    }

    @Nullable
    public static SimpleSmeltRecipe findSimpleRecipe(ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (SimpleSmeltRecipe recipe : SIMPLE_RECIPES) {
            if (recipe.input == stack.getItem()) {
                return recipe;
            }
        }
        return null;
    }

    public static List<SmeltingRecipe> getAllRecipes() {
        return new ArrayList<>(RECIPES);
    }

    public static List<SimpleSmeltRecipe> getAllSimpleRecipes() {
        return new ArrayList<>(SIMPLE_RECIPES);
    }
}