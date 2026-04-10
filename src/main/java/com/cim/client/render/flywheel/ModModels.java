package com.cim.client.render.flywheel;

import com.cim.api.rotation.ShaftDiameter;
import com.cim.api.rotation.ShaftMaterial;
import com.cim.block.basic.ModBlocks;
import com.cim.block.basic.industrial.rotation.ShaftBlock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;

public class ModModels {
    // Храним связи: Блок Вала -> Его PartialModel
    public static final Map<Block, PartialModel> SHAFT_MODELS = new HashMap<>();

    // Оставляем твои старые модели мотора
    public static final PartialModel MOTOR_BASE = PartialModel.of(new ResourceLocation("cim", "block/electro_motor"));
    public static final PartialModel HALF_SHAFT = PartialModel.of(new ResourceLocation("cim", "block/half_shaft"));
    public static final PartialModel BEARING_INNER_RING = PartialModel.of(new ResourceLocation("cim", "block/bearing_shaft"));
    public static final PartialModel BEARING = PartialModel.of(new ResourceLocation("cim", "block/bearing"));



    public static void init() {
        // Динамически регистрируем модели для всех валов в игре
        for (RegistryObject<Block> blockObj : ModBlocks.BLOCKS.getEntries()) {
            if (blockObj.get() instanceof ShaftBlock) {
                ResourceLocation id = blockObj.getId();
                // Создаем PartialModel, ссылаясь на JSON, который сгенерирует DataGen
                SHAFT_MODELS.put(blockObj.get(), PartialModel.of(new ResourceLocation(id.getNamespace(), "block/" + id.getPath())));
            }
        }
    }

    public static PartialModel getShaftModelFor(ShaftMaterial material, ShaftDiameter diameter) {
        if (material == null || diameter == null) {
            return HALF_SHAFT;
        }

        // Комбинация 1: например, "shaft_light_iron"
        ResourceLocation id1 = new ResourceLocation("cim", "shaft_" + diameter.name + "_" + material.name());
        Block block1 = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id1);
        if (block1 != null && SHAFT_MODELS.containsKey(block1)) {
            return SHAFT_MODELS.get(block1);
        }

        // Комбинация 2: например, "iron_shaft_light" или "shaft_iron_light"
        ResourceLocation id2 = new ResourceLocation("cim", "shaft_" + material.name() + "_" + diameter.name);
        Block block2 = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(id2);
        if (block2 != null && SHAFT_MODELS.containsKey(block2)) {
            return SHAFT_MODELS.get(block2);
        }

        // Если все равно не нашел, выводим в консоль, чтобы точно узнать, какое имя мы ищем
        com.cim.main.CrustalIncursionMod.LOGGER.error("[Flywheel] Не найдена модель вала для ID: {} или {}", id1, id2);

        return HALF_SHAFT;
    }
}
