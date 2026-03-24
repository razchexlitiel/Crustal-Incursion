package com.cim.api.metal;

public final class MetalUnits {
    // Константы конвертации (настраиваемые)
    public static final int MB_PER_BLOCK = 1000;
    public static final int MB_PER_INGOT = 111;  // 9 слитков ≈ 1000mb (999)
    public static final int MB_PER_NUGGET = 12;  // 9 самородков ≈ 1 слиток (108)

    private MetalUnits() {}

    /** Распределяет mb на блоки/слитки/самородки */
    public static MetalStack convert(int totalMb) {
        int blocks = totalMb / MB_PER_BLOCK;
        int rem = totalMb % MB_PER_BLOCK;

        int ingots = rem / MB_PER_INGOT;
        rem = rem % MB_PER_INGOT;

        int nuggets = rem / MB_PER_NUGGET;
        int leftover = rem % MB_PER_NUGGET; // Остаток < 12mb

        return new MetalStack(blocks, ingots, nuggets, leftover, totalMb);
    }

    /** Конвертирует блоки/слитки/самородки обратно в mb */
    public static int toMb(int blocks, int ingots, int nuggets) {
        return blocks * MB_PER_BLOCK + ingots * MB_PER_INGOT + nuggets * MB_PER_NUGGET;
    }

    /** Форматирует для отображения: "5б 3сл 2см" или "3.5 слитка" */
    public static String format(int totalMb) {
        MetalStack stack = convert(totalMb);
        StringBuilder sb = new StringBuilder();

        if (stack.blocks > 0) sb.append(stack.blocks).append("б ");
        if (stack.ingots > 0) sb.append(stack.ingots).append("сл ");
        if (stack.nuggets > 0) sb.append(stack.nuggets).append("см");

        String result = sb.toString().trim();
        return result.isEmpty() ? totalMb + "мб" : result;
    }

    public record MetalStack(int blocks, int ingots, int nuggets, int leftover, int totalMb) {
        public boolean isEmpty() { return totalMb == 0; }

        public int getDisplayTotalIngots() {
            return blocks * 9 + ingots + (nuggets >= 4 ? 1 : 0); // Для примерной оценки
        }
    }
}