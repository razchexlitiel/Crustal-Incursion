package com.cim.client.render.flywheel; // Проверь свой пакет!

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.InstanceWriter;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

public class BeltInstance extends TransformedInstance {

    public float uvScroll = 0.0f;

    public BeltInstance(InstanceType<? extends BeltInstance> type, InstanceHandle handle) {
        super(type, handle);
    }


    public BeltInstance setUvScroll(float scroll) {
        this.uvScroll = scroll;
        return this;
    }

    // ИСПРАВЛЕННЫЙ МАКЕТ: Строгий порядок байтов, как в ядре Flywheel (без normal)
    public static final Layout LAYOUT = LayoutBuilder.create()
            .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
            .vector("overlay", IntegerRepr.SHORT, 2)
            .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
            .matrix("pose", FloatRepr.FLOAT, 4)
            .scalar("uvScroll", FloatRepr.FLOAT) // Наш сдвиг строго в самом конце!
            .build();

    public static final InstanceType<BeltInstance> TYPE = SimpleInstanceType.builder(BeltInstance::new)
            .layout(LAYOUT) // Твой исправленный LAYOUT, который уже есть в этом классе
            .writer((ptr, instance) -> {
                // 1. Базовый писатель (цвет, свет, матрица)
                dev.engine_room.flywheel.lib.instance.InstanceTypes.TRANSFORMED.writer().write(ptr, instance);

                // 2. Дописываем твой сдвиг анимации
                MemoryUtil.memPutFloat(ptr + 76, instance.uvScroll);
            })
            // ВАЖНО: Проверь, правильный ли тут путь до твоего шейдера
            .vertexShader(new ResourceLocation("cim", "instance/belt.vert"))
            .cullShader(new ResourceLocation("flywheel", "instance/cull/transformed.glsl"))
            .build();

    public static final InstanceWriter<BeltInstance> WRITER = (ptr, instance) -> {
        // Базовый писатель Flywheel заполняет pose, color, light, overlay
        InstanceTypes.TRANSFORMED.writer().write(ptr, instance);

        // Мы дописываем наш сдвиг ровно в конец (offset = 76 байт)
        long offset = InstanceTypes.TRANSFORMED.layout().byteSize();
        MemoryUtil.memPutFloat(ptr + offset, instance.uvScroll);
    };

//    public static final InstanceType<BeltInstance> TYPE = SimpleInstanceType.builder(BeltInstance::new)
//            .layout(LAYOUT)
//            .writer(WRITER)
//            .vertexShader(new ResourceLocation("cim", "instance/belt.vert"))
//            .cullShader(new ResourceLocation("flywheel", "instance/cull/transformed.glsl"))
//            .build();
}