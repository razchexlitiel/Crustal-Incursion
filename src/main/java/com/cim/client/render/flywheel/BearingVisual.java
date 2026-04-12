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
    }

    private void createShaftInstance() {
        if (blockState.getValue(BearingBlock.HAS_SHAFT) && currentMaterial != null && currentDiameter != null) {
            String shaftName = "shaft_" + currentDiameter.name + "_" + currentMaterial.name();
            PartialModel shaftModel = ModModels.SHAFT_MODELS.getOrDefault(shaftName, ModModels.HALF_SHAFT);

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
                // Используем локальные координаты
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
    public void update(float pt) {
        super.update(pt);
        if (blockEntity.getShaftMaterial() != currentMaterial || blockEntity.getShaftDiameter() != currentDiameter) {
            this.currentMaterial = blockEntity.getShaftMaterial();
            this.currentDiameter = blockEntity.getShaftDiameter();

            if (this.shaft != null) {
                this.shaft.delete();
                this.shaft = null;
            }

            createShaftInstance();

            if (this.shaft != null) {
                applyStaticTransform(this.shaft);
                updateLight(pt);
            }
        }
    }

    @Override
    public void beginFrame(Context ctx) {
        long speed = blockEntity.getVisualSpeed();
        if (speed == 0) return;

        float time = (float) (System.currentTimeMillis() % 100000) / 50f;
        float angle = time * speed * 0.1f;

        applyRotation(this.innerRing, angle);
        if (this.shaft != null) {
            applyRotation(this.shaft, angle);
        }
    }

    private void applyRotation(TransformedInstance instance, float angle) {
        instance.setIdentityTransform()
                // Используем локальные координаты
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