package com.cim.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class MultiblockManager {
    private static final Map<Level, MultiblockManager> INSTANCES = new HashMap<>();

    private final Level level;
    private final Map<BlockPos, MultiblockController> multiblocks = new HashMap<>();
    private final Map<BlockPos, BlockPos> componentToOrigin = new HashMap<>();

    public MultiblockManager(Level level) {
        this.level = level;
    }

    public static MultiblockManager getInstance(Level level) {
        return INSTANCES.computeIfAbsent(level, MultiblockManager::new);
    }

    public <T extends MultiblockController> T getOrCreateMultiblock(
            BlockPos clickedPos,
            BiFunction<Level, BlockPos, T> factory) {

        // Проверяем, не является ли эта позиция частью существующего мультиблока
        BlockPos existingOrigin = componentToOrigin.get(clickedPos);
        if (existingOrigin != null) {
            MultiblockController existing = multiblocks.get(existingOrigin);
            if (existing != null) {
                // Проверяем валидность при каждом использовании
                if (existing.validate()) {
                    return (T) existing;
                } else {
                    // Разрушаем невалидный мультиблок
                    removeMultiblock(existingOrigin);
                }
            }
        }

        // Пытаемся найти валидный мультиблок с origin вокруг clickedPos
        // Для 3x1x3 origin может быть от -2 до 0 по x и z
        for (int dx = -2; dx <= 0; dx++) {
            for (int dz = -2; dz <= 0; dz++) {
                BlockPos candidateOrigin = clickedPos.offset(dx, 0, dz);

                // Пропускаем если уже проверяли
                if (multiblocks.containsKey(candidateOrigin)) continue;

                T candidate = factory.apply(level, candidateOrigin);

                // Проверяем: структура валидна И clickedPos входит в неё
                if (candidate.validate() && candidate.isPartOfMultiblock(clickedPos)) {
                    multiblocks.put(candidateOrigin, candidate);
                    registerComponents(candidate);
                    return candidate;
                }
            }
        }

        return null;
    }

    private void registerComponents(MultiblockController multiblock) {
        BlockPos origin = multiblock.getOrigin();
        MultiblockPattern pattern = multiblock.getPattern();

        for (BlockPos pos : multiblock.componentPositions) {
            componentToOrigin.put(pos, origin);
        }
    }

    private void removeMultiblock(BlockPos origin) {
        MultiblockController multiblock = multiblocks.remove(origin);
        if (multiblock != null) {
            for (BlockPos pos : multiblock.componentPositions) {
                componentToOrigin.remove(pos);
            }
        }
    }

    public void onBlockBroken(BlockPos pos) {
        BlockPos origin = componentToOrigin.get(pos);
        if (origin != null) {
            MultiblockController multiblock = multiblocks.get(origin);
            if (multiblock != null) {
                multiblock.onBreak();
                removeMultiblock(origin);
            }
        }
    }

    public void tick() {
        // Удаляем невалидные мультиблоки
        var iterator = multiblocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().validate()) {
                entry.getValue().onBreak();
                // Удаляем компоненты
                for (BlockPos pos : entry.getValue().componentPositions) {
                    componentToOrigin.remove(pos);
                }
                iterator.remove();
            } else {
                entry.getValue().tick();
            }
        }
    }
}