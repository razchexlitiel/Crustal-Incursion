package com.cim.client.render.flywheel;

import com.cim.block.basic.industrial.rotation.ShaftBlock;
import com.cim.block.entity.industrial.rotation.ShaftBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;

import java.util.function.Consumer;

import static net.minecraft.world.entity.ai.util.DefaultRandomPos.getPos;

public class ShaftVisual extends AbstractBlockEntityVisual<ShaftBlockEntity> implements SimpleDynamicVisual {

    private final TransformedInstance instance;
    private final Direction facing;

    public ShaftVisual(VisualizationContext ctx, ShaftBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        this.facing = blockEntity.getBlockState().getValue(ShaftBlock.FACING);

        // 1. В новой апи мы получаем Instancer через instancerProvider() из контекста
        // Заглушка Models.SHAFT_MODEL — это наш будущий загрузчик модели
        // Добавляем .get() в самом конце
        Instancer<TransformedInstance> instancer = instancerProvider().instancer(
                InstanceTypes.TRANSFORMED,
                Models.partial(ModModels.SHAFT_MODEL) // Передаем нашу модель в их фабрику!
        );

        // 2. Создаем инстанс (наши данные на видеокарте)
        this.instance = instancer.createInstance();

        // 3. Первичная настройка позиции
        setupTransform();
    }



    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        // Передаем наш инстанс вала, чтобы на нем рисовались трещины при добыче
        consumer.accept(this.instance);
    }

    private void setupTransform() {
        // 1. Сбрасываем позицию, ставим на координаты блока и центрируем (к 0.5, 0.5, 0.5)
        instance.setIdentityTransform()
                .translate(pos)
                .center();

        // 2. Получаем ось из направления (FACING)
        Direction.Axis axis = facing.getAxis();

        // 3. Поворачиваем инстанс
        if (axis == Direction.Axis.X) {
            // Если вал стоит по линии Восток-Запад, поворачиваем на 90 градусов по Y
            instance.rotateY((float) Math.toRadians(90));
        } else if (axis == Direction.Axis.Y) {
            // Если вал стоит вертикально (Вверх-Вниз), поворачиваем на 90 градусов по X
            instance.rotateX((float) Math.toRadians(90));
        }
        // Если ось Z (Север-Юг), ничего не делаем — наша базовая модель в Blockbench уже смотрит туда.

        // TODO: Тут в будущем будет математика анимации (Угол = RenderTime * Speed)

        // 4. Смещаем центр обратно и отправляем изменения на видеокарту
        instance.uncenter()
                .setChanged();
    }




    @Override
    public void updateLight(float partialTick) {
        // Перерасчет освещения
        relight(pos, instance);
    }

    @Override
    protected void _delete() {
        // Удаляем инстанс из памяти GPU при разрушении блока
        instance.delete();
    }


    @Override
    public void beginFrame(Context ctx) {
        float speed = blockEntity.getSpeed();
        if (speed != 0) {
            setupTransform();
        }
    }
}
