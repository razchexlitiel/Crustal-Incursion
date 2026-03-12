package com.cim.api.hive;

import com.cim.block.basic.ModBlocks;
import com.cim.block.entity.ModBlockEntities;
import com.cim.block.entity.hive.DepthWormNestBlockEntity;
import com.cim.block.entity.hive.HiveSoilBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class HiveNetwork {
    public final UUID id;
    public final Set<BlockPos> members = new HashSet<>();
    public final Map<BlockPos, Integer> wormCounts = new HashMap<>();
    public final Map<BlockPos, List<CompoundTag>> nestWormData = new HashMap<>();
    public int killsPool = 0;
    private long lastFedTime = 0;

    public enum HiveState {
        EXPANSION, DEFENSIVE, AGGRESSIVE, RECOVERY, STARVATION
    }

    private HiveState currentState = HiveState.EXPANSION;
    private int threatLevel = 0;
    private int expansionPressure = 0;
    private long lastStateChange = 0;
    private int successfulAttacks = 0;
    private final Set<BlockPos> dangerZones = new HashSet<>();

    // Новые поля для сценарного AI
    public int targetWormCount = 6;
    public int targetNestCount = 2;
    public long lastScenarioChange = 0;
    public int wormsSpawnedTotal = 0;
    public int nestsBuiltTotal = 0;

    // Лимиты расширения
    public int maxExpansionRadius = 8; // Максимальный радиус от центра улья
    public BlockPos hiveCenter = null; // Центр улья (первое гнездо)

    public enum DevelopmentScenario {
        STARTUP, RAPID_GROWTH, EXPAND_TERRITORY, BUILD_NESTS,
        CONSOLIDATE, AGGRESSIVE_PUSH, DEFENSIVE_BUILDUP
    }

    private DevelopmentScenario currentScenario = DevelopmentScenario.STARTUP;

    public final Map<BlockPos, Integer> activeWormCounts = new HashMap<>();

    public HiveNetwork(UUID id) {
        this.id = id;
    }

    public void addMember(BlockPos pos, boolean isNest) {
        members.add(pos);
        if (isNest) {
            wormCounts.put(pos, 0);
            nestWormData.put(pos, new ArrayList<>());
            // Устанавливаем центр улья при первом гнезде
            if (hiveCenter == null) {
                hiveCenter = pos.immutable();
                System.out.println("[Hive] Center established at " + hiveCenter);
            }
        }
    }

    public void clearNestWormData(BlockPos nestPos) {
        List<CompoundTag> data = nestWormData.get(nestPos);
        if (data != null) data.clear();
        wormCounts.put(nestPos, 0);
    }

    public void removeMember(BlockPos pos) {
        members.remove(pos);
        wormCounts.remove(pos);
        nestWormData.remove(pos);
    }

    public List<CompoundTag> getNestWormData(BlockPos nestPos) {
        return nestWormData.getOrDefault(nestPos, new ArrayList<>());
    }

    public void addWormDataToNest(BlockPos nestPos, CompoundTag wormData) {
        List<CompoundTag> data = nestWormData.computeIfAbsent(nestPos, k -> new ArrayList<>());
        if (data.size() >= 3) data.clear();
        data.add(wormData);
        wormCounts.put(nestPos, data.size());
    }

    public boolean isNest(Level level, BlockPos pos) {
        return members.contains(pos) && level.isLoaded(pos) &&
                level.getBlockState(pos).is(ModBlocks.DEPTH_WORM_NEST.get());
    }

    public int getTotalWorms() {
        int total = 0;
        for (List<CompoundTag> worms : nestWormData.values()) {
            total += worms.size();
        }
        return total;
    }

    public boolean isDead() {
        boolean noStoredWorms = getTotalWorms() == 0;
        boolean noActiveWorms = activeWormCounts.isEmpty() ||
                activeWormCounts.values().stream().allMatch(count -> count == 0);
        boolean noMembers = members.isEmpty();
        return noStoredWorms && noActiveWorms && noMembers;
    }

    public boolean isAbandoned() {
        boolean noStoredWorms = getTotalWorms() == 0;
        boolean noActiveWorms = activeWormCounts.isEmpty() ||
                activeWormCounts.values().stream().allMatch(count -> count == 0);
        boolean hasMembers = !members.isEmpty();
        boolean noResources = killsPool <= 0;
        return noStoredWorms && noActiveWorms && hasMembers && noResources;
    }

    public void addActiveWorm(BlockPos nestPos) {
        activeWormCounts.merge(nestPos, 1, Integer::sum);
        System.out.println("[Hive] Worm went active from nest " + nestPos +
                " | Active: " + activeWormCounts.get(nestPos) + " | Stored: " + wormCounts.getOrDefault(nestPos, 0));
    }

    public void removeActiveWorm(BlockPos nestPos) {
        activeWormCounts.merge(nestPos, -1, (old, delta) -> Math.max(0, old + delta));
        if (activeWormCounts.getOrDefault(nestPos, 0) <= 0) {
            activeWormCounts.remove(nestPos);
        }
    }

    public int getTotalWormsIncludingActive() {
        int stored = getTotalWorms();
        int active = activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();
        return stored + active;
    }

    public int getWormsFromNest(BlockPos nestPos) {
        int stored = wormCounts.getOrDefault(nestPos, 0);
        int active = activeWormCounts.getOrDefault(nestPos, 0);
        return stored + active;
    }

    // Проверка расстояния от центра улья
    public boolean isWithinExpansionLimit(BlockPos pos) {
        if (hiveCenter == null) return true;
        double dist = Math.sqrt(pos.distSqr(hiveCenter));
        return dist <= maxExpansionRadius;
    }

    // Поиск ближайшего гнезда к точке
    public BlockPos findNearestNest(BlockPos pos) {
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos nestPos : wormCounts.keySet()) {
            double dist = pos.distSqr(nestPos);
            if (dist < minDist) {
                minDist = dist;
                nearest = nestPos;
            }
        }
        return nearest;
    }

    public void update(Level level) {
        if (level.isClientSide) return;

        // ПРОВЕРКА: есть ли прогруженные чанки
        if (!hasAnyLoadedChunk(level)) {
            return; // Не тикаем если никого нет рядом
        }

        if (wormCounts.isEmpty()) {
            if (members.isEmpty()) return;
            if (currentState != HiveState.STARVATION) {
                System.out.println("[Hive " + id + "] No nests - network abandoned");
                currentState = HiveState.STARVATION;
                currentScenario = DevelopmentScenario.STARTUP;
            }
            return;
        }

        if (level.getGameTime() % 40 == 0) {
            int active = activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("[Hive Tick] Network " + this.id +
                    " | Scenario: " + currentScenario +
                    " | State: " + currentState +
                    " | Points: " + killsPool + " | Nodes: " + members.size() +
                    " | Nests: " + wormCounts.size() +
                    " | Stored: " + getTotalWorms() + " | Active: " + active +
                    " | Total: " + getTotalWormsIncludingActive() + " | Threat: " + threatLevel +
                    " | Radius: " + (hiveCenter != null ? Math.sqrt(members.iterator().next().distSqr(hiveCenter)) : 0));

            if (members.isEmpty()) {
                System.out.println("[Hive Tick] ERROR: Member list empty!");
                return;
            }
            makeDecisions(level);
        }
    }

    // ГЛАВНЫЙ МЕТОД ПРИНЯТИЯ РЕШЕНИЙ
    private void makeDecisions(Level level) {
        if (killsPool <= 0 && !members.isEmpty()) {
            enterStarvationMode(level);
            return;
        }
        if (killsPool <= 0) return;

        // Анализируем и выбираем сценарий каждые 5 секунд
        if (level.getGameTime() % 100 == 0 || currentScenario == null) {
            analyzeAndChooseScenario(level);
        }

        // Выполняем текущий сценарий
        executeScenario(level);

        // Пробуем улучшить почву в гнезда (без принудительной необходимости)
        if (killsPool >= 15 && wormCounts.size() < 8 && level.getGameTime() % 20 == 0) {
            tryUpgradeSoilToNest(level, false);
        }
    }


    // Попытка автоматического улучшения почвы в гнездо
// Теперь с проверкой: строим ядро только если НУЖНО место для нового червя
    private void tryUpgradeSoilToNest(Level level, boolean needSpaceForNewWorm) {
        if (killsPool < 15 || wormCounts.size() >= 8) return;

        // Проверяем - нужно ли вообще новое ядро?
        int totalWorms = getTotalWormsIncludingActive();
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        if (totalWorms < maxCapacity && !needSpaceForNewWorm) {
            System.out.println("[Hive Debug] No need for new nest - capacity: " + totalWorms + "/" + maxCapacity);
            return;
        }

        System.out.println("[Hive Debug] Trying to upgrade soil (need space: " + needSpaceForNewWorm +
                "), worms: " + totalWorms + "/" + maxCapacity);

        // Инициализируем центр если нужно
        if (hiveCenter == null && !wormCounts.isEmpty()) {
            hiveCenter = wormCounts.keySet().iterator().next().immutable();
            System.out.println("[Hive] Center auto-established at " + hiveCenter);
        }

        System.out.println("[Hive Debug] Trying to upgrade soil (need space: " + needSpaceForNewWorm +
                "), worms: " + totalWorms + "/" + maxCapacity);

        // Ищем почву, готовую к улучшению
        for (BlockPos soilPos : new ArrayList<>(members)) {
            if (wormCounts.containsKey(soilPos)) continue;

            // Проверяем - есть ли соседнее гнездо
            boolean adjacentToNest = false;
            BlockPos adjacentNestPos = null;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = soilPos.relative(dir);
                if (wormCounts.containsKey(neighbor)) {
                    adjacentToNest = true;
                    adjacentNestPos = neighbor;
                    break;
                }
            }

            // Проверяем расстояние до ДРУГИХ гнезд (кроме соседнего)
            boolean tooCloseToOtherNests = false;
            if (adjacentToNest) {
                for (BlockPos nestPos : wormCounts.keySet()) {
                    if (nestPos.equals(adjacentNestPos)) continue;
                    if (soilPos.distSqr(nestPos) < 4) {
                        tooCloseToOtherNests = true;
                        break;
                    }
                }
            }

            boolean goodLocation = adjacentToNest && !tooCloseToOtherNests && isWithinExpansionLimit(soilPos);
            if (!goodLocation) continue;

            System.out.println("[Hive] Upgrading soil to nest at " + soilPos + " (adjacent to: " + adjacentNestPos + ")");
            upgradeSoilToNest(level, soilPos);
            return;
        }

        System.out.println("[Hive Debug] No suitable soil found for upgrade");
    }

    // Максимально упрощенная проверка
    private boolean isGoodNestLocationQuick(Level level, BlockPos pos) {
        return isWithinExpansionLimit(pos);
    }

    // АНАЛИЗ И ВЫБОР СЦЕНАРИЯ
    private void analyzeAndChooseScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive();
        int nests = wormCounts.size();
        int nodes = members.size();
        int maxCapacity = nests * 3;

        double territoryEfficiency = nodes > 0 ? (double) totalWorms / nodes : 0;
        double nestUtilization = nests > 0 ? (double) totalWorms / maxCapacity : 0;

        System.out.println("[Hive AI] Analysis: Worms=" + totalWorms + "/" + maxCapacity +
                " Nests=" + nests + "/" + targetNestCount +
                " Nodes=" + nodes +
                " Efficiency=" + String.format("%.2f", territoryEfficiency) +
                " Utilization=" + String.format("%.2f", nestUtilization) +
                " Points=" + killsPool);

        DevelopmentScenario newScenario = currentScenario;

        // 1. КРИТИЧЕСКИ: Нет места для червяков - строим ядро СРОЧНО
        if (totalWorms >= maxCapacity - 1 && nests < 8 && killsPool >= 15) {
            newScenario = DevelopmentScenario.BUILD_NESTS;
            targetNestCount = Math.min(8, nests + 1);
        }
        // 2. Угроза - оборона (приоритет)
        else if (threatLevel > 15) {
            newScenario = DevelopmentScenario.DEFENSIVE_BUILDUP;
        }
        // 3. Мало червяков но есть очки - быстрый рост
        else if (totalWorms < 4 && killsPool >= 15 && nests > 0) {
            newScenario = DevelopmentScenario.RAPID_GROWTH;
            targetWormCount = Math.min(maxCapacity - 1, 6);
        }
        // 4. Много червяков, мало территории - расширяемся компактно
        else if (territoryEfficiency > 0.30 && nodes < nests * 4 && killsPool >= 5) {
            newScenario = DevelopmentScenario.EXPAND_TERRITORY;
        }
        // 5. Мало ядер относительно потенциала - строим
        else if (nests < 3 && killsPool >= 20 && totalWorms >= nests * 2) {
            newScenario = DevelopmentScenario.BUILD_NESTS;
            targetNestCount = Math.min(3, nests + 1);
        }
        // 6. Много очков - агрессивное развитие
        else if (killsPool > 30 && totalWorms >= 4) {
            newScenario = DevelopmentScenario.AGGRESSIVE_PUSH;
            targetWormCount = Math.min(maxCapacity, 12);
        }
        // 7. Всё хорошо - консолидация
        else if (totalWorms >= 3 && nests >= 2 && territoryEfficiency < 0.20) {
            newScenario = DevelopmentScenario.CONSOLIDATE;
        }
        // 8. По умолчанию - стартап если мало всего
        else if (nests == 1 && totalWorms < 3) {
            newScenario = DevelopmentScenario.STARTUP;
        }

        if (newScenario != currentScenario) {
            currentScenario = newScenario;
            lastScenarioChange = level.getGameTime();
            System.out.println("[Hive AI] SCENARIO CHANGED TO: " + currentScenario);
        } else {
            System.out.println("[Hive AI] Scenario: " + currentScenario + " (continuing)");
        }
    }

    // ВЫПОЛНЕНИЕ СЦЕНАРИЯ
    private void executeScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive();
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        switch (currentScenario) {
            case STARTUP -> {
                currentState = HiveState.EXPANSION;
                executeStartup(level, totalWorms, nests);
            }
            case RAPID_GROWTH -> {
                currentState = HiveState.AGGRESSIVE;
                executeRapidGrowth(level, totalWorms, maxCapacity);
            }
            case EXPAND_TERRITORY -> {
                currentState = HiveState.EXPANSION;
                executeExpandTerritory(level, totalWorms, nests);
            }
            case BUILD_NESTS -> {
                currentState = HiveState.EXPANSION;
                executeBuildNests(level, totalWorms, nests);
            }
            case CONSOLIDATE -> {
                currentState = HiveState.EXPANSION;
                executeConsolidate(level);
            }
            case AGGRESSIVE_PUSH -> {
                currentState = HiveState.AGGRESSIVE;
                executeAggressivePush(level, totalWorms, maxCapacity);
            }
            case DEFENSIVE_BUILDUP -> {
                currentState = HiveState.DEFENSIVE;
                executeDefensiveBuildup(level, totalWorms, nests);
            }
        }
    }

    private void executeStartup(Level level, int totalWorms, int nests) {
        // Фаза 1: Спавним червяков пока есть очки
        if (totalWorms < 3 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // Фаза 2: Компактное расширение для второго гнезда
        if (nests == 1 && killsPool >= 5 && level.getGameTime() % 60 == 0) {
            tryExpandCompact(level, 1);
            return;
        }

        // Фаза 3: Пробуем улучшить почву в гнездо (автоматически через tryUpgradeSoilToNest)
        // Если получилось - перейдём в RAPID_GROWTH
        if (nests >= 2 && totalWorms >= 2) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    private void executeRapidGrowth(Level level, int totalWorms, int maxCapacity) {
        // Спавним пока есть место и очки
        if (totalWorms < targetWormCount && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // Если полны — пробуем построить новое гнездо для продолжения роста
        if (totalWorms >= maxCapacity && killsPool >= 15 && wormCounts.size() < 8) {
            System.out.println("[Hive] Full capacity, trying to expand for more growth");
            tryUpgradeSoilToNest(level, true); // true = нужно место для нового червя

            // Если построили — переключаемся на BUILD_NESTS
            if (wormCounts.size() > maxCapacity / 3) { // появилось новое гнездо
                currentScenario = DevelopmentScenario.BUILD_NESTS;
            } else {
                // Не получилось построить — консолидируем
                currentScenario = DevelopmentScenario.CONSOLIDATE;
            }
            return;
        }

        // Если полны и не можем строить — консолидируем
        if (totalWorms >= maxCapacity) {
            currentScenario = DevelopmentScenario.CONSOLIDATE;
        }
    }


    private void executeExpandTerritory(Level level, int totalWorms, int nests) {
        // Компактное расширение - не дальше 8 блоков от центра
        // Цель: создать "платформу" для новых гнезд рядом с существующими

        int currentNodes = members.size();
        int targetNodes = nests * 4; // Меньше чем было (было 5-6)

        if (currentNodes >= targetNodes || killsPool < 5) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
            return;
        }

        // Расширяемся не чаще раза в 50 тиков, компактно
        if (level.getGameTime() % 50 == 0) {
            tryExpandCompact(level, 1);
        }
    }

    private void executeBuildNests(Level level, int totalWorms, int nests) {
        // Сначала пробуем улучшить существующую почву (теперь с 2 аргументами!)
        // false = не требуется срочно место для червя, просто проверяем возможность
        tryUpgradeSoilToNest(level, false);

        // Если не получилось и мало очков - расширяемся
        if (wormCounts.size() == nests && killsPool >= 5 && level.getGameTime() % 40 == 0) {
            // Проверяем, есть ли кандидаты на улучшение
            boolean hasCandidate = false;
            for (BlockPos soilPos : members) {
                if (wormCounts.containsKey(soilPos)) continue;

                boolean adjacentToNest = false;
                for (Direction dir : Direction.values()) {
                    if (wormCounts.containsKey(soilPos.relative(dir))) {
                        adjacentToNest = true;
                        break;
                    }
                }

                if (adjacentToNest && isGoodNestLocationQuick(level, soilPos)) {
                    hasCandidate = true;
                    break;
                }
            }

            // Если нет кандидатов - расширяемся чтобы создать
            if (!hasCandidate) {
                System.out.println("[Hive] No upgrade candidates, expanding to create more soil");
                tryExpandCompact(level, 1);
            }
        }

        // Если построили достаточно - возвращаемся к росту
        if (wormCounts.size() >= targetNestCount) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    private void executeConsolidate(Level level) {
        int totalWorms = getTotalWormsIncludingActive();

        // Докапываем до целевого числа если кто-то погиб
        if (totalWorms < targetWormCount && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // Очень много очков - можно позволить агрессию
        if (killsPool > 40) {
            currentScenario = DevelopmentScenario.AGGRESSIVE_PUSH;
        }
    }

    private void executeAggressivePush(Level level, int totalWorms, int maxCapacity) {
        // Спавним до лимита
        if (totalWorms < maxCapacity && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // ВСЕ заполнены - пробуем построить ядро только если хотим ещё червяков
        if (totalWorms >= maxCapacity && wormCounts.size() < 8 && killsPool >= 15) {
            // Проверяем - хотим ли мы ещё червяков?
            if (targetWormCount > totalWorms) {
                tryUpgradeSoilToNest(level, true);
            }
        }

        // Атакуем если есть готовые
        if (getTotalWorms() >= 2 && killsPool >= 3) {
            executeCoordinatedAttack(level);
        }
    }

    private void executeDefensiveBuildup(Level level, int totalWorms, int nests) {
        // Заполняем гнезда червяками (резерв для защиты)
        int readyWorms = getTotalWorms();
        if (readyWorms < nests * 2 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // Минимальное расширение только если критично
        if (nests == 1 && members.size() < 4 && killsPool >= 5 && level.getGameTime() % 100 == 0) {
            tryExpandCompact(level, 1);
        }

        // Проверяем угрозу - если прошла, возвращаемся к росту
        if (threatLevel < 5 && killsPool > 15) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    // КОМПАКТНОЕ РАСШИРЕНИЕ (с проверкой расстояния от центра)
    private void tryExpandCompact(Level level) {
        tryExpandCompact(level, 1);
    }

    private void tryExpandCompact(Level level, int maxExpansions) {
        if (killsPool < 5) return;

        // Находим гнездо, ближайшее к центру (для компактности)
        BlockPos bestNest = findNestNearestToCenter(level);
        if (bestNest == null) return;

        // Ищем место с приоритетом: ближе к центру > горизонталь > вертикаль
        BlockPos target = findAdjacentSpotCompact(level, bestNest);
        if (target != null && canPlaceSoilSafely(level, target, this.id)) {
            // Дополнительная проверка расстояния
            if (!isWithinExpansionLimit(target)) {
                System.out.println("[Hive] Expansion limit reached at " + target + " (max radius: " + maxExpansionRadius + ")");
                return;
            }

            placeHiveSoil(level, target, this.id);
            killsPool -= 5;
            System.out.println("[Hive] Compact expansion at " + target + " (dist from center: " +
                    Math.sqrt(target.distSqr(hiveCenter)) + ") | Points: " + killsPool);
        }
    }

    // Поиск гнезда, ближайшего к центру (для компактного роста)
    private BlockPos findNestNearestToCenter(Level level) {
        BlockPos fallback = findNestNeedingExpansion(level);
        if (hiveCenter == null) return fallback;

        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;

        for (BlockPos nestPos : wormCounts.keySet()) {
            if (!level.isLoaded(nestPos)) continue;

            // Проверка валидности позиции
            if (nestPos == null) continue;

            double distToCenter = nestPos.distSqr(hiveCenter);
            int neighbors = countNetworkNeighbors(level, nestPos);
            double score = distToCenter + neighbors * 10;

            if (score < minDist) {
                minDist = score;
                nearest = nestPos;
            }
        }

        // Если не нашли подходящего — возвращаем fallback
        return nearest != null ? nearest : fallback;
    }

    // Поиск места с приоритетом компактности
    private BlockPos findAdjacentSpotCompact(Level level, BlockPos center) {
        // Приоритет 1: Направления К ЦЕНТРУ (для компактности)
        if (hiveCenter != null) {
            Vec3 toCenter = new Vec3(hiveCenter.getX() - center.getX(), 0, hiveCenter.getZ() - center.getZ()).normalize();

            // Выбираем направление ближе всего к вектору на центр
            Direction bestDir = null;
            double bestDot = -1;

            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                Vec3 dirVec = new Vec3(dir.getStepX(), 0, dir.getStepZ());
                double dot = dirVec.dot(toCenter);
                if (dot > bestDot) {
                    bestDot = dot;
                    bestDir = dir;
                }
            }

            if (bestDir != null) {
                BlockPos target = center.relative(bestDir);
                if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                    return target;
                }
            }
        }

        // Приоритет 2: Горизонталь как обычно
        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : horizontal) {
            BlockPos target = center.relative(dir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

        // Приоритет 3: Вертикаль (вниз предпочтительнее чем вверх для стабильности)
        for (Direction dir : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockPos target = center.relative(dir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

        // Приоритет 4: От других членов, но ближе к центру
        BlockPos nearestMember = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos member : members) {
            if (wormCounts.containsKey(member)) continue;
            double dist = member.distSqr(hiveCenter != null ? hiveCenter : center);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestMember = member;
            }
        }

        if (nearestMember != null) {
            for (Direction dir : horizontal) {
                BlockPos target = nearestMember.relative(dir);
                if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                    return target;
                }
            }
        }

        return null;
    }

    // УПРОЩЕННОЕ УЛУЧШЕНИЕ ПОЧВЫ В ГНЕЗДО
    public void tryAutoUpgradeSoil(Level level, BlockPos soilPos) {
        if (killsPool < 15 || wormCounts.size() >= 8) return;
        if (!members.contains(soilPos)) return;
        if (wormCounts.containsKey(soilPos)) return; // Уже гнездо

        // Проверяем - подходит ли место
        if (!isGoodNestLocationQuick(level, soilPos)) return;

        // Проверяем связность с сетью
        boolean hasNestNeighbor = false;
        for (Direction dir : Direction.values()) {
            if (wormCounts.containsKey(soilPos.relative(dir))) {
                hasNestNeighbor = true;
                break;
            }
        }

        if (!hasNestNeighbor) return;

        System.out.println("[Hive] Auto-upgrading soil at " + soilPos);
        upgradeSoilToNest(level, soilPos);
    }

    // СТАРЫЕ МЕТОДЫ (сохранены, но адаптированы)
    private void enterStarvationMode(Level level) {
        if (currentState != HiveState.STARVATION) {
            currentState = HiveState.STARVATION;
            System.out.println("[Hive " + id + "] CRITICAL HUNGER! Survival mode activated.");
        }
    }

    private int calculateThreatLevel(Level level) {
        int threats = 0;
        for (BlockPos pos : members) {
            if (!level.isLoaded(pos)) continue;
            AABB area = new AABB(pos).inflate(20);
            List<Player> players = level.getEntitiesOfClass(Player.class, area,
                    p -> !p.isCreative() && !p.isSpectator());
            threats += players.size() * 10;

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DepthWormNestBlockEntity nest && nest.hasInjuredWorms()) {
                threats += 5;
            }
        }
        return threats;
    }

    private BlockPos findAdjacentSpot(Level level, BlockPos center) {
        return findAdjacentSpotCompact(level, center); // Используем компактную версию
    }

    private boolean canPlaceSoilSafely(Level level, BlockPos pos, UUID networkId) {
        if (!isValidExpansionTarget(level, pos)) return false;
        if (!isWithinExpansionLimit(pos)) return false; // Проверка лимита

        boolean hasNetworkNeighbor = false;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be instanceof HiveNetworkMember member && networkId.equals(member.getNetworkId())) {
                hasNetworkNeighbor = true;
                break;
            }
        }

        if (!hasNetworkNeighbor) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isAir() || level.getBlockEntity(below) instanceof HiveNetworkMember) {
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos diagonal = pos.relative(dir).below();
                    BlockEntity be = level.getBlockEntity(diagonal);
                    if (be instanceof HiveNetworkMember member && networkId.equals(member.getNetworkId())) {
                        hasNetworkNeighbor = true;
                        break;
                    }
                }
            }
        }

        return hasNetworkNeighbor;
    }

    private BlockPos findNestNeedingExpansion(Level level) {
        BlockPos bestNest = null;
        int minNeighbors = Integer.MAX_VALUE;

        for (BlockPos nestPos : wormCounts.keySet()) {
            if (!level.isLoaded(nestPos)) continue;
            int neighbors = countNetworkNeighbors(level, nestPos);
            if (neighbors < minNeighbors) {
                minNeighbors = neighbors;
                bestNest = nestPos;
            }
        }
        return bestNest;
    }

    private int countNetworkNeighbors(Level level, BlockPos pos) {
        int count = 0;
        for (Direction dir : Direction.values()) {
            BlockPos check = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(check);
            if (be instanceof HiveNetworkMember member && this.id.equals(member.getNetworkId())) {
                count++;
            }
        }
        return count;
    }

    private boolean isValidExpansionTarget(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() &&
                !state.is(ModBlocks.HIVE_SOIL.get()) &&
                !state.is(ModBlocks.DEPTH_WORM_NEST.get()) &&
                !state.is(ModBlocks.HIVE_SOIL_DEAD.get()) &&
                !state.is(ModBlocks.DEPTH_WORM_NEST_DEAD.get()) &&
                state.getDestroySpeed(level, pos) >= 0;
    }
    private void placeHiveSoil(Level level, BlockPos pos, UUID networkId) {
        level.setBlock(pos, ModBlocks.HIVE_SOIL.get().defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof HiveSoilBlockEntity soil) {
            soil.setNetworkId(networkId);
            HiveNetworkManager manager = HiveNetworkManager.get(level);
            if (manager != null) manager.addNode(networkId, pos, false);

            // НЕ вызываем tryAutoUpgradeSoil здесь - пусть makeDecisions решает
        }
    }

    // УПРОЩЕННОЕ СТРОИТЕЛЬСТВО ГНЕЗДА
    private boolean buildNewNestIfPossible(Level level) {
        if (wormCounts.size() >= 8 || killsPool < 15) return false;

        // Ищем подходящую почву вплотную к гнездам (упрощенная проверка)
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos soilPos : new ArrayList<>(members)) {
            if (wormCounts.containsKey(soilPos)) continue;
            if (!isWithinExpansionLimit(soilPos)) continue;

            // Простая проверка: есть ли рядом гнездо
            boolean adjacentToNest = false;
            for (Direction dir : Direction.values()) {
                if (wormCounts.containsKey(soilPos.relative(dir))) {
                    adjacentToNest = true;
                    break;
                }
            }

            if (adjacentToNest && isGoodNestLocationQuick(level, soilPos)) {
                candidates.add(soilPos);
            }
        }

        if (!candidates.isEmpty()) {
            // Выбираем ближайшую к центру
            BlockPos chosen = candidates.get(0);
            if (hiveCenter != null) {
                double minDist = chosen.distSqr(hiveCenter);
                for (BlockPos pos : candidates) {
                    double dist = pos.distSqr(hiveCenter);
                    if (dist < minDist) {
                        minDist = dist;
                        chosen = pos;
                    }
                }
            }

            upgradeSoilToNest(level, chosen);
            return true;
        }

        // Нет подходящей почвы - создаём новую рядом с гнездом
        for (BlockPos nestPos : wormCounts.keySet()) {
            if (!level.isLoaded(nestPos)) continue;
            for (Direction dir : Direction.values()) {
                BlockPos target = nestPos.relative(dir);
                if (canPlaceSoilSafely(level, target, this.id)) {
                    placeHiveSoil(level, target, this.id);
                    killsPool -= 5;
                    // После установки почвы tryAutoUpgradeSoil попробует улучшить её
                    return killsPool >= 15; // true если хватит очков на улучшение
                }
            }
        }
        return false;
    }

    private void upgradeSoilToNest(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HiveSoilBlockEntity soil)) {
            System.out.println("[Hive] ERROR: No soil at " + pos + " for upgrade");
            return;
        }

        // СОХРАНЯЕМ UUID ИЗ ПОЧВЫ!
        UUID preservedId = soil.getNetworkId();
        if (preservedId == null) {
            System.out.println("[Hive] ERROR: Soil has no network ID at " + pos);
            return;
        }
        if (!preservedId.equals(this.id)) {
            System.out.println("[Hive] ERROR: Soil belongs to different network " + preservedId + " vs " + this.id);
            return;
        }
        if (wormCounts.size() >= 8) {
            System.out.println("[Hive] Maximum nest count reached (" + wormCounts.size() + ")");
            return;
        }
        if (!hasNetworkNeighbor(level, pos)) {
            System.out.println("[Hive] ERROR: Cannot upgrade isolated soil at " + pos);
            return;
        }

        System.out.println("[Hive] Upgrading soil at " + pos + " to nest for network " + preservedId);

        // Удаляем из members как почву (будет добавлено как гнездо)
        members.remove(pos);

        // Устанавливаем новый блок - это удалит старый BlockEntity!
        level.setBlock(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState(), 3);

        // Создаём новый BlockEntity с СОХРАНЁННЫМ UUID
        BlockEntity newBe = ModBlockEntities.DEPTH_WORM_NEST.get().create(pos,
                ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState());

        if (!(newBe instanceof DepthWormNestBlockEntity nest)) {
            System.out.println("[Hive] CRITICAL ERROR: Failed to create nest BlockEntity at " + pos);
            return;
        }

        // УСТАНАВЛИВАЕМ СОХРАНЁННЫЙ UUID!
        nest.setNetworkId(preservedId);
        level.setBlockEntity(nest);

        // Проверяем что UUID сохранился
        BlockEntity verify = level.getBlockEntity(pos);
        if (!(verify instanceof DepthWormNestBlockEntity) ||
                !preservedId.equals(((DepthWormNestBlockEntity)verify).getNetworkId())) {
            System.out.println("[Hive] CRITICAL ERROR: UUID not preserved after upgrade at " + pos);
            return;
        }

        // Регистрируем в сети как гнездо
        wormCounts.put(pos, 0);
        nestWormData.put(pos, new ArrayList<>());
        members.add(pos);
        nestsBuiltTotal++;
        killsPool -= 15;

        System.out.println("[Hive] New nest at " + pos + " for network " + preservedId +
                ". Total nests: " + wormCounts.size());

        // Уведомляем менеджер
        HiveNetworkManager manager = HiveNetworkManager.get(level);
        if (manager != null) {
            manager.addNode(preservedId, pos, true);
        }
    }

    private boolean hasNetworkNeighbor(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be instanceof HiveNetworkMember member && this.id.equals(member.getNetworkId())) {
                return true;
            }
        }
        return false;
    }

    // УПРОЩЕННЫЙ СПАВН ЧЕРВЯКОВ
    // Поле класса для отслеживания глубины рекурсии
    private transient int spawnRecursionDepth = 0;
    private static final int MAX_SPAWN_RECURSION = 3;

    private void spawnNewWormOptimally(Level level) {
        // Guard: проверяем глубину рекурсии
        if (spawnRecursionDepth >= MAX_SPAWN_RECURSION) {
            System.out.println("[Hive] Max spawn recursion reached, aborting");
            spawnRecursionDepth = 0;
            return;
        }

        spawnRecursionDepth++;

        try {
            BlockPos bestNest = null;
            int minWorms = Integer.MAX_VALUE;
            int nests = wormCounts.size();
            int totalWorms = getTotalWormsIncludingActive();
            int maxCapacity = nests * 3;

            // Проверяем — есть ли место вообще?
            if (totalWorms >= maxCapacity) {
                // ВСЕ ядра заполнены — нужно новое ядро!
                System.out.println("[Hive] All nests full (" + totalWorms + "/" + maxCapacity + "), need new nest");

                if (killsPool >= 15 && wormCounts.size() < 8) {
                    int nestsBefore = wormCounts.size();
                    tryUpgradeSoilToNest(level, true);

                    // Проверяем получилось ли (сравниваем количество ДО и ПОСЛЕ)
                    if (wormCounts.size() > nestsBefore) {
                        // Новое ядро построено! Пробуем снова (рекурсия с защитой)
                        spawnNewWormOptimally(level);
                        return;
                    }
                }

                System.out.println("[Hive] Cannot build new nest, waiting...");
                return;
            }

            // Находим гнездо с минимальным количеством червяков
            for (Map.Entry<BlockPos, Integer> entry : wormCounts.entrySet()) {
                BlockPos nestPos = entry.getKey();
                int stored = entry.getValue();
                int active = activeWormCounts.getOrDefault(nestPos, 0);
                int totalAtNest = stored + active;

                if (totalAtNest < minWorms && totalAtNest < 3) {
                    BlockEntity be = level.getBlockEntity(nestPos);
                    if (be instanceof DepthWormNestBlockEntity nest && !nest.isFull()) {
                        bestNest = nestPos;
                        minWorms = totalAtNest;
                    }
                }
            }

            if (bestNest != null && killsPool >= 10) {
                BlockEntity be = level.getBlockEntity(bestNest);
                if (be instanceof DepthWormNestBlockEntity nest) {
                    CompoundTag newWorm = new CompoundTag();
                    newWorm.putFloat("Health", 15.0F);
                    newWorm.putInt("Kills", 0);

                    nest.addWormTag(newWorm);
                    addWormDataToNest(bestNest, newWorm);
                    wormCounts.put(bestNest, wormCounts.getOrDefault(bestNest, 0) + 1);

                    wormsSpawnedTotal++;
                    killsPool -= 10;

                    int totalNow = getTotalWormsIncludingActive();
                    System.out.println("[Hive] Spawned worm at " + bestNest +
                            " | Stored: " + wormCounts.get(bestNest) +
                            " | Active: " + activeWormCounts.getOrDefault(bestNest, 0) +
                            " | Total: " + totalNow + "/" + (nests * 3) +
                            " | Points: " + killsPool);
                }
            } else if (bestNest == null && killsPool >= 10) {
                // Нет гнезда с местом, но есть очки — пробуем построить ядро
                System.out.println("[Hive] No nest with space, trying to build new nest...");
                if (killsPool >= 15 && wormCounts.size() < 8) {
                    tryUpgradeSoilToNest(level, true);
                }
            }
        } finally {
            // Сбрасываем счётчик при выходе
            spawnRecursionDepth = 0;
        }
    }

    private void executeCoordinatedAttack(Level level) {
        LivingEntity target = findBestTarget(level);
        if (target == null) return;

        List<BlockPos> nearbyNests = findNestsNearTarget(level, target.blockPosition(), 25);
        int released = 0;

        for (BlockPos nestPos : nearbyNests) {
            if (released >= 2) break;
            BlockEntity be = level.getBlockEntity(nestPos);
            if (be instanceof DepthWormNestBlockEntity nest && !nest.getStoredWorms().isEmpty()) {
                nest.releaseWorms(nestPos, target);
                released++;
            }
        }

        if (released > 0) {
            killsPool = Math.max(0, killsPool - released);
            successfulAttacks++;
            System.out.println("[Hive] Attack! Released: " + released +
                    " towards " + target.getName().getString());
        }
    }

    private LivingEntity findBestTarget(Level level) {
        LivingEntity bestTarget = null;
        double bestScore = -1;

        for (BlockPos pos : members) {
            if (!level.isLoaded(pos)) continue;
            AABB area = new AABB(pos).inflate(30);
            List<LivingEntity> potential = level.getEntitiesOfClass(LivingEntity.class, area,
                    e -> e instanceof Player && !((Player)e).isCreative() && !((Player)e).isSpectator() && e.isAlive());

            for (LivingEntity target : potential) {
                double dist = pos.distSqr(target.blockPosition());
                double score = 1000.0 / (dist + 1);
                if (potential.size() == 1) score *= 1.5;

                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = target;
                }
            }
        }
        return bestTarget;
    }

    private List<BlockPos> findNestsNearTarget(Level level, BlockPos targetPos, double maxDist) {
        List<BlockPos> result = new ArrayList<>();
        double maxDistSq = maxDist * maxDist;

        for (BlockPos nestPos : wormCounts.keySet()) {
            if (nestPos.distSqr(targetPos) <= maxDistSq) {
                BlockEntity be = level.getBlockEntity(nestPos);
                if (be instanceof DepthWormNestBlockEntity nest && !nest.getStoredWorms().isEmpty()) {
                    result.add(nestPos);
                }
            }
        }
        result.sort(Comparator.comparingDouble(p -> p.distSqr(targetPos)));
        return result;
    }

    public boolean hasAnyLoadedChunk(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;

        ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
        for (BlockPos pos : members) {
            ChunkPos chunkPos = new ChunkPos(pos);
            if (!chunkMap.getPlayers(chunkPos, false).isEmpty()) return true;
        }
        return false;
    }

    public void updateWormCount(BlockPos nestPos, int delta) {
        wormCounts.merge(nestPos, delta, Integer::sum);
        if (wormCounts.get(nestPos) < 0) wormCounts.put(nestPos, 0);
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("KillsPool", this.killsPool);
        tag.putLong("LastFed", this.lastFedTime);
        tag.putString("CurrentState", this.currentState.name());
        tag.putString("Scenario", this.currentScenario.name());
        tag.putInt("TargetWorms", this.targetWormCount);
        tag.putInt("TargetNests", this.targetNestCount);
        tag.putInt("MaxRadius", this.maxExpansionRadius);
        if (this.hiveCenter != null) {
            tag.putLong("HiveCenter", this.hiveCenter.asLong());
        }
        tag.putInt("ThreatLevel", this.threatLevel);
        tag.putInt("ExpansionPressure", this.expansionPressure);
        tag.putLong("LastStateChange", this.lastStateChange);
        tag.putInt("SuccessfulAttacks", this.successfulAttacks);

        ListTag membersList = new ListTag();
        for (BlockPos p : members) {
            CompoundTag pTag = NbtUtils.writeBlockPos(p);
            pTag.putBoolean("IsNest", wormCounts.containsKey(p));
            if (wormCounts.containsKey(p)) {
                pTag.putInt("WormCount", wormCounts.get(p));
                ListTag wormDataList = new ListTag();
                List<CompoundTag> wormData = nestWormData.get(p);
                if (wormData != null) wormDataList.addAll(wormData);
                pTag.put("WormData", wormDataList);
            }
            membersList.add(pTag);
        }
        tag.put("Members", membersList);

        ListTag dangerList = new ListTag();
        for (BlockPos p : dangerZones) {
            dangerList.add(NbtUtils.writeBlockPos(p));
        }
        tag.put("DangerZones", dangerList);

        ListTag activeList = new ListTag();
        for (Map.Entry<BlockPos, Integer> entry : activeWormCounts.entrySet()) {
            CompoundTag activeTag = NbtUtils.writeBlockPos(entry.getKey());
            activeTag.putInt("Count", entry.getValue());
            activeList.add(activeTag);
        }
        tag.put("ActiveWorms", activeList);

        return tag;
    }

    public static HiveNetwork fromNBT(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        HiveNetwork net = new HiveNetwork(id);

        net.killsPool = tag.getInt("KillsPool");
        net.lastFedTime = tag.getLong("LastFed");

        try {
            net.currentScenario = DevelopmentScenario.valueOf(tag.getString("Scenario"));
        } catch (IllegalArgumentException | NullPointerException e) {
            // Оставляем дефолт из конструктора
            System.out.println("[Hive] Invalid scenario in NBT, using default: " + net.currentScenario);
        }

        // Аналогично для currentState
        try {
            net.currentState = HiveState.valueOf(tag.getString("CurrentState"));
        } catch (IllegalArgumentException | NullPointerException e) {
            // Оставляем дефолт
        }

        net.targetWormCount = tag.getInt("TargetWorms");
        if (net.targetWormCount == 0) net.targetWormCount = 6;

        net.targetNestCount = tag.getInt("TargetNests");
        if (net.targetNestCount == 0) net.targetNestCount = 2;

        net.maxExpansionRadius = tag.getInt("MaxRadius");
        if (net.maxExpansionRadius == 0) net.maxExpansionRadius = 8;

        if (tag.contains("HiveCenter")) {
            net.hiveCenter = BlockPos.of(tag.getLong("HiveCenter"));
        }

        net.threatLevel = tag.getInt("ThreatLevel");
        net.expansionPressure = tag.getInt("ExpansionPressure");
        net.lastStateChange = tag.getLong("LastStateChange");
        net.successfulAttacks = tag.getInt("SuccessfulAttacks");

        ListTag membersList = tag.getList("Members", 10);
        for (int i = 0; i < membersList.size(); i++) {
            CompoundTag pTag = membersList.getCompound(i);
            BlockPos pos = NbtUtils.readBlockPos(pTag);
            boolean isNest = pTag.getBoolean("IsNest");

            net.members.add(pos);
            if (isNest) {
                net.wormCounts.put(pos, pTag.getInt("WormCount"));
                ListTag wormDataList = pTag.getList("WormData", 10);
                List<CompoundTag> wormData = new ArrayList<>();
                for (int j = 0; j < wormDataList.size(); j++) {
                    wormData.add(wormDataList.getCompound(j));
                }
                net.nestWormData.put(pos, wormData);

                // Устанавливаем центр при загрузке первого гнезда
                if (net.hiveCenter == null) {
                    net.hiveCenter = pos.immutable();
                }
            }
        }

        ListTag dangerList = tag.getList("DangerZones", 10);
        for (int i = 0; i < dangerList.size(); i++) {
            net.dangerZones.add(NbtUtils.readBlockPos(dangerList.getCompound(i)));
        }

        if (tag.contains("ActiveWorms")) {
            ListTag activeList = tag.getList("ActiveWorms", 10);
            for (int i = 0; i < activeList.size(); i++) {
                CompoundTag activeTag = activeList.getCompound(i);
                BlockPos pos = NbtUtils.readBlockPos(activeTag);
                int count = activeTag.getInt("Count");
                if (count > 0) {
                    net.activeWormCounts.put(pos, count);
                }
            }
        }

        return net;
    }
}