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
import razchexlitiel.cim.worldgen.tree.custom.ModTrunkPlacerTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class MiniSequoiaTrunkPlacer extends TrunkPlacer {

    public static final Codec<MiniSequoiaTrunkPlacer> CODEC = RecordCodecBuilder.create(instance ->
            trunkPlacerParts(instance).apply(instance, MiniSequoiaTrunkPlacer::new));

    public MiniSequoiaTrunkPlacer(int baseHeight, int randomHeightA, int randomHeightB) {
        super(baseHeight, randomHeightA, randomHeightB);
    }

    @Override
    protected TrunkPlacerType<?> type() {
        return ModTrunkPlacerTypes.MINI_SEQUOIA_TRUNK.get();
    }

    @Override
    public List<FoliagePlacer.FoliageAttachment> placeTrunk(LevelSimulatedReader level, BiConsumer<BlockPos, BlockState> blockSetter, RandomSource random, int height, BlockPos pos, TreeConfiguration config) {
        setDirtAt(level, blockSetter, random, pos.below(), config);
        List<FoliagePlacer.FoliageAttachment> foliage = new ArrayList<>();

        // 1. Ствол
        for (int i = 0; i < height; i++) {
            placeLog(level, blockSetter, random, pos.above(i), config);
        }

        // 2. Макушка
        foliage.add(new FoliagePlacer.FoliageAttachment(pos.above(height), 0, false));

        // 3. Ветки крестом (Начинаем с 6 блока)
        int branchStart = 6;
        for (int i = branchStart; i < height - 1; i += 2) {
            // Структура 2-2-1
            int branchLength = (i >= height - 3) ? 1 : 2;

            // --- ШАГ А: Строим ветки из бревен и вешаем листву на их концы ---
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos branchPos = pos.above(i);
                for (int b = 1; b <= branchLength; b++) {
                    branchPos = branchPos.relative(dir);
                    placeLog(level, blockSetter, random, branchPos, config);
                }
                // Якорь листвы на конце ветки
                foliage.add(new FoliagePlacer.FoliageAttachment(branchPos.above(), 0, false));
            }

            // --- ШАГ Б: Заполняем листвой УГЛЫ (диагонали) между ветками ---
            // Берем уровень на 1 блок выше ветки (чтобы листва красиво ложилась в угол)
            BlockPos trunkTierPos = pos.above(i + 1);

            // Добавляем 4 дополнительных якоря для листвы по диагоналям от ствола!
            foliage.add(new FoliagePlacer.FoliageAttachment(trunkTierPos.offset(1, 0, 1), 0, false));   // Юго-Восток
            foliage.add(new FoliagePlacer.FoliageAttachment(trunkTierPos.offset(-1, 0, 1), 0, false));  // Юго-Запад
            foliage.add(new FoliagePlacer.FoliageAttachment(trunkTierPos.offset(1, 0, -1), 0, false));  // Северо-Восток
            foliage.add(new FoliagePlacer.FoliageAttachment(trunkTierPos.offset(-1, 0, -1), 0, false)); // Северо-Запад
        }
        return foliage;
    }
}