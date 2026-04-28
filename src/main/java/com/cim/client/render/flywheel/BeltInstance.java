package com.cim.client.render.flywheel;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

/**
 * Кастомный инстанс для ремня, расширяющий TransformedInstance на одно float-поле uvScroll.
 *
 * Layout по паттерну Create SCROLLING_TRANSFORMED:
 *   pose (mat4, 64 bytes) → ПЕРВЫМ! Чтобы cull shader корректно читал матрицу.
 *   color (vec4 u8norm, 4 bytes)
 *   light (uvec2 u16, 4 bytes)
 *   overlay (ivec2 i16, 4 bytes)
 *   uvScroll (float, 4 bytes)
 *   Total: 80 bytes
 */
public class BeltInstance extends TransformedInstance {

    public float uvScroll = 0.0f;

    public BeltInstance(InstanceType<? extends BeltInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public BeltInstance setUvScroll(float scroll) {
        this.uvScroll = scroll;
        return this;
    }

    // Layout по паттерну Create SCROLLING_TRANSFORMED: pose ПЕРВОЙ
    public static final Layout LAYOUT = LayoutBuilder.create()
            .matrix("pose", FloatRepr.FLOAT, 4)                    // offset 0,  64 bytes
            .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4) // offset 64, 4 bytes
            .vector("light", IntegerRepr.SHORT, 2)                  // offset 68, 4 bytes
            .vector("overlay", IntegerRepr.SHORT, 2)                // offset 72, 4 bytes
            .scalar("uvScroll", FloatRepr.FLOAT)                    // offset 76, 4 bytes
            .build();                                                // total: 80 bytes

    public static final InstanceType<BeltInstance> TYPE = SimpleInstanceType.builder(BeltInstance::new)
            .layout(LAYOUT)
            .writer((ptr, instance) -> {
                // Пишем всё вручную, без делегации — точные офсеты по layout-у
                ExtraMemoryOps.putMatrix4f(ptr, instance.pose);        // offset 0, 64 bytes
                MemoryUtil.memPutByte(ptr + 64, instance.red);         // offset 64
                MemoryUtil.memPutByte(ptr + 65, instance.green);
                MemoryUtil.memPutByte(ptr + 66, instance.blue);
                MemoryUtil.memPutByte(ptr + 67, instance.alpha);
                ExtraMemoryOps.put2x16(ptr + 68, instance.light);      // offset 68
                ExtraMemoryOps.put2x16(ptr + 72, instance.overlay);    // offset 72
                MemoryUtil.memPutFloat(ptr + 76, instance.uvScroll);   // offset 76
            })
            .vertexShader(new ResourceLocation("cim", "instance/belt.vert"))
            .cullShader(new ResourceLocation("cim", "instance/cull/belt.glsl"))
            .build();
}