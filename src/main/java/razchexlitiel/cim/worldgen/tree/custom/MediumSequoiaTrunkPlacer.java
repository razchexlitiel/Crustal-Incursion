package razchexlitiel.cim.worldgen.tree.custom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType;
import razchexlitiel.cim.block.basic.ModBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class MediumSequoiaTrunkPlacer extends TrunkPlacer {

    public static final Codec<MediumSequoiaTrunkPlacer> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.intRange(0, 100).fieldOf("base_height").forGetter(placer -> placer.baseHeight),
                    Codec.intRange(0, 50).fieldOf("height_rand_a").forGetter(placer -> placer.heightRandA),
                    Codec.intRange(0, 50).fieldOf("height_rand_b").forGetter(placer -> placer.heightRandB)
            ).apply(instance, MediumSequoiaTrunkPlacer::new));

    public MediumSequoiaTrunkPlacer(int baseHeight, int randomHeightA, int randomHeightB) {
        super(baseHeight, randomHeightA, randomHeightB);
    }

    @Override
    protected TrunkPlacerType<?> type() {
        return ModTrunkPlacerTypes.MEDIUM_SEQUOIA_TRUNK.get();
    }

    @Override
    public List<FoliagePlacer.FoliageAttachment> placeTrunk(LevelSimulatedReader level, BiConsumer<BlockPos, BlockState> blockSetter, RandomSource random, int height, BlockPos pos, TreeConfiguration config) {
        // Устанавливаем землю под ВСЕМ стволом 2x2
        setDirtAt(level, blockSetter, random, pos.below(), config);
        setDirtAt(level, blockSetter, random, pos.below().east(), config);
        setDirtAt(level, blockSetter, random, pos.below().south(), config);
        setDirtAt(level, blockSetter, random, pos.below().east().south(), config);

        List<FoliagePlacer.FoliageAttachment> foliage = new ArrayList<>();

        // 1. СТВОЛ 2x2 И ГРАДИЕНТ МХА
        for (int y = 0; y < height; y++) {
            float mossChance = Math.max(0.0f, 1.0f - (y / 15.0f));

            for (int x = 0; x <= 1; x++) {
                for (int z = 0; z <= 1; z++) {
                    BlockPos trunkPos = pos.offset(x, y, z);

                    if (random.nextFloat() < mossChance) {
                        blockSetter.accept(trunkPos, ModBlocks.SEQUOIA_BARK_MOSSY.get().defaultBlockState());
                    } else {
                        placeLog(level, blockSetter, random, trunkPos, config);
                    }
                }
            }
        }

        // Макушка
        foliage.add(new FoliagePlacer.FoliageAttachment(pos.above(height).offset(0, 0, 0), 0, false));
        foliage.add(new FoliagePlacer.FoliageAttachment(pos.above(height).offset(1, 0, 0), 0, false));
        foliage.add(new FoliagePlacer.FoliageAttachment(pos.above(height).offset(0, 0, 1), 0, false));
        foliage.add(new FoliagePlacer.FoliageAttachment(pos.above(height).offset(1, 0, 1), 0, false));

        // 2. ВЕТКИ И ИХ ХВОЯ
        int branchStart = height / 2;

        for (int y = branchStart; y < height - 2; y += 2) {
            float progress = (float) (y - branchStart) / (height - branchStart);
            int branchLength = Math.round(5 - (4 * progress));

            // Строим 4 ветки
            buildBranch(level, blockSetter, random, config, pos.offset(0, y, 0), Direction.NORTH, branchLength, foliage);
            buildBranch(level, blockSetter, random, config, pos.offset(1, y, 0), Direction.EAST, branchLength, foliage);
            buildBranch(level, blockSetter, random, config, pos.offset(1, y, 1), Direction.SOUTH, branchLength, foliage);
            buildBranch(level, blockSetter, random, config, pos.offset(0, y, 1), Direction.WEST, branchLength, foliage);

            // === 3. ПУЗАТЫЕ ШАПКИ ДЛЯ ЗАПОЛНЕНИЯ УГЛОВ (ИСПРАВЛЕНО!) ===
            BlockPos gapFillerPos = pos.above(y + 1);

            // МАГИЯ ОБЪЕМА: Чем длиннее ветки (внизу), тем огромнее будет шапка в углу!
            // Внизу она будет расширяться на +2 блока, а на макушке сузится до +0.
            int cornerRadiusOffset = branchLength / 2;

            // ИДЕАЛЬНЫЕ координаты пустот для ствола 2х2 (больше не пересекаются с ветками):
            foliage.add(new FoliagePlacer.FoliageAttachment(gapFillerPos.offset(1, 0, -1), cornerRadiusOffset, true)); // NE
            foliage.add(new FoliagePlacer.FoliageAttachment(gapFillerPos.offset(2, 0, 1), cornerRadiusOffset, true));  // SE
            foliage.add(new FoliagePlacer.FoliageAttachment(gapFillerPos.offset(0, 0, 2), cornerRadiusOffset, true));  // SW
            foliage.add(new FoliagePlacer.FoliageAttachment(gapFillerPos.offset(-1, 0, 0), cornerRadiusOffset, true)); // NW
        }

        return foliage;
    }

    private void buildBranch(LevelSimulatedReader level, BiConsumer<BlockPos, BlockState> blockSetter, RandomSource random, TreeConfiguration config, BlockPos startPos, Direction dir, int length, List<FoliagePlacer.FoliageAttachment> foliage) {
        BlockPos current = startPos;
        for (int i = 1; i <= length; i++) {
            current = current.relative(dir);
            placeLog(level, blockSetter, random, current, config);

            // Ставим якорь листвы на КАЖДОЙ верхней грани ветки!
            foliage.add(new FoliagePlacer.FoliageAttachment(current.above(), 0, false));
        }
    }
}