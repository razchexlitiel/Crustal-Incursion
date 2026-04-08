package com.cim.worldgen.feature;

import com.cim.api.vein.VeinManager;
import com.cim.api.vein.VeinManager.VeinType;

import com.cim.block.basic.ModBlocks;
import com.cim.block.entity.conglomerate.ConglomerateBlockEntity;
import com.cim.main.CrustalIncursionMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

class ConglomerateVeinFeature extends Feature<NoneFeatureConfiguration> {

    public ConglomerateVeinFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource rand = context.random();

        if (!(level instanceof ServerLevel serverLevel)) return false;

        // Параметры жилы
        int radius = 3 + rand.nextInt(4); // 3-6 блоков в радиусе
        int height = 2 + rand.nextInt(3); // 2-4 блока в высоту
        VeinType type = VeinType.values()[rand.nextInt(VeinType.values().length)];

        Set<BlockPos> veinBlocks = new HashSet<>();

        // Генерация эллипсоида
        for (int x = -radius; x <= radius; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Проверка эллипсоида
                    double dist = (x*x)/(double)(radius*radius) + (y*y)/(double)(height*height) + (z*z)/(double)(radius*radius);
                    if (dist > 1.0) continue;

                    BlockPos pos = origin.offset(x, y, z);

                    // Проверяем, что можно заменить (камень, глубинный сланец)
                    if (isReplaceable(level, pos)) {
                        veinBlocks.add(pos.immutable());
                    }
                }
            }
        }

        if (veinBlocks.size() < 10) return false; // Слишком маленькая жила

        // Регистрируем жилу в менеджере
        UUID veinId = VeinManager.get(serverLevel).registerVein(veinBlocks, type);

        // Ставим блоки
        for (BlockPos pos : veinBlocks) {
            // Сначала ставим блок
            level.setBlock(pos, ModBlocks.CONGLOMERATE.get().defaultBlockState(), 2);

            // Получаем BE (должен создаться автоматически при setBlock)
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof ConglomerateBlockEntity conglomerateBe) {
                conglomerateBe.setVeinId(veinId);
            } else {
                // Если BE не создался (редкий случай), логируем
                CrustalIncursionMod.LOGGER.warn("Failed to create ConglomerateBlockEntity at {}", pos);
            }
        }

        return true;
    }

    private boolean isReplaceable(WorldGenLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) ||
                level.getBlockState(pos).is(net.minecraft.tags.BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    }
}