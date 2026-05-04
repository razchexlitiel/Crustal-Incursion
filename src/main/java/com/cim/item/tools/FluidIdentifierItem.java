package com.cim.item.tools;

import com.cim.block.basic.industrial.fluids.FluidPipeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class FluidIdentifierItem extends Item {
    public FluidIdentifierItem(Properties pProperties) {
        super(pProperties.stacksTo(1)); // Идентификатор не стакается
    }

    // ==========================================
    // ФИКС 1: РАЗРЕШАЕМ ШИФТ+ПКМ ПО ТРУБЕ
    // ==========================================
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        // Если игрок на шифте кликает по нашей трубе - говорим игре:
        // "Не открывай предмет, заставь сработать метод use() самой трубы!"
        return level.getBlockState(pos).getBlock() instanceof FluidPipeBlock;
    }

    // ==========================================
    // ФИКС 2: БЛОКИРУЕМ GUI ПРИ КЛИКЕ ПО БЛОКУ
    // ==========================================
    @Override
    public InteractionResult useOn(UseOnContext pContext) {
        // Если мы кликнули по трубе (а её метод use на клиенте вдруг вернул PASS из-за рассинхрона),
        // мы принудительно гасим клик здесь, возвращая SUCCESS.
        // Это не даст игре дойти до метода use() в воздухе и открыть GUI.
        if (pContext.getLevel().getBlockState(pContext.getClickedPos()).getBlock() instanceof FluidPipeBlock) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(pContext);
    }


    @OnlyIn(Dist.CLIENT)
    private void openScreen(ItemStack stack) {
        Minecraft.getInstance().setScreen(new com.cim.client.overlay.gui.GUIFluidIdentifier(stack));
    }

    // --- УТИЛИТЫ ДЛЯ ЧТЕНИЯ NBT ---
    public static String getSelectedFluid(ItemStack stack) {
        if (!stack.hasTag()) return "none";
        return stack.getTag().getString("SelectedFluid");
    }

    public static List<String> getRecentFluids(ItemStack stack) {
        List<String> list = new ArrayList<>();
        if (stack.hasTag() && stack.getTag().contains("RecentFluids")) {
            ListTag listTag = stack.getTag().getList("RecentFluids", Tag.TAG_STRING);
            for (int i = 0; i < listTag.size(); i++) {
                list.add(listTag.getString(i));
            }
        }
        return list;
    }

    public static List<String> getFavorites(ItemStack stack) {
        List<String> list = new ArrayList<>();
        if (stack.hasTag() && stack.getTag().contains("Favorites")) {
            ListTag listTag = stack.getTag().getList("Favorites", Tag.TAG_STRING);
            for (int i = 0; i < listTag.size(); i++) {
                list.add(listTag.getString(i));
            }
        }
        return list;
    }


    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (level.isClientSide) openScreen(stack);
        } else {
            if (!level.isClientSide) {
                cycleFluid(stack, 1);
                String current = getSelectedFluid(stack);

                // ЦВЕТНОЕ СООБЩЕНИЕ
                player.displayClientMessage(Component.translatable("message.cim.selected_fluid")
                        .append(": ")
                        .append(Component.translatable("fluid." + current.replace(":", "."))
                                .withStyle(style -> style.withColor(getFluidColor(current)))), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }





    private void cycleFluid(ItemStack stack, int direction) {
        List<String> history = getRecentFluids(stack);
        if (history.isEmpty()) return;

        String current = getSelectedFluid(stack);
        int currentIndex = history.indexOf(current);

        // Вычисляем новый индекс с зацикливанием
        int newIndex = (currentIndex + direction) % history.size();
        if (newIndex < 0) newIndex = history.size() - 1;

        stack.getOrCreateTag().putString("SelectedFluid", history.get(newIndex));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String fluid = getSelectedFluid(stack);
        if (fluid.equals("none")) {
            tooltip.add(Component.translatable("tooltip.cim.no_fluid").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("Жидкость: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.translatable("fluid." + fluid.replace(":", "."))
                            .withStyle(style -> style.withColor(getFluidColor(fluid))))); // Динамический цвет
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }


    private int getFluidColor(String fluidName) {
        if (fluidName.contains("lava")) return 0xe64306;
        if (fluidName.equals("none")) return 0xFFFFFF;

        try {
            Fluid fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(new net.minecraft.resources.ResourceLocation(fluidName));
            if (fluid != null) {
                return net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid.getFluidType())
                        .getTintColor(new net.minecraftforge.fluids.FluidStack(fluid, 1000)) | 0xFF000000;
            }
        } catch (Exception e) {
            // На случай если ResourceLocation будет кривой
        }
        return 0xFFFFFF;
    }

}