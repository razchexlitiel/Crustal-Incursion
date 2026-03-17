// HiveNetwork.java — полностью переработанная логика расширения и состояний
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
    public int killsPool = 1;

    private long lastFedTime = 0;
    private long starvationStartTime = -1;
    private static final int DAILY_UPKEEP = 1;
    private static final int UPKEEP_INTERVAL = 24000 * 3;
    private static final int STARVATION_DEATH_TIME = 24000 * 7;

    // ⭐ НОВОЕ: Флаг активации — пока false, сеть только голодает
    private boolean isAwakened = false;
    private static final int AWAKEN_THRESHOLD = 2; // Минимум 2 очка для пробуждения

    private int rootsBuilt = 0;
    private int soilBuilt = 0;
    private static final int ROOTS_FOR_SOIL = 3;
    private static final int SOIL_FOR_NEST = 3;

    public enum HiveState {
        DORMANT, EXPANSION, DEFENSIVE, AGGRESSIVE, RECOVERY, STARVATION, DEAD
    }

    private HiveState currentState = HiveState.DORMANT; // ⭐ Начинаем с DORMANT
    private int threatLevel = 0;
    private long lastStateChange = 0;
    private int successfulAttacks = 0;
    private final Set<BlockPos> dangerZones = new HashSet<>();

    public int targetWormCount = 6;
    public int targetNestCount = 2;
    public long lastScenarioChange = 0;
    public int wormsSpawnedTotal = 0;
    public int nestsBuiltTotal = 0;

    public int maxExpansionRadius = 8;
    public BlockPos hiveCenter = null;

    public enum DevelopmentScenario {
        STARTUP, RAPID_GROWTH, EXPAND_TERRITORY, BUILD_NESTS,
        CONSOLIDATE, AGGRESSIVE_PUSH, DEFENSIVE_BUILDUP, SURVIVAL
    }

    private DevelopmentScenario currentScenario = DevelopmentScenario.STARTUP;
    public final Map<BlockPos, Integer> activeWormCounts = new HashMap<>();
    private int lastLoggedWormCount = -1;
    private transient int spawnRecursionDepth = 0;
    private static final int MAX_SPAWN_RECURSION = 3;

    // ⭐ НОВОЕ: Таймеры для редких операций
    private long lastExpansionAttempt = 0;
    private static final long EXPANSION_COOLDOWN = 100; // Раз в 5 сек проверяем расширение
    private long lastRootAttempt = 0;
    private static final long ROOT_COOLDOWN = 120; // Реже строим корни

    public HiveNetwork(UUID id) {
        this.id = id;
    }

    public boolean isActive() {
        return killsPool > 0;
    }

    public boolean isAwakened() {
        return isAwakened;
    }

    public void addPoints(int points, Level level) {
        boolean wasInactive = !isActive();
        killsPool = Math.min(50, killsPool + points);

        // ⭐ Пробуждение при накоплении достаточно очков
        if (!isAwakened && killsPool >= AWAKEN_THRESHOLD) {
            isAwakened = true;
            currentState = HiveState.EXPANSION;
            currentScenario = DevelopmentScenario.STARTUP;
            lastFedTime = level.getGameTime();
            System.out.println("[Hive " + id + "] ⚡ AWAKENED with " + killsPool + " points!");
        }

        if (wasInactive && isActive() && isAwakened) {
            currentState = HiveState.EXPANSION;
        }
    }

    public void addMember(BlockPos pos, boolean isNest) {
        members.add(pos);
        if (isNest) {
            wormCounts.put(pos, 0);
            if (hiveCenter == null) {
                hiveCenter = pos.immutable();
            }
        }
    }

    public void removeMember(BlockPos pos) {
        members.remove(pos);
        wormCounts.remove(pos);
    }

    public List<CompoundTag> getNestWormData(BlockPos nestPos, Level level) {
        BlockEntity be = level.getBlockEntity(nestPos);
        if (be instanceof DepthWormNestBlockEntity nest) {
            return nest.getStoredWorms();
        }
        return Collections.emptyList();
    }

    public boolean isNest(Level level, BlockPos pos) {
        return members.contains(pos) && level.isLoaded(pos) &&
                level.getBlockState(pos).is(ModBlocks.DEPTH_WORM_NEST.get());
    }

    public int getTotalWorms(Level level) {
        int total = 0;
        for (BlockPos nestPos : wormCounts.keySet()) {
            if (level.isLoaded(nestPos)) {
                BlockEntity be = level.getBlockEntity(nestPos);
                if (be instanceof DepthWormNestBlockEntity nest) {
                    total += nest.getStoredWormsCount();
                }
            }
        }
        return total;
    }

    public boolean isDead(Level level) {
        boolean noStoredWorms = getTotalWorms(level) == 0;
        boolean noActiveWorms = activeWormCounts.isEmpty() ||
                activeWormCounts.values().stream().allMatch(count -> count == 0);
        boolean noMembers = members.isEmpty();
        return noStoredWorms && noActiveWorms && noMembers;
    }

    public boolean isAbandoned(Level level) {
        boolean noStoredWorms = getTotalWorms(level) == 0;
        boolean noActiveWorms = activeWormCounts.isEmpty() ||
                activeWormCounts.values().stream().allMatch(count -> count == 0);
        boolean hasMembers = !members.isEmpty();
        boolean noResources = killsPool <= 0;
        boolean hasNests = !wormCounts.isEmpty();

        return noStoredWorms && noActiveWorms && hasMembers && noResources && hasNests;
    }

    public void addActiveWorm(BlockPos nestPos) {
        // ⭐ Проверяем что не добавляем больше чем есть в гнезде
        int currentActive = activeWormCounts.getOrDefault(nestPos, 0);
        int stored = wormCounts.getOrDefault(nestPos, 0);

        // Максимум 3 активных на гнездо (лимит вместимости)
        if (currentActive >= 3) {
            System.out.println("[Hive] WARNING: Attempted to add active worm to full nest " + nestPos);
            return;
        }

        activeWormCounts.merge(nestPos, 1, Integer::sum);
    }
    public void removeActiveWorm(BlockPos nestPos) {
        int current = activeWormCounts.getOrDefault(nestPos, 0);
        if (current <= 0) {
            System.out.println("[Hive] WARNING: Remove active worm from empty count at " + nestPos);
            activeWormCounts.put(nestPos, 0);
            return;
        }

        activeWormCounts.merge(nestPos, -1, (old, delta) -> Math.max(0, old + delta));
        if (activeWormCounts.get(nestPos) == 0) {
            activeWormCounts.remove(nestPos);
        }
    }

    public int getTotalWormsIncludingActive(Level level) {
        int stored = getTotalWorms(level);
        int active = activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();

        // ⭐ Проверка: активных не должно быть больше чем слотов в гнездах
        int maxPossible = wormCounts.size() * 3;
        if (active > maxPossible) {
            System.out.println("[Hive] ERROR: Active worms (" + active + ") exceed capacity (" + maxPossible +
                    "). Resetting active counts.");
            activeWormCounts.clear();
            active = 0;
        }

        return stored + active;
    }

    public int getWormsFromNest(BlockPos nestPos, Level level) {
        int stored = 0;
        if (level.isLoaded(nestPos)) {
            BlockEntity be = level.getBlockEntity(nestPos);
            if (be instanceof DepthWormNestBlockEntity nest) {
                stored = nest.getStoredWormsCount();
            }
        }
        int active = activeWormCounts.getOrDefault(nestPos, 0);
        return stored + active;
    }

    public boolean isWithinExpansionLimit(BlockPos pos) {
        if (hiveCenter == null) return true;
        double dist = Math.sqrt(pos.distSqr(hiveCenter));
        return dist <= maxExpansionRadius;
    }

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

    // ⭐ ОПТИМИЗИРОВАНО: Процесс голода работает всегда, но быстро
    private void processHunger(Level level) {
        long currentTime = level.getGameTime();
        long timeSinceLastFed = currentTime - lastFedTime;

        if (timeSinceLastFed >= UPKEEP_INTERVAL && killsPool > 0) {
            int daysPassed = (int) (timeSinceLastFed / UPKEEP_INTERVAL);
            int upkeep = Math.min(daysPassed * DAILY_UPKEEP, killsPool);
            killsPool -= upkeep;
            lastFedTime = currentTime;

            // ⭐ Если очки упали ниже порога — засыпаем
            if (isAwakened && killsPool < AWAKEN_THRESHOLD) {
                isAwakened = false;
                currentState = HiveState.DORMANT;
                System.out.println("[Hive " + id + "] 😴 Entered DORMANT (points below threshold)");
            }
        }

        if (killsPool <= 0) {
            if (starvationStartTime < 0) {
                starvationStartTime = currentTime;
                System.out.println("[Hive " + id + "] ENTERING STARVATION! (7 days until death)");
            }

            long starvationTime = currentTime - starvationStartTime;

            if (starvationTime > STARVATION_DEATH_TIME) {
                dieFromStarvation(level);
            } else if (currentState != HiveState.STARVATION && currentState != HiveState.DEAD) {
                currentState = HiveState.STARVATION;
                currentScenario = DevelopmentScenario.SURVIVAL;
            }
        } else {
            if (starvationStartTime >= 0) {
                starvationStartTime = -1;
                System.out.println("[Hive " + id + "] Leaving starvation mode");
            }
        }
    }

    private void dieFromStarvation(Level level) {
        System.out.println("[Hive " + id + "] ☠️ COLONY DIED FROM STARVATION!");
        currentState = HiveState.DEAD;

        List<BlockPos> toReplace = new ArrayList<>(members);
        for (BlockPos pos : toReplace) {
            if (!level.isLoaded(pos)) continue;

            BlockState current = level.getBlockState(pos);
            if (current.is(ModBlocks.HIVE_SOIL.get())) {
                level.setBlock(pos, ModBlocks.HIVE_SOIL_DEAD.get().defaultBlockState(), 3);
            } else if (current.is(ModBlocks.DEPTH_WORM_NEST.get())) {
                level.setBlock(pos, ModBlocks.DEPTH_WORM_NEST_DEAD.get().defaultBlockState(), 3);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof DepthWormNestBlockEntity nest) {
                    nest.releaseWormsAndNotify();
                }
            }
        }

        members.clear();
        wormCounts.clear();
        activeWormCounts.clear();
    }

    // ⭐ ГЛАВНОЕ: Разделение тика на "голод" (всегда) и "решения" (только если пробуждены)
    public void update(Level level) {
        if (level.isClientSide) return;
        if (currentState == HiveState.DEAD) return;

        // Всегда обрабатываем голод, но реже если не пробуждены
        int hungerTickRate = isAwakened ? 100 : 600; // Раз в 30 сек если спим
        if (level.getGameTime() % hungerTickRate == 0) {
            processHunger(level);
        }

        // ⭐ Если не пробуждены — только голод, никаких решений
        if (!isAwakened) {
            // Проверяем смерть от голода даже во сне
            if (killsPool <= 0 && starvationStartTime >= 0) {
                long starvationTime = level.getGameTime() - starvationStartTime;
                if (starvationTime > STARVATION_DEATH_TIME) {
                    dieFromStarvation(level);
                }
            }
            return;
        }

        if (!hasAnyLoadedChunk(level)) return;

        // Логирование реже
        if (level.getGameTime() % 200 == 0) {
            int active = activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();
            int stored = getTotalWorms(level);

            System.out.println("[Hive " + id + "] State: " + currentState +
                    " | Scenario: " + currentScenario +
                    " | Points: " + killsPool +
                    " | Worms: " + stored + "(stored) + " + active + "(active)" +
                    " | Nests: " + wormCounts.size() +
                    " | Members: " + members.size());
        }

        // Основная логика — реже для оптимизации
        if (level.getGameTime() % 40 == 0) {
            makeDecisions(level);
        }
    }

    private void makeDecisions(Level level) {
        if (currentState == HiveState.DEAD || currentState == HiveState.DORMANT) return;
        if (currentState == HiveState.STARVATION) {
            executeDefensiveBuildup(level, getTotalWormsIncludingActive(level), wormCounts.size());
            return;
        }

        if (killsPool <= 0) {
            currentState = HiveState.DORMANT;
            isAwakened = false;
            return;
        }

        if (level.getGameTime() % 100 == 0 || currentScenario == null) {
            analyzeAndChooseScenario(level);
        }

        executeScenario(level);

        long time = level.getGameTime();

        // ⭐ ИСПРАВЛЕНО: Корни — отдельно
        if (time - lastRootAttempt > ROOT_COOLDOWN) {
            if (tryBuildRootsSmart(level)) {
                lastRootAttempt = time;
            }
        }

        // ⭐ ИСПРАВЛЕНО: Расширение и апгрейд — раздельно
        if (time - lastExpansionAttempt > EXPANSION_COOLDOWN) {
            boolean expanded = tryExpandSmart(level);
            lastExpansionAttempt = time;

            if (!expanded && killsPool >= 5) {
                System.out.println("[Hive " + id + "] Expansion failed — needNests:" +
                        (getTotalWormsIncludingActive(level) >= wormCounts.size() * 3 - 1) +
                        " needSoil:" + (members.size() < wormCounts.size() * 4) +
                        " soilBuilt:" + soilBuilt + " rootsBuilt:" + rootsBuilt);
            }
        }

        // ⭐ НОВОЕ: Проверка апгрейда почвы в гнездо — НЕЗАВИСИМО от расширения!
        // Если накопили достаточно почвы и нужны гнезда — апгрейдим
        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;
        boolean needMoreNests = totalWorms >= maxCapacity - 1 && nests < 8;

        if (needMoreNests && soilBuilt >= SOIL_FOR_NEST && killsPool >= 15) {
            if (tryUpgradeSoilToNest(level, true)) {
                System.out.println("[Hive " + id + "] ⭐ UPGRADED SOIL TO NEST! Chain reset.");
                soilBuilt = 0;
                rootsBuilt = 0;
            } else {
                System.out.println("[Hive " + id + "] Upgrade failed — no valid soil found");
            }
        }
    }

    private void executeDefensiveBuildup(Level level, int totalWorms, int nests) {
        // В starvation режиме — консервация, минимальный рост для выживания
        if (totalWorms < nests * 2 && killsPool >= 10) {
            spawnNewWormOptimally(level);
        }
        // При высокой угрозе — экстренное расширение если есть ресурсы
        else if (threatLevel > 10 && killsPool >= 15 && nests < 3 && level.getGameTime() % 200 == 0) {
            tryExpandSmart(level);
        }
    }
    // ⭐ НОВАЯ ЛОГИКА: Умное строительство корней — не закрываем всё поверхность
    private boolean tryBuildRootsSmart(Level level) {
        if (killsPool < 2) return false;
        if (rootsBuilt >= ROOTS_FOR_SOIL) return false;

        // Считаем соотношение корней к почве/гнёздам
        int rootCount = 0;
        int surfaceCount = 0;

        for (BlockPos pos : members) {
            BlockState state = level.getBlockState(pos);
            if (state.is(ModBlocks.HIVE_ROOTS.get())) {
                rootCount++;
            } else if (state.is(ModBlocks.HIVE_SOIL.get()) || state.is(ModBlocks.DEPTH_WORM_NEST.get())) {
                surfaceCount++;
            }
        }

        // Не более 30% корней от поверхности
        if (surfaceCount > 0 && (double) rootCount / surfaceCount > 0.3) {
            return false;
        }

        // Ищем гнездо с меньше всего корней рядом
        BlockPos bestNest = findNestWithFewestRoots(level);
        if (bestNest == null) return false;

        // Строим 1-2 корня у этого гнезда
        List<BlockPos> candidates = findRootCandidates(level, bestNest);
        if (candidates.isEmpty()) return false;

        // Берём случайный, но не более 2 за раз
        BlockPos target = candidates.get(level.getRandom().nextInt(candidates.size()));
        boolean hanging = target.getY() > bestNest.getY();

        if (ModBlocks.HIVE_ROOTS != null) {
            BlockState rootState = ModBlocks.HIVE_ROOTS.get().defaultBlockState()
                    .setValue(com.cim.block.basic.necrosis.hive.HiveRootsBlock.HANGING, hanging);

            level.setBlock(target, rootState, 3);
            killsPool -= 2;
            rootsBuilt++;
            return true;
        }
        return false;
    }

    private BlockPos findNestWithFewestRoots(Level level) {
        BlockPos best = null;
        int minRoots = Integer.MAX_VALUE;

        for (BlockPos nestPos : wormCounts.keySet()) {
            if (!level.isLoaded(nestPos)) continue;

            int nearbyRoots = countNearbyRoots(level, nestPos, 3);
            if (nearbyRoots < minRoots) {
                minRoots = nearbyRoots;
                best = nestPos;
            }
        }
        return best;
    }

    private int countNearbyRoots(Level level, BlockPos center, int radius) {
        int count = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (level.getBlockState(center.offset(x, y, z)).is(ModBlocks.HIVE_ROOTS.get())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private List<BlockPos> findRootCandidates(Level level, BlockPos nestPos) {
        List<BlockPos> candidates = new ArrayList<>();

        // Проверяем сверху и снизу
        BlockPos above = nestPos.above();
        BlockPos below = nestPos.below();

        if (level.getBlockState(above).isAir() && isValidRootPlacement(level, above, true)) {
            candidates.add(above);
        }
        if (level.getBlockState(below).isAir() && isValidRootPlacement(level, below, false)) {
            candidates.add(below);
        }

        // И по бокам если есть поддержка
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = nestPos.relative(dir);
            if (level.getBlockState(side).isAir()) {
                // Корень должен цепляться за существующий блок колонии
                boolean hasSupport = false;
                for (Direction d : Direction.values()) {
                    BlockPos support = side.relative(d);
                    if (members.contains(support)) {
                        hasSupport = true;
                        break;
                    }
                }
                if (hasSupport) candidates.add(side);
            }
        }

        return candidates;
    }

    private boolean isValidRootPlacement(Level level, BlockPos pos, boolean hanging) {
        for (Direction dir : Direction.values()) {
            if (members.contains(pos.relative(dir))) return true;
        }
        return false;
    }

    private boolean tryExpandSmart(Level level) {
        if (killsPool < 5) return false;
        if (members.isEmpty()) return false;

        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        boolean needMoreNests = totalWorms >= maxCapacity - 1 && nests < 8;
        boolean needMoreSoil = members.size() < nests * 4;

        // ⭐ ИСПРАВЛЕНО: Строим почву если нужны гнезда и недостаточно soilBuilt
        boolean needSoilForNests = needMoreNests && soilBuilt < SOIL_FOR_NEST;

        if (!needMoreNests && !needMoreSoil && !needSoilForNests) {
            return false;
        }


        // Сначала ищем дыры
        BlockPos hole = findHoleInNetwork(level);
        if (hole != null && canPlaceSoilSafely(level, hole, this.id)) {
            placeHiveSoil(level, hole, this.id);
            killsPool -= 5;
            soilBuilt++;
            System.out.println("[Hive " + id + "] Filled hole at " + hole + " | Soil progress: " + soilBuilt + "/" + SOIL_FOR_NEST);
            return true;
        }

        // Расширяемся наружу
        BlockPos expansionTarget = findExpansionTarget(level, needMoreNests);
        if (expansionTarget != null && canPlaceSoilSafely(level, expansionTarget, this.id)) {
            placeHiveSoil(level, expansionTarget, this.id);
            killsPool -= 5;
            soilBuilt++;
            System.out.println("[Hive " + id + "] Expanded to " + expansionTarget + " | Soil progress: " + soilBuilt + "/" + SOIL_FOR_NEST);
            return true;
        }

        return false;
    }

    // ⭐ НОВЫЙ МЕТОД: Проверка апгрейда почвы в гнездо
    private void checkAndUpgradeSoil(Level level, boolean needMoreNests) {
        if (!needMoreNests) return;
        if (soilBuilt < SOIL_FOR_NEST) return;
        if (killsPool < 15) return;

        if (tryUpgradeSoilToNest(level, true)) {
            System.out.println("[Hive " + id + "] Upgraded soil to nest! Chain reset.");
            soilBuilt = 0;
            rootsBuilt = 0;
        }
    }

    // Ищем пустоты внутри сети
    private BlockPos findHoleInNetwork(Level level) {
        // Проверяем позиции рядом с существующими членами
        Set<BlockPos> checked = new HashSet<>();

        for (BlockPos member : members) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = member.relative(dir);
                if (checked.contains(neighbor)) continue;
                checked.add(neighbor);

                if (!members.contains(neighbor) && canPlaceSoilSafely(level, neighbor, this.id)) {
                    // Считаем соседей-колонистов
                    int colonyNeighbors = 0;
                    for (Direction d : Direction.values()) {
                        if (members.contains(neighbor.relative(d))) {
                            colonyNeighbors++;
                        }
                    }
                    // Если 3+ соседа — это "дыра"
                    if (colonyNeighbors >= 3) {
                        return neighbor;
                    }
                }
            }
        }
        return null;
    }

    // Ищем точку для наружного расширения
    private BlockPos findExpansionTarget(Level level, boolean towardsCenter) {
        if (hiveCenter == null) return null;

        // Находим самый дальний от центра блок сети
        BlockPos farthest = null;
        double maxDist = 0;

        for (BlockPos pos : members) {
            double dist = pos.distSqr(hiveCenter);
            if (dist > maxDist) {
                maxDist = dist;
                farthest = pos;
            }
        }

        if (farthest == null) return null;

        // Ищем направление от центра к краю
        Vec3 toEdge = new Vec3(farthest.getX() - hiveCenter.getX(), 0, farthest.getZ() - hiveCenter.getZ()).normalize();

        // Пробуем продолжить в том же направлении
        Direction bestDir = null;
        double bestDot = -1;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Vec3 dirVec = new Vec3(dir.getStepX(), 0, dir.getStepZ());
            double dot = dirVec.dot(toEdge);
            if (dot > bestDot) {
                bestDot = dot;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            BlockPos target = farthest.relative(bestDir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

        // Fallback — любое валидное место рядом с краем
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos target = farthest.relative(dir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

        return null;
    }

    private void analyzeAndChooseScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int nodes = members.size();
        int maxCapacity = nests * 3;

        double territoryEfficiency = nodes > 0 ? (double) totalWorms / nodes : 0;
        double nestUtilization = nests > 0 ? (double) totalWorms / maxCapacity : 0;

        DevelopmentScenario newScenario = currentScenario;

        // Приоритет: если гнёзда переполнены — строим новые
        if (totalWorms >= maxCapacity - 1 && nests < 8 && killsPool >= 15) {
            newScenario = DevelopmentScenario.BUILD_NESTS;
            targetNestCount = Math.min(8, nests + 1);
        }
        else if (threatLevel > 15) {
            newScenario = DevelopmentScenario.DEFENSIVE_BUILDUP;
        }
        else if (totalWorms < 4 && killsPool >= 15 && nests > 0) {
            newScenario = DevelopmentScenario.RAPID_GROWTH;
            targetWormCount = Math.min(maxCapacity - 1, 6);
        }
        else if (territoryEfficiency > 0.30 && nodes < nests * 4 && killsPool >= 5) {
            newScenario = DevelopmentScenario.EXPAND_TERRITORY;
        }
        else if (nests < 3 && killsPool >= 20 && totalWorms >= nests * 2) {
            newScenario = DevelopmentScenario.BUILD_NESTS;
            targetNestCount = Math.min(3, nests + 1);
        }
        else if (killsPool > 30 && totalWorms >= 4) {
            newScenario = DevelopmentScenario.AGGRESSIVE_PUSH;
            targetWormCount = Math.min(maxCapacity, 12);
        }
        else if (totalWorms >= 3 && nests >= 2 && territoryEfficiency < 0.20) {
            newScenario = DevelopmentScenario.CONSOLIDATE;
        }
        else if (nests == 1 && totalWorms < 3) {
            newScenario = DevelopmentScenario.STARTUP;
        }

        if (newScenario != currentScenario) {
            currentScenario = newScenario;
            lastScenarioChange = level.getGameTime();
            System.out.println("[Hive AI] Scenario: " + currentScenario);
        }
    }

    private void executeScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        switch (currentScenario) {
            case STARTUP -> {
                currentState = HiveState.EXPANSION;
                if (totalWorms < 3 && killsPool >= 10) {
                    spawnNewWormOptimally(level);
                }
            }
            case RAPID_GROWTH -> {
                currentState = HiveState.AGGRESSIVE;
                if (totalWorms < targetWormCount && killsPool >= 10) {
                    spawnNewWormOptimally(level);
                }
            }
            case EXPAND_TERRITORY -> {
                currentState = HiveState.EXPANSION;
                // tryExpandSmart вызывается отдельно с кулдауном
            }
            case BUILD_NESTS -> {
                currentState = HiveState.EXPANSION;
                // tryExpandSmart с needMoreNests=true
            }
            case CONSOLIDATE -> {
                currentState = HiveState.EXPANSION;
                if (totalWorms < targetWormCount && killsPool >= 10) {
                    spawnNewWormOptimally(level);
                }
            }
            case AGGRESSIVE_PUSH -> {
                currentState = HiveState.AGGRESSIVE;
                if (totalWorms < maxCapacity && killsPool >= 10) {
                    spawnNewWormOptimally(level);
                }
                if (getTotalWorms(level) >= 2 && killsPool >= 3) {
                    executeCoordinatedAttack(level);
                }
            }
            case DEFENSIVE_BUILDUP, SURVIVAL -> {
                currentState = HiveState.DEFENSIVE;
                int readyWorms = getTotalWorms(level);
                if (readyWorms < nests * 2 && killsPool >= 10) {
                    spawnNewWormOptimally(level);
                }
            }
        }
    }

    // Упрощённый и надёжный спавн червя
    private void spawnNewWormOptimally(Level level) {
        if (spawnRecursionDepth >= MAX_SPAWN_RECURSION) {
            spawnRecursionDepth = 0;
            return;
        }
        spawnRecursionDepth++;

        try {
            BlockPos bestNest = null;
            int minWorms = Integer.MAX_VALUE;

            for (BlockPos nestPos : wormCounts.keySet()) {
                if (!level.isLoaded(nestPos)) continue;

                BlockEntity be = level.getBlockEntity(nestPos);
                if (!(be instanceof DepthWormNestBlockEntity nest)) continue;
                if (nest.isFull()) continue;

                int count = nest.getStoredWormsCount();
                if (count < minWorms) {
                    minWorms = count;
                    bestNest = nestPos;
                }
            }

            if (bestNest != null && killsPool >= 10) {
                BlockEntity be = level.getBlockEntity(bestNest);
                if (be instanceof DepthWormNestBlockEntity nest) {
                    CompoundTag newWorm = new CompoundTag();
                    newWorm.putFloat("Health", 15.0F);
                    newWorm.putInt("Kills", 0);
                    newWorm.putLong("BoundNest", bestNest.asLong());

                    nest.addWormTag(newWorm);
                    wormCounts.put(bestNest, nest.getStoredWormsCount());

                    wormsSpawnedTotal++;
                    killsPool -= 10;
                }
            }
        } finally {
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

    private boolean canPlaceSoilSafely(Level level, BlockPos pos, UUID networkId) {
        if (!isValidExpansionTarget(level, pos)) return false;
        if (!isWithinExpansionLimit(pos)) return false;

        boolean hasNetworkNeighbor = false;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be instanceof HiveNetworkMember member && networkId.equals(member.getNetworkId())) {
                hasNetworkNeighbor = true;
                break;
            }
        }

        // Диагональная проверка для "мостов"
        if (!hasNetworkNeighbor) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isAir()) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
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
        }
    }

    private boolean tryUpgradeSoilToNest(Level level, boolean needSpaceForNewWorm) {
        if (killsPool < 15) {
            System.out.println("[Hive " + id + "] Upgrade: need 15 points, have " + killsPool);
            return false;
        }
        if (wormCounts.size() >= 8) {
            System.out.println("[Hive " + id + "] Upgrade: max nests reached");
            return false;
        }

        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        // ⭐ ИСПРАВЛЕНО: Если needSpaceForNewWorm, проверяем что реально нужно место
        if (!needSpaceForNewWorm && totalWorms < maxCapacity) {
            System.out.println("[Hive " + id + "] Upgrade: not full yet (" + totalWorms + "/" + maxCapacity + ")");
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>();

        for (BlockPos soilPos : members) {
            if (wormCounts.containsKey(soilPos)) continue;

            boolean tooClose = false;
            for (BlockPos nestPos : wormCounts.keySet()) {
                if (soilPos.distSqr(nestPos) < 4) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose && isWithinExpansionLimit(soilPos) && hasNetworkNeighbor(level, soilPos)) {
                candidates.add(soilPos);
            }
        }

        System.out.println("[Hive " + id + "] Upgrade: " + candidates.size() + " candidates, needSpace=" + needSpaceForNewWorm);

        if (candidates.isEmpty()) {
            System.out.println("[Hive " + id + "] Upgrade: no valid soil found");
            return false;
        }

        candidates.sort(Comparator.comparingDouble(p -> {
            int nestNeighbors = 0;
            for (Direction d : Direction.values()) {
                if (wormCounts.containsKey(p.relative(d))) nestNeighbors++;
            }
            return p.distSqr(hiveCenter) - nestNeighbors * 10;
        }));

        upgradeSoilToNest(level, candidates.get(0));
        return true;
    }

    private boolean hasNetworkNeighbor(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (members.contains(pos.relative(dir))) return true;
        }
        return false;
    }

    private void upgradeSoilToNest(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HiveSoilBlockEntity soil)) return;

        UUID preservedId = soil.getNetworkId();
        if (preservedId == null || !preservedId.equals(this.id)) return;
        if (wormCounts.size() >= 8) return;

        members.remove(pos);

        level.setBlock(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState(), 3);

        BlockEntity newBe = ModBlockEntities.DEPTH_WORM_NEST.get().create(pos,
                ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState());

        if (!(newBe instanceof DepthWormNestBlockEntity nest)) return;

        nest.setNetworkId(preservedId);
        level.setBlockEntity(newBe);

        wormCounts.put(pos, 0);
        members.add(pos);
        nestsBuiltTotal++;
        killsPool -= 15;

        HiveNetworkManager manager = HiveNetworkManager.get(level);
        if (manager != null) {
            manager.addNode(preservedId, pos, true);
        }
    }

    // NBT сериализация с сохранением флага пробуждения
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("KillsPool", this.killsPool);
        tag.putLong("LastFed", this.lastFedTime);
        tag.putLong("StarvationStart", this.starvationStartTime);
        tag.putBoolean("IsAwakened", this.isAwakened); // ⭐ Новое
        tag.putInt("RootsBuilt", this.rootsBuilt);
        tag.putInt("SoilBuilt", this.soilBuilt);
        tag.putString("CurrentState", this.currentState.name());
        tag.putString("Scenario", this.currentScenario.name());
        tag.putInt("TargetWorms", this.targetWormCount);
        tag.putInt("TargetNests", this.targetNestCount);
        tag.putInt("MaxRadius", this.maxExpansionRadius);
        if (this.hiveCenter != null) {
            tag.putLong("HiveCenter", this.hiveCenter.asLong());
        }
        tag.putInt("ThreatLevel", this.threatLevel);
        tag.putLong("LastStateChange", this.lastStateChange);
        tag.putInt("SuccessfulAttacks", this.successfulAttacks);

        ListTag membersList = new ListTag();
        for (BlockPos p : members) {
            CompoundTag pTag = NbtUtils.writeBlockPos(p);
            pTag.putBoolean("IsNest", wormCounts.containsKey(p));
            if (wormCounts.containsKey(p)) {
                pTag.putInt("WormCount", wormCounts.get(p));
            }
            membersList.add(pTag);
        }
        tag.put("Members", membersList);

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
        if (net.killsPool <= 0) net.killsPool = 1;

        net.lastFedTime = tag.getLong("LastFed");
        net.starvationStartTime = tag.getLong("StarvationStart");
        net.isAwakened = tag.getBoolean("IsAwakened"); // ⭐ Новое
        net.rootsBuilt = tag.getInt("RootsBuilt");
        net.soilBuilt = tag.getInt("SoilBuilt");

        try {
            net.currentScenario = DevelopmentScenario.valueOf(tag.getString("Scenario"));
        } catch (IllegalArgumentException | NullPointerException e) {
            net.currentScenario = DevelopmentScenario.STARTUP;
        }

        try {
            net.currentState = HiveState.valueOf(tag.getString("CurrentState"));
        } catch (IllegalArgumentException | NullPointerException e) {
            net.currentState = HiveState.DORMANT;
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
                if (net.hiveCenter == null) {
                    net.hiveCenter = pos.immutable();
                }
            }
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