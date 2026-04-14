package com.cim.client.render.flywheel;

import com.cim.api.rotation.ShaftDiameter;
import com.cim.api.rotation.ShaftMaterial;
import com.cim.block.basic.industrial.rotation.BearingBlock;
import com.cim.block.entity.industrial.rotation.BearingBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static net.minecraft.core.Direction.EAST;
import static net.minecraft.core.Direction.UP;

public class BearingVisual extends AbstractBlockEntityVisual<BearingBlockEntity> implements SimpleDynamicVisual {

    private final TransformedInstance innerRing;
    @Nullable
    private TransformedInstance shaft;

    private final Direction facing;
    private ShaftMaterial currentMaterial;
    private ShaftDiameter currentDiameter;

    // Локальные координаты
    private final float localX;
    private final float localY;
    private final float localZ;

    public BearingVisual(VisualizationContext ctx, BearingBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);
        this.facing = blockState.getValue(BearingBlock.FACING);

        Vec3i origin = ctx.renderOrigin();
        this.localX = pos.getX() - origin.getX();
        this.localY = pos.getY() - origin.getY();
        this.localZ = pos.getZ() - origin.getZ();

        this.innerRing = instancerProvider().instancer(
                InstanceTypes.TRANSFORMED,
                Models.partial(ModModels.BEARING_INNER_RING)
        ).createInstance();

        this.currentMaterial = blockEntity.getShaftMaterial();
        this.currentDiameter = blockEntity.getShaftDiameter();

        createShaftInstance();
        setupStaticPositions();
        updateLight(partialTick);

        // DEBUG: Логируем создание визуала для отслеживания бага с прозрачностью
        if (com.cim.main.CrustalIncursionMod.LOGGER.isInfoEnabled()) {
            com.cim.main.CrustalIncursionMod.LOGGER.info("[CIM-Visual] BearingVisual CREATED at {} | hasShaft={}", pos, blockEntity.hasShaft());
        }
    }

    private void createShaftInstance() {
        if (blockEntity.hasShaft() && currentMaterial != null && currentDiameter != null) {
            // ИСПОЛЬЗУЕМ .name(), чтобы избежать багов с переопределенным toString()
            String matName = currentMaterial.name().toLowerCase();
            String diaName = currentDiameter.name().toLowerCase();
            String shaftName = "shaft_" + diaName + "_" + matName;

            PartialModel shaftModel = ModModels.SHAFT_MODELS.get(shaftName);
            if (shaftModel == null) {
                // Если модель не зарегистрирована, логируем это, а не просто крашимся
                System.out.println("[CIM-Debug] ВНИМАНИЕ: Модель " + shaftName + " не найдена в ModModels! Используем HALF_SHAFT.");
                shaftModel = ModModels.HALF_SHAFT;
            }

            this.shaft = instancerProvider().instancer(
                    InstanceTypes.TRANSFORMED,
                    Models.partial(shaftModel)
            ).createInstance();
        }
    }

    private void setupStaticPositions() {
        applyStaticTransform(this.innerRing);
        if (this.shaft != null) {
            applyStaticTransform(this.shaft);
        }
    }

    private void applyStaticTransform(TransformedInstance instance) {
        instance.setIdentityTransform()
                .translate(localX, localY, localZ)
                .translate(0.5f, 0.5f, 0.5f);

        Direction.Axis axis = facing.getAxis();
        if (axis == Direction.Axis.X) {
            instance.rotateY((float) Math.toRadians(facing == EAST ? 270 : 90));
        } else if (axis == Direction.Axis.Y) {
            instance.rotateX((float) Math.toRadians(facing == UP ? 90 : -90));
        } else if (facing == Direction.SOUTH) {
            instance.rotateY((float) Math.toRadians(180));
        }

        instance.translate(-0.5f, -0.5f, -0.5f);
        instance.setChanged();
    }

    @Override
    public void beginFrame(Context ctx) {
        // 1. АБСОЛЮТНАЯ ЗАЩИТА ОТ ДЕСИНКА
        // Проверяем актуальное состояние NBT каждый кадр
        boolean shaftStateChanged = blockEntity.hasShaft() != (this.shaft != null);
        boolean materialChanged = blockEntity.getShaftMaterial() != currentMaterial;
        boolean diameterChanged = blockEntity.getShaftDiameter() != currentDiameter;

        // Если что-то изменилось (например, мы вставили вал) — мгновенно пересобираем
        if (shaftStateChanged || materialChanged || diameterChanged) {
            this.currentMaterial = blockEntity.getShaftMaterial();
            this.currentDiameter = blockEntity.getShaftDiameter();

            if (this.shaft != null) {
                this.shaft.delete();
                this.shaft = null;
            }

            createShaftInstance();

            if (this.shaft != null) {
                applyStaticTransform(this.shaft);
                relight(pos, this.shaft); // Считаем свет для нового вала
            }
        }

        // 2. ВРАЩЕНИЕ
        long speed = blockEntity.getVisualSpeed();
        float time = (float) (System.currentTimeMillis() % 100000) / 50f;
        // Даже если скорость 0, вызываем пересчет позиций, чтобы модель не исчезла!
        float angle = speed == 0 ? 0 : time * speed * 0.1f;

        applyRotation(this.innerRing, angle);
        if (this.shaft != null) {
            applyRotation(this.shaft, angle);
        }
    }

    private void applyRotation(TransformedInstance instance, float angle) {
        instance.setIdentityTransform()
                .translate(localX, localY, localZ)
                .translate(0.5f, 0.5f, 0.5f);

        Direction.Axis axis = facing.getAxis();
        if (axis == Direction.Axis.X) {
            instance.rotateY((float) Math.toRadians(facing == EAST ? 270 : 90));
        } else if (axis == Direction.Axis.Y) {
            instance.rotateX((float) Math.toRadians(facing == UP ? 90 : -90));
        } else if (facing == Direction.SOUTH) {
            instance.rotateY((float) Math.toRadians(180));
        }

        instance.rotateZ(angle);
        instance.translate(-0.5f, -0.5f, -0.5f);
        instance.setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        if (this.shaft != null) {
            relight(pos, this.innerRing, this.shaft);
        } else {
            relight(pos, this.innerRing);
        }
    }

    @Override
    protected void _delete() {
        this.innerRing.delete();
        if (this.shaft != null) {
            this.shaft.delete();
        }
    }

    @Override
    public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        consumer.accept(this.innerRing);
        if (this.shaft != null) {
            consumer.accept(this.shaft);
        }
    }
}