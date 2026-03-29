package com.cim.client.renderer;

import com.cim.api.metal.Metal;
import com.cim.block.basic.industrial.casting.CastingDescentBlock;
import com.cim.block.entity.industrial.casting.CastingDescentBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class CastingDescentRenderer implements BlockEntityRenderer<CastingDescentBlockEntity> {
    private static final ResourceLocation LIQUID_METAL_TEXTURE = new ResourceLocation("cim", "textures/machine/liquid_metal.png");

    // Базовые размеры из твоих данных
    private static final float W = 3.7f / 16f; // Ширина
    private static final float T = 1.2f / 16f; // Толщина/Высота сегмента

    public CastingDescentRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(CastingDescentBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!be.isPouring()) return;
        Metal metal = be.getPouringMetal();
        if (metal == null) return;

        Direction facing = be.getLevel().getBlockState(be.getBlockPos()).getValue(CastingDescentBlock.FACING);
        float streamEndY = be.getStreamEndY();

        int color = metal.getColor();
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        // Отрицательный flowOffset заставляет текстуру течь ВНИЗ
        float flowOffset = -(be.getLevel().getGameTime() + partialTick) * 0.1f;
        VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(LIQUID_METAL_TEXTURE));

        poseStack.pushPose();

        // 1. РАЗВОРОТ: Добавляем 180 градусов к текущему повороту FACING
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - facing.toYRot()));
        poseStack.translate(-0.5, -0.5, -0.5);

        // 2. СЕГМЕНТ 3 (Горизонтальный "хвост")
        renderBox(poseStack, builder, packedLight, r, g, b, 1.0f,
                6.1f/16f, 1.2f/16f, 12.15f/16f,
                (6.1f+3.7f)/16f, (1.2f+1.2f)/16f, 16.0f/16f,
                flowOffset, 0.3f);

        // 3. СЕГМЕНТ 2 (Наклонный -22.5)
        poseStack.pushPose();
        poseStack.translate(8f/16f, 1.8f/16f, 12.15f/16f);
        poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f));
        poseStack.translate(-8f/16f, -1.8f/16f, -12.15f/16f);

        renderBox(poseStack, builder, packedLight, r, g, b, 1.0f,
                6.1f/16f, 1.2f/16f, 7.6f/16f,
                (6.1f+3.7f)/16f, (1.2f+1.2f)/16f, 12.15f/16f,
                flowOffset, 0.4f);
        poseStack.popPose();

        // 4. СЕГМЕНТ 1 (Вертикальная струя 3.7x1.2)
        float s1TopY = 0.666f / 16f;
        if (streamEndY < s1TopY) {
            renderBox(poseStack, builder, packedLight, r, g, b, 1.0f,
                    6.1f/16f, streamEndY, 7.9f/16f,
                    (6.1f+3.7f)/16f, s1TopY, (7.9f+1.2f)/16f, // Z теперь 1.2 пикселя
                    flowOffset, s1TopY - streamEndY);
        }

        poseStack.popPose();
    }

    private void renderBox(PoseStack poseStack, VertexConsumer builder, int packedLight,
                           float r, float g, float b, float a,
                           float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                           float flowOffset, float length) {
        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();

        float uMin = 0, uMax = 1;
        float vMin = flowOffset % 1.0f;
        float vMax = vMin + length;

        // Отрисовка всех сторон с сонаправленным UV (все текут "вперед" или "вниз")
        // Верх (+Y)
        vertex(builder, m, n, minX, maxY, minZ, r, g, b, a, uMin, vMin, packedLight, 0, 1, 0);
        vertex(builder, m, n, minX, maxY, maxZ, r, g, b, a, uMin, vMax, packedLight, 0, 1, 0);
        vertex(builder, m, n, maxX, maxY, maxZ, r, g, b, a, uMax, vMax, packedLight, 0, 1, 0);
        vertex(builder, m, n, maxX, maxY, minZ, r, g, b, a, uMax, vMin, packedLight, 0, 1, 0);

        // Бока (Запад/Восток) - здесь V привязана к направлению течения
        vertex(builder, m, n, minX, minY, minZ, r, g, b, a, 0, vMin, packedLight, -1, 0, 0);
        vertex(builder, m, n, minX, minY, maxZ, r, g, b, a, 1, vMin, packedLight, -1, 0, 0);
        vertex(builder, m, n, minX, maxY, maxZ, r, g, b, a, 1, vMax, packedLight, -1, 0, 0);
        vertex(builder, m, n, minX, maxY, minZ, r, g, b, a, 0, vMax, packedLight, -1, 0, 0);

        vertex(builder, m, n, maxX, minY, maxZ, r, g, b, a, 0, vMin, packedLight, 1, 0, 0);
        vertex(builder, m, n, maxX, minY, minZ, r, g, b, a, 1, vMin, packedLight, 1, 0, 0);
        vertex(builder, m, n, maxX, maxY, minZ, r, g, b, a, 1, vMax, packedLight, 1, 0, 0);
        vertex(builder, m, n, maxX, maxY, maxZ, r, g, b, a, 0, vMax, packedLight, 1, 0, 0);

        // Лицевые (Север/Юг)
        vertex(builder, m, n, minX, minY, minZ, r, g, b, a, 0, vMax, packedLight, 0, 0, -1);
        vertex(builder, m, n, maxX, minY, minZ, r, g, b, a, 1, vMax, packedLight, 0, 0, -1);
        vertex(builder, m, n, maxX, maxY, minZ, r, g, b, a, 1, vMin, packedLight, 0, 0, -1);
        vertex(builder, m, n, minX, maxY, minZ, r, g, b, a, 0, vMin, packedLight, 0, 0, -1);
    }

    private void vertex(VertexConsumer b, Matrix4f m, Matrix3f n, float x, float y, float z,
                        float r, float g, float bl, float a, float u, float v, int light,
                        float nx, float ny, float nz) {
        b.vertex(m, x, y, z).color(r, g, bl, a).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(n, nx, ny, nz).endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(CastingDescentBlockEntity be) { return true; }
}
