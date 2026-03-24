package com.cim.client.overlay.gui;

import com.cim.api.metal.Metal;
import com.cim.api.metal.MetalType;
import com.cim.api.metal.MetalUnits;
import com.cim.main.CrustalIncursionMod;
import com.cim.menu.SmelterMenu;
import com.cim.multiblock.industrial.SmelterBlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

public class GUISmelter extends AbstractContainerScreen<SmelterMenu> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            CrustalIncursionMod.MOD_ID, "textures/gui/machine/smelter_gui.png");

    public GUISmelter(SmelterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 184;
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Фон
        gui.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // Температура
        int temp = menu.getTemperature();
        int maxTemp = SmelterBlockEntity.MAX_TEMP;
        int barHeight = 51;
        int fillHeight = (temp * barHeight) / maxTemp;

        if (fillHeight > 0) {
            gui.blit(TEXTURE, x + 12, y + 17 + (barHeight - fillHeight),
                    176, 4 + (barHeight - fillHeight), 15, fillHeight);
        }

        // Прогресс верхний
        if (menu.isSmeltingTop()) {
            int progress = menu.getProgressTop();
            int maxProgress = menu.getMaxProgressTop();
            int fillWidth = maxProgress > 0 ? (progress * 70) / maxProgress : 0;
            if (fillWidth > 0) {
                gui.blit(TEXTURE, x + 95, y + 7, 176, 0, fillWidth, 3);
            }
        }

        // Прогресс нижний
        if (menu.isSmeltingBottom()) {
            int progress = menu.getProgressBottom();
            int maxProgress = menu.getMaxProgressBottom();
            int fillWidth = maxProgress > 0 ? (progress * 70) / maxProgress : 0;
            if (fillWidth > 0) {
                gui.blit(TEXTURE, x + 95, y + 39, 176, 0, fillWidth, 3);
            }
        }

        // Буфер металлов
        renderMetalTank(gui, x + 33, y + 8, 48, 70);
    }

    private void renderMetalTank(GuiGraphics gui, int x, int y, int width, int height) {
        List<SmelterBlockEntity.MetalStack> metals = menu.getBlockEntity().getMetalStacks();
        if (metals.isEmpty()) return;

        int totalCapacity = SmelterBlockEntity.TANK_CAPACITY;
        int currentY = y + height;

        // Сортируем по количеству (опционально)
        metals.sort((a, b) -> Integer.compare(b.amount, a.amount));

        for (SmelterBlockEntity.MetalStack stack : metals) {
            int segmentHeight = (int)((stack.amount * height) / (float)totalCapacity);
            if (segmentHeight <= 0) continue;

            int color = stack.metal.getColor();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;

            // Рисуем сегмент с цветом металла
            gui.setColor(r, g, b, 1.0f);
            gui.blit(TEXTURE, x, currentY - segmentHeight, 50, 185, width, segmentHeight);
            gui.setColor(1.0f, 1.0f, 1.0f, 1.0f);

            // Обводка
            gui.fill(x, currentY - segmentHeight, x + width, currentY - segmentHeight + 1, 0x40FFFFFF);

            currentY -= segmentHeight;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Убраны название и "инвентарь"
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        this.renderBackground(gui);
        super.render(gui, mouseX, mouseY, delta);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Тултип температуры
        if (this.isHovering(12, 17, 15, 51, mouseX, mouseY)) {
            int temp = menu.getTemperature();
            float percent = temp / 1600f;
            int color = getTempColor(percent);
            Component text = Component.literal(String.format("%d / %d °C", temp, 1600))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            gui.renderTooltip(this.font, text, mouseX, mouseY);
        }

        // Тултип металлов - ОБНОВЛЁННЫЙ
        if (this.isHovering(33, 8, 48, 70, mouseX, mouseY)) {
            renderMetalTooltip(gui, mouseX, mouseY);
        } else {
            this.renderTooltip(gui, mouseX, mouseY);
        }
    }

    private void renderMetalTooltip(GuiGraphics gui, int mx, int my) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6§lРасплавленные металлы:"));

        List<SmelterBlockEntity.MetalStack> metals = menu.getBlockEntity().getMetalStacks();
        if (metals.isEmpty()) {
            lines.add(Component.literal("§7Пусто"));
        } else {
            for (SmelterBlockEntity.MetalStack stack : metals) {
                int mb = stack.amount;
                Metal metal = stack.metal;

                // Цвет металла
                String hexColor = String.format("%06X", metal.getColor() & 0xFFFFFF);
                String name = Component.translatable(metal.getTranslationKey()).getString();

                if (hasShiftDown()) {
                    // Для отладки: показываем точное количество мб
                    lines.add(Component.literal(String.format("§#%s%s§f: §e%d мб", hexColor, name, mb)));
                } else {
                    // НОВОЕ: используем MetalUnits.convert для разбиения на блоки/слитки/самородки
                    MetalUnits.MetalStack amount = MetalUnits.convert(mb);
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("§#%s%s§f: ", hexColor, name));

                    boolean hasContent = false;

                    if (amount.blocks() > 0) {
                        sb.append("§e").append(amount.blocks()).append("§7б ");
                        hasContent = true;
                    }
                    if (amount.ingots() > 0) {
                        sb.append("§e").append(amount.ingots()).append("§7сл ");
                        hasContent = true;
                    }
                    if (amount.nuggets() > 0) {
                        sb.append("§e").append(amount.nuggets()).append("§7см");
                        hasContent = true;
                    }

                    // Если есть остаток (< 12мб) и ничего больше не показали
                    if (!hasContent && amount.leftover() > 0) {
                        sb.append("§e<1§7см");
                    }

                    lines.add(Component.literal(sb.toString().trim()));
                }
            }

            // Общая заполненность в блоках/слитках
            int total = menu.getBlockEntity().getTotalMetalAmount();
            MetalUnits.MetalStack totalAmount = MetalUnits.convert(total);
            int maxBlocks = menu.getBlockEntity().getBlockCapacity();

            StringBuilder totalSb = new StringBuilder("§7Всего: §e");
            if (totalAmount.blocks() > 0) totalSb.append(totalAmount.blocks()).append("§7б ");
            if (totalAmount.ingots() > 0) totalSb.append(totalAmount.ingots()).append("§7сл ");
            if (totalAmount.nuggets() > 0) totalSb.append(totalAmount.nuggets()).append("§7см");
            totalSb.append(" §8/ ").append(maxBlocks).append("§7б");

            lines.add(Component.literal(totalSb.toString().trim()));
            lines.add(Component.literal("§8[Shift] точное значение"));
        }

        gui.renderComponentTooltip(this.font, lines, mx, my);
    }

    private static int getTempColor(float percent) {
        percent = Math.max(0, Math.min(1, percent));
        if (percent < 0.3f) return 0xAAAAAA;
        if (percent < 0.7f) return 0xFFAA00;
        return 0xFF2222;
    }
}