package razchexlitiel.cim.worldgen.biome; // Поменяй на свой пакет

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import razchexlitiel.cim.block.basic.ModBlocks;
import razchexlitiel.cim.worldgen.biome.ModBiomes; // Твой класс с ключом биома

public class ModSurfaceRules {

    // Вспомогательный метод для удобства
    private static SurfaceRules.RuleSource makeStateRule(Block block) {
        return SurfaceRules.state(block.defaultBlockState());
    }

    public static SurfaceRules.RuleSource makeRules() {
        SurfaceRules.ConditionSource isSequoiaBiome = SurfaceRules.isBiome(ModBiomes.SEQUOIA_GROVE);

        // --- МАГИЯ ШУМА: Создаем реалистичные пятна на земле ---
        SurfaceRules.RuleSource groundMix = SurfaceRules.sequence(
                // Если шум от -0.9 до -0.5: Генерируем полянки Мха
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.9D, -0.5D), makeStateRule(Blocks.MOSS_BLOCK)),

                // Если шум от -0.2 до 0.2: Генерируем "тропинки" из Каменистой земли
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.2D, 0.2D), makeStateRule(Blocks.COARSE_DIRT)),

                // Если шум от 0.5 до 0.9: Генерируем островки Подзола
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.5D, 0.9D), makeStateRule(Blocks.PODZOL)),

                // Всё остальное пространство (фон) — заливаем твоей кастомной хвоей!
                makeStateRule(ModBlocks.SEQUOIA_BIOME_MOSS.get())
        );

        SurfaceRules.RuleSource sequoiaSurface = SurfaceRules.sequence(
                // Проверяем, что мы под открытым небом (чтобы не залить пещеры)
                SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
                        SurfaceRules.sequence(
                                // На самом полу используем наш "Шумный Микс"
                                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, groundMix),
                                // Прямо под полом оставляем обычную землю
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, makeStateRule(Blocks.DIRT))
                        )
                )
        );

        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(isSequoiaBiome, sequoiaSurface)
        );
    }
}
