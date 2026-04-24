// com/cim/client/overlay/gui/GUISmallSmelter.java
package com.cim.client.overlay.gui;

import com.cim.api.metallurgy.system.MetalUnits2;
import com.cim.block.entity.industrial.casting.SmallSmelterBlockEntity;
import com.cim.main.CrustalIncursionMod;
import com.cim.menu.SmallSmelterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GUISmallSmelter extends AbstractContainerScreen<SmallSmelterMenu> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(CrustalIncursionMod.MOD_ID, "textures/gui/machine/small_smelter_gui.png");
    private static final int TANK_X = 83;
    private static final int TANK_Y = 8;
    private static final int TANK_W = 32;
    private static final int TANK_H = 54;
    private static final int TEMP_BAR_X = 40;
    private static final int TEMP_BAR_Y = 9;
    private static final int TEMP_BAR_W = 15;
    private static final int TEMP_BAR_H = 51;
    private static final int FLAME_X = 121;
    private static final int FLAME_Y = 35;
    private static final int FLAME_W = 14;
    private static final int FLAME_H = 14;
    private static final int PROGRESS_X = 121;
    private static final int PROGRESS_Y = 7;
    private static final int PROGRESS_W = 16;
    private static final int PROGRESS_H = 3;

    public GUISmallSmelter(SmallSmelterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 168;
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        gui.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // Температурная полоска
        float temp = menu.getTemperature() / 10f; // в data хранится *10
        float maxTemp = SmallSmelterBlockEntity.MAX_TEMP;
        int filledHeight = (int)((temp / maxTemp) * TEMP_BAR_H);
        if (filledHeight > 0) {
            gui.blit(TEXTURE, x + TEMP_BAR_X, y + TEMP_BAR_Y + (TEMP_BAR_H - filledHeight),
                    177, 19 + (TEMP_BAR_H - filledHeight), TEMP_BAR_W, filledHeight);
        }

        // Индикатор горения
        if (menu.isBurning()) {
            gui.blit(TEXTURE, x + FLAME_X, y + FLAME_Y, 179, 2, FLAME_W, FLAME_H);
        }

        // Прогресс плавки
        if (menu.hasRecipe()) {
            int progress = menu.getSmeltProgress();
            int maxProgress = menu.getSmeltMaxProgress();
            int fillWidth = maxProgress > 0 ? (progress * PROGRESS_W) / maxProgress : 0;
            if (fillWidth > 0) {
                gui.blit(TEXTURE, x + PROGRESS_X, y + PROGRESS_Y, 215, 15, fillWidth, PROGRESS_H);
            }
        }

        // Резервуар металла (32x54)
        renderMetalTank(gui, x + TANK_X, y + TANK_Y, TANK_W, TANK_H);
    }

    private void renderMetalTank(GuiGraphics gui, int x, int y, int width, int height) {
        List<SmallSmelterBlockEntity.MetalStack> metals = menu.getBlockEntity().getMetalStacks();
        if (metals.isEmpty()) return;

        int totalCapacity = SmallSmelterBlockEntity.CAPACITY_UNITS;
        int currentY = y + height;

        for (SmallSmelterBlockEntity.MetalStack stack : metals) {
            int segmentHeight = (int)((stack.amount * height) / (float)totalCapacity);
            if (segmentHeight <= 0) continue;
            int color = stack.metal.getColor();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            gui.setColor(r, g, b, 1.0f);
            gui.blit(TEXTURE, x, currentY - segmentHeight, 194, 19, width, segmentHeight);
            gui.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            gui.fill(x, currentY - segmentHeight, x + width, currentY - segmentHeight + 1, 0x40FFFFFF);
            currentY -= segmentHeight;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {}

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        this.renderBackground(gui);
        super.render(gui, mouseX, mouseY, delta);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        if (isHovering(TEMP_BAR_X, TEMP_BAR_Y, TEMP_BAR_W, TEMP_BAR_H, mouseX, mouseY)) {
            renderTemperatureTooltip(gui, mouseX, mouseY);
        } else if (isHovering(FLAME_X, FLAME_Y, FLAME_W, FLAME_H, mouseX, mouseY)) {
            renderFlameTooltip(gui, mouseX, mouseY);
        } else if (isHovering(PROGRESS_X, PROGRESS_Y, PROGRESS_W, PROGRESS_H, mouseX, mouseY)) {
            renderProgressTooltip(gui, mouseX, mouseY);
        } else if (isHovering(TANK_X, TANK_Y, TANK_W, TANK_H, mouseX, mouseY)) {
            renderMetalTankTooltip(gui, mouseX, mouseY);
        } else {
            this.renderTooltip(gui, mouseX, mouseY);
        }
    }

    private void renderTemperatureTooltip(GuiGraphics gui, int mx, int my) {
        float temp = menu.getTemperature() / 10f;
        float maxTemp = SmallSmelterBlockEntity.MAX_TEMP;
        int color = getSmoothTemperatureColor(temp / maxTemp);
        Component text = Component.literal(String.format("%.0f / %.0f °C", temp, maxTemp))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
        gui.renderTooltip(this.font, text, mx, my);
    }

    private void renderFlameTooltip(GuiGraphics gui, int mx, int my) {
        if (menu.isBurning()) {
            int seconds = menu.getBurnTime() / 20;
            int totalSeconds = menu.getTotalBurnTime() / 20;
            Component text = Component.literal(String.format("§6Осталось: §f%d§7/§f%d сек", seconds, totalSeconds));
            gui.renderTooltip(this.font, text, mx, my);
        } else {
            gui.renderTooltip(this.font, Component.literal("§7Остановлен"), mx, my);
        }
    }

    private void renderProgressTooltip(GuiGraphics gui, int mx, int my) {
        List<Component> lines = new ArrayList<>();
        int currentTemp = (int)(menu.getTemperature() / 10f);
        int requiredTemp = menu.getBlockEntity().getRequiredTemp();
        boolean hasEnough = currentTemp >= requiredTemp;
        int tempColor = hasEnough ? 0x00FF00 : (System.currentTimeMillis() / 500 % 2 == 0 ? 0x910000 : 0x808080);
        lines.add(Component.literal(String.format("Температура: %d/%d °C", currentTemp, requiredTemp))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(tempColor))));
        if (menu.getSmeltMaxProgress() > 0) {
            int remaining = menu.getSmeltMaxProgress() - menu.getSmeltProgress();
            int heatPerTick = menu.getBlockEntity().getCurrentRecipe() != null ? (int)menu.getBlockEntity().getCurrentRecipe().heatConsumption() : 10;
            float seconds = remaining / (heatPerTick * 20.0f);
            lines.add(Component.literal(String.format("Осталось: %.1fс", Math.max(0, seconds)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA))));
        }
        gui.renderComponentTooltip(this.font, lines, mx, my);
    }

    private void renderMetalTankTooltip(GuiGraphics gui, int mx, int my) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6§lРасплавленные металлы:"));
        List<SmallSmelterBlockEntity.MetalStack> metals = menu.getBlockEntity().getMetalStacks();
        if (metals.isEmpty()) {
            lines.add(Component.literal("§7Пусто"));
        } else {
            boolean showExact = hasShiftDown();
            List<SmallSmelterBlockEntity.MetalStack> displayOrder = new ArrayList<>(metals);
            Collections.reverse(displayOrder);
            for (SmallSmelterBlockEntity.MetalStack stack : displayOrder) {
                int units = stack.amount;
                MetalUnits2.MetalStack converted = MetalUnits2.convertFromUnits(units);
                String name = Component.translatable(stack.metal.getTranslationKey()).getString();
                if (showExact) {
                    lines.add(Component.literal(name + ": " + units + " ед.")
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(stack.metal.getColor()))));
                } else {
                    StringBuilder sb = new StringBuilder();
                    if (converted.blocks() > 0) sb.append(converted.blocks()).append("б ");
                    if (converted.ingots() > 0) sb.append(converted.ingots()).append("сл ");
                    if (converted.nuggets() > 0) sb.append(converted.nuggets()).append("см ");
                    if (sb.length() == 0) sb.append("0");
                    lines.add(Component.literal(name + ": " + sb.toString())
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(stack.metal.getColor()))));
                }
            }
            int total = menu.getBlockEntity().getTotalMetalAmount();
            lines.add(Component.literal(String.format("§7Всего: §f%d§7 ед. / §f%d§7 ед.", total, SmallSmelterBlockEntity.CAPACITY_UNITS)));
            lines.add(Component.literal(showExact ? "§8[Shift] скрыть точное значение" : "§8[Shift] точное значение"));
        }
        gui.renderComponentTooltip(this.font, lines, mx, my);
    }

    private int getSmoothTemperatureColor(float percent) {
        percent = Math.max(0.0f, Math.min(1.0f, percent));
        int colorGrey = 0xAAAAAA;
        int colorOrange = 0xFFAA00;
        int colorRed = 0xFF2222;
        if (percent <= 0.3f) return lerpColor(colorGrey, colorOrange, percent / 0.3f);
        else if (percent <= 0.7f) return lerpColor(colorOrange, colorRed, (percent - 0.3f) / 0.4f);
        else return colorRed;
    }

    private int lerpColor(int c1, int c2, float t) {
        int r = (int)(((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int)(((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int)((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}