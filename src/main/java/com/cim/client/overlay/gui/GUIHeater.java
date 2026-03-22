package com.cim.client.overlay.gui;

import com.cim.main.CrustalIncursionMod;
import com.cim.menu.HeaterMenu;
import com.cim.multiblock.industrial.HeaterBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class GUIHeater extends AbstractContainerScreen<HeaterMenu> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            CrustalIncursionMod.MOD_ID, "textures/gui/machine/heater_gui.png");

    // Примеры предметов для каждого тира (для отображения в тултипе)
    private static final ItemStack[] TIER_ICONS = {
            new ItemStack(Items.STICK),           // Тир 0
            new ItemStack(Items.COAL),            // Тир 1
            new ItemStack(Items.BLAZE_ROD),       // Тир 2
            new ItemStack(Blocks.COAL_BLOCK),     // Тир 3 (блок)
            new ItemStack(Items.LAVA_BUCKET),     // Тир 4
            new ItemStack(Items.NETHER_STAR)      // Тир 5 (заглушка/специальное)
    };

    public GUIHeater(HeaterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 168;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        guiGraphics.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // Прогрессбар тепла (снизу вверх)
        int temp = menu.getTemperature();
        int maxTemp = HeaterBlockEntity.MAX_TEMP;
        int barHeight = 52;
        int filledHeight = (int) ((long) temp * barHeight / maxTemp);

        if (filledHeight > 0) {
            guiGraphics.blit(TEXTURE,
                    x + 64, y + 9 + (barHeight - filledHeight),
                    177, 19 + (barHeight - filledHeight),
                    16, filledHeight);
        }

        // Индикатор работы
        if (menu.isBurning()) {
            guiGraphics.blit(TEXTURE, x + 104, y + 25, 177, 0, 18, 18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Убрали название контейнера! Метод пустой.
        // Если нужно оставить "Инвентарь" для слотов игрока, раскомментируй:
        // guiGraphics.drawString(this.font, this.playerInventoryTitle, 8, 74, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, delta);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Тултип температуры
        if (this.isHovering(64, 9, 15, 52, mouseX, mouseY)) {
            Component tempText = Component.literal(
                    String.format("§c%d°C §7/ §c%d°C", menu.getTemperature(), HeaterBlockEntity.MAX_TEMP)
            );
            guiGraphics.renderTooltip(this.font, tempText, mouseX, mouseY);
        }

        // Тултип индикатора работы
        if (this.isHovering(104, 25, 18, 18, mouseX, mouseY)) {
            if (menu.isBurning()) {
                int seconds = menu.getBurnTime() / 20;
                int totalSeconds = menu.getTotalBurnTime() / 20;
                Component timeText = Component.literal(
                        String.format("§6Осталось: §f%d§7/§f%d сек", seconds, totalSeconds)
                );
                guiGraphics.renderTooltip(this.font, timeText, mouseX, mouseY);
            } else {
                guiGraphics.renderTooltip(this.font, Component.literal("§7Остановлен"), mouseX, mouseY);
            }
        }

        // Тултип слота топлива с иконками (x85 y12)
        if (this.isHovering(85, 12, 16, 16, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§6§lТопливные тиры:"));
            lines.add(Component.literal("§8Тир 0: §f1°C §7за тик, §f5§7сек"));
            lines.add(Component.literal("§8Тир 1: §f2°C §7за тик, §f7.5§7сек"));
            lines.add(Component.literal("§8Тир 2: §f3°C §7+ зола, §f20§7сек"));
            lines.add(Component.literal("§8Тир 3: §f4°C §7+ зола, §f30§7сек"));
            lines.add(Component.literal("§8Тир 4: §f6°C §7+ зола, §f40§7сек"));
            lines.add(Component.literal("§8Тир 5: §f10°C §7+ зола, §f60§7сек"));

            // Рисуем тултип текста
            guiGraphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);

            // Рисуем иконки предметов справа от тултипа
            int tooltipWidth = 120; // примерная ширина тултипа
            int iconX = mouseX + tooltipWidth + 4;
            int iconY = mouseY + 12; // сдвиг вниз от заголовка

            // Рисуем иконку для каждого тира с отступом вниз
            for (int i = 0; i < 6; i++) {
                int drawY = iconY + (i * 12); // 12 пикселей между иконками (включая высоту строки)
                if (i < TIER_ICONS.length && !TIER_ICONS[i].isEmpty()) {
                    guiGraphics.renderItem(TIER_ICONS[i], iconX, drawY);
                    guiGraphics.renderItemDecorations(this.font, TIER_ICONS[i], iconX, drawY);
                }
            }
        }

        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}