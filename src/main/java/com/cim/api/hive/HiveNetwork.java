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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class HiveNetwork {
    public final UUID id;
    public final Set<BlockPos> members = new HashSet<>();
    public final Map<BlockPos, Integer> wormCounts = new HashMap<>();
    public int killsPool = 1; // ⭐ По умолчанию 1 очко для старта

    // Система голода
    private long lastFedTime = 0;
    private long starvationStartTime = -1;
    private static final int DAILY_UPKEEP = 1;
    private static final int STARVATION_DEATH_TIME = 24000 * 3;
    private static final long LOG_COOLDOWN = 200;
    private long lastLogTime = 0;

    // ⭐ УБРАНО: Флаг isActive — теперь активность = killsPool > 0

    public enum HiveState {
        DORMANT, // killsPool <= 0
        EXPANSION, DEFENSIVE, AGGRESSIVE, RECOVERY, STARVATION, DEAD
    }

    private HiveState currentState = HiveState.EXPANSION;
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

    public HiveNetwork(UUID id) {
        this.id = id;
    }

    // ⭐ НОВОЕ: Проверка активности через очки
    public boolean isActive() {
        return killsPool > 0;
    }

    // ⭐ НОВОЕ: Добавление очков (с автоматическим пробуждением)
    public void addPoints(int points, Level level) {
        boolean wasInactive = !isActive();
        killsPool = Math.min(50, killsPool + points);

        if (wasInactive && isActive()) {
            // Пробуждение из спячки!
            currentState = HiveState.EXPANSION;
            currentScenario = DevelopmentScenario.STARTUP;
            System.out.println("[Hive " + id + "] ⚡ AWAKENED with " + killsPool + " points!");
        }
    }

    public void addMember(BlockPos pos, boolean isNest) {
        members.add(pos);
        if (isNest) {
            wormCounts.put(pos, 0);
        }
        if (hiveCenter == null && isNest) {
            hiveCenter = pos.immutable();
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

        boolean abandoned = noStoredWorms && noActiveWorms && hasMembers && noResources;

        if (abandoned && level.getGameTime() - lastLogTime > LOG_COOLDOWN) {
            lastLogTime = level.getGameTime();
            System.out.println("[Hive " + id + "] Abandoned - no worms, no resources");
        }

        return abandoned;
    }

    public void addActiveWorm(BlockPos nestPos) {
        activeWormCounts.merge(nestPos, 1, Integer::sum);
    }

    public void removeActiveWorm(BlockPos nestPos) {
        activeWormCounts.merge(nestPos, -1, (old, delta) -> Math.max(0, old + delta));
        if (activeWormCounts.getOrDefault(nestPos, 0) <= 0) {
            activeWormCounts.remove(nestPos);
        }
    }

    public int getTotalWormsIncludingActive(Level level) {
        return getTotalWorms(level) + activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();
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

    private void processHunger(Level level) {
        long currentTime = level.getGameTime();
        int daysPassed = (int) ((currentTime - lastFedTime) / 24000);

        if (daysPassed > 0 && killsPool > 0) {
            int upkeep = Math.min(daysPassed * DAILY_UPKEEP, killsPool);
            killsPool -= upkeep;
            lastFedTime = currentTime + (currentTime % 24000);
            System.out.println("[Hive " + id + "] Hunger: consumed " + upkeep + " points, remaining: " + killsPool);
        }

        if (killsPool <= 0) {
            if (starvationStartTime < 0) {
                starvationStartTime = currentTime;
                System.out.println("[Hive " + id + "] ENTERING STARVATION!");
            }

            long starvationTime = currentTime - starvationStartTime;

            if (starvationTime > STARVATION_DEATH_TIME) {
                dieFromStarvation(level);
            } else {
                if (currentState != HiveState.STARVATION && currentState != HiveState.DEAD) {
                    currentState = HiveState.STARVATION;
                    currentScenario = DevelopmentScenario.SURVIVAL;
                }
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

    public void update(Level level) {
        if (level.isClientSide) return;
        if (currentState == HiveState.DEAD) return;

        // ⭐ Голод тикает ВСЕГДА (даже в спячке), но реже
        if (!isActive()) {
            // Спячка: только голод раз в 100 тиков
            if (level.getGameTime() % 100 == 0) {
                processHunger(level);

                // ⭐ Проверяем смерть от голода даже в спячке
                if (killsPool <= 0 && starvationStartTime >= 0) {
                    long starvationTime = level.getGameTime() - starvationStartTime;
                    if (starvationTime > STARVATION_DEATH_TIME) {
                        dieFromStarvation(level);
                    }
                }
            }
            return;
        }

        // Активная сеть — полный тик
        if (!hasAnyLoadedChunk(level)) return;

        processHunger(level);

        if (wormCounts.isEmpty() && members.isEmpty()) {
            currentState = HiveState.DEAD;
            return;
        }

        // ⭐ Проверяем переход в спячку при 0 очков
        if (killsPool <= 0 && currentState != HiveState.STARVATION) {
            currentState = HiveState.DORMANT;
            System.out.println("[Hive " + id + "] 😴 Entered DORMANT (no points)");
            return; // Не делаем решений в спячке
        }

        if (level.getGameTime() % 40 == 0) {
            int active = activeWormCounts.values().stream().mapToInt(Integer::intValue).sum();
            int stored = getTotalWorms(level);

            if (level.getGameTime() % 200 == 0 || stored != lastLoggedWormCount) {
                System.out.println("[Hive " + id + "] State: " + currentState +
                        " | Scenario: " + currentScenario +
                        " | Points: " + killsPool +
                        " | Worms: " + stored + "(stored) + " + active + "(active)" +
                        " | Nests: " + wormCounts.size() +
                        " | Hunger: " + (starvationStartTime < 0 ? "fed" :
                        ((level.getGameTime() - starvationStartTime) / 24000) + " days starving"));
                lastLoggedWormCount = stored;
            }

            if (!members.isEmpty()) {
                makeDecisions(level);
            }
        }
    }

    private void makeDecisions(Level level) {
        if (currentState == HiveState.DEAD) return;
        if (currentState == HiveState.DORMANT) return; // Не принимаем решений в спячке
        if (currentState == HiveState.STARVATION) {
            executeDefensiveBuildup(level, getTotalWormsIncludingActive(level), wormCounts.size());
            return;
        }

        if (killsPool <= 0) {
            currentState = HiveState.DORMANT;
            return;
        }

        if (level.getGameTime() % 100 == 0 || currentScenario == null) {
            analyzeAndChooseScenario(level);
        }

        executeScenario(level);

        if (level.getGameTime() % 60 == 0 && killsPool >= 2) {
            tryBuildRoots(level);
        }

        if (killsPool >= 15 && wormCounts.size() < 8 && level.getGameTime() % 20 == 0) {
            tryUpgradeSoilToNest(level, false);
        }
    }

    private void tryBuildRoots(Level level) {
        if (members.isEmpty()) return;

        List<BlockPos> candidates = new ArrayList<>();

        for (BlockPos pos : members) {
            if (!level.isLoaded(pos)) continue;

            BlockPos below = pos.below();
            BlockPos above = pos.above();

            if (level.getBlockState(below).isAir() && isValidRootPlacement(level, below, false)) {
                candidates.add(below);
            }
            else if (level.getBlockState(above).isAir() && isValidRootPlacement(level, above, true)) {
                candidates.add(above);
            }
        }

        if (candidates.isEmpty()) return;

        BlockPos target = candidates.get(level.getRandom().nextInt(candidates.size()));
        boolean hanging = target.getY() > hiveCenter.getY();

        if (ModBlocks.HIVE_ROOTS != null && killsPool >= 2) {
            BlockState rootState = ModBlocks.HIVE_ROOTS.get().defaultBlockState();
            try {
                rootState = rootState
                        .setValue(com.cim.block.basic.necrosis.hive.HiveRootsBlock.HANGING, hanging)
                        .setValue(com.cim.block.basic.necrosis.hive.HiveRootsBlock.UP, !hanging)
                        .setValue(com.cim.block.basic.necrosis.hive.HiveRootsBlock.DOWN, hanging);
            } catch (Exception e) {
                // Свойства не найдены
            }

            level.setBlock(target, rootState, 3);
            killsPool -= 2;
            System.out.println("[Hive " + id + "] Built roots at " + target + " (hanging: " + hanging + ")");
        }
    }

    private boolean isValidRootPlacement(Level level, BlockPos pos, boolean hanging) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (members.contains(neighbor)) return true;
        }
        return false;
    }

    private void analyzeAndChooseScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int nodes = members.size();
        int maxCapacity = nests * 3;

        double territoryEfficiency = nodes > 0 ? (double) totalWorms / nodes : 0;
        double nestUtilization = nests > 0 ? (double) totalWorms / maxCapacity : 0;

        DevelopmentScenario newScenario = currentScenario;

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
            System.out.println("[Hive AI] SCENARIO CHANGED TO: " + currentScenario);
        }
    }

    private void executeScenario(Level level) {
        int totalWorms = getTotalWormsIncludingActive(level);
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
            case DEFENSIVE_BUILDUP, SURVIVAL -> {
                currentState = HiveState.DEFENSIVE;
                executeDefensiveBuildup(level, totalWorms, nests);
            }
        }
    }

    private void executeStartup(Level level, int totalWorms, int nests) {
        if (totalWorms < 3 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (nests == 1 && killsPool >= 5 && level.getGameTime() % 60 == 0) {
            tryExpandCompact(level, 1);
            return;
        }

        if (nests >= 2 && totalWorms >= 2) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    private void executeRapidGrowth(Level level, int totalWorms, int maxCapacity) {
        if (totalWorms < targetWormCount && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (totalWorms >= maxCapacity && killsPool >= 15 && wormCounts.size() < 8) {
            int nestsBefore = wormCounts.size();
            tryUpgradeSoilToNest(level, true);

            if (wormCounts.size() > nestsBefore) {
                currentScenario = DevelopmentScenario.BUILD_NESTS;
            } else {
                currentScenario = DevelopmentScenario.CONSOLIDATE;
            }
            return;
        }

        if (totalWorms >= maxCapacity) {
            currentScenario = DevelopmentScenario.CONSOLIDATE;
        }
    }

    private void executeExpandTerritory(Level level, int totalWorms, int nests) {
        int currentNodes = members.size();
        int targetNodes = nests * 4;

        if (currentNodes >= targetNodes || killsPool < 5) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
            return;
        }

        if (level.getGameTime() % 50 == 0) {
            tryExpandCompact(level, 1);
        }
    }

    private void executeBuildNests(Level level, int totalWorms, int nests) {
        tryUpgradeSoilToNest(level, false);

        if (wormCounts.size() == nests && killsPool >= 5 && level.getGameTime() % 40 == 0) {
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

                if (adjacentToNest && isWithinExpansionLimit(soilPos)) {
                    hasCandidate = true;
                    break;
                }
            }

            if (!hasCandidate) {
                tryExpandCompact(level, 1);
            }
        }

        if (wormCounts.size() >= targetNestCount) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    private void executeConsolidate(Level level) {
        int totalWorms = getTotalWormsIncludingActive(level);

        if (totalWorms < targetWormCount && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (killsPool > 40) {
            currentScenario = DevelopmentScenario.AGGRESSIVE_PUSH;
        }
    }

    private void executeAggressivePush(Level level, int totalWorms, int maxCapacity) {
        if (totalWorms < maxCapacity && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (totalWorms >= maxCapacity && wormCounts.size() < 8 && killsPool >= 15) {
            if (targetWormCount > totalWorms) {
                tryUpgradeSoilToNest(level, true);
            }
        }

        if (getTotalWorms(level) >= 2 && killsPool >= 3) {
            executeCoordinatedAttack(level);
        }
    }

    private void executeDefensiveBuildup(Level level, int totalWorms, int nests) {
        int readyWorms = getTotalWorms(level);
        if (readyWorms < nests * 2 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (nests == 1 && members.size() < 4 && killsPool >= 5 && level.getGameTime() % 100 == 0) {
            tryExpandCompact(level, 1);
        }

        if (threatLevel < 5 && killsPool > 15) {
            currentScenario = DevelopmentScenario.RAPID_GROWTH;
        }
    }

    private void tryExpandCompact(Level level, int maxExpansions) {
        if (killsPool < 5) return;

        BlockPos bestNest = findNestNearestToCenter(level);
        if (bestNest == null) return;

        BlockPos target = findAdjacentSpotCompact(level, bestNest);
        if (target != null && canPlaceSoilSafely(level, target, this.id)) {
            if (!isWithinExpansionLimit(target)) {
                return;
            }

            placeHiveSoil(level, target, this.id);
            killsPool -= 5;
        }
    }

    private BlockPos findNestNearestToCenter(Level level) {
        BlockPos fallback = findNestNeedingExpansion(level);
        if (hiveCenter == null) return fallback;

        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;

        for (BlockPos nestPos : wormCounts.keySet()) {
            if (!level.isLoaded(nestPos)) continue;
            if (nestPos == null) continue;

            double distToCenter = nestPos.distSqr(hiveCenter);
            int neighbors = countNetworkNeighbors(level, nestPos);
            double score = distToCenter + neighbors * 10;

            if (score < minDist) {
                minDist = score;
                nearest = nestPos;
            }
        }

        return nearest != null ? nearest : fallback;
    }

    private BlockPos findAdjacentSpotCompact(Level level, BlockPos center) {
        if (hiveCenter != null) {
            Vec3 toCenter = new Vec3(hiveCenter.getX() - center.getX(), 0, hiveCenter.getZ() - center.getZ()).normalize();

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

        Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : horizontal) {
            BlockPos target = center.relative(dir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

        for (Direction dir : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockPos target = center.relative(dir);
            if (canPlaceSoilSafely(level, target, this.id) && isWithinExpansionLimit(target)) {
                return target;
            }
        }

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

    private void tryUpgradeSoilToNest(Level level, boolean needSpaceForNewWorm) {
        if (killsPool < 15 || wormCounts.size() >= 8) return;

        int totalWorms = getTotalWormsIncludingActive(level);
        int nests = wormCounts.size();
        int maxCapacity = nests * 3;

        if (totalWorms < maxCapacity && !needSpaceForNewWorm) {
            return;
        }

        if (hiveCenter == null && !wormCounts.isEmpty()) {
            hiveCenter = wormCounts.keySet().iterator().next().immutable();
        }

        for (BlockPos soilPos : new ArrayList<>(members)) {
            if (wormCounts.containsKey(soilPos)) continue;

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

            upgradeSoilToNest(level, soilPos);
            return;
        }
    }

    private void upgradeSoilToNest(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HiveSoilBlockEntity soil)) {
            return;
        }

        UUID preservedId = soil.getNetworkId();
        if (preservedId == null || !preservedId.equals(this.id)) {
            return;
        }
        if (wormCounts.size() >= 8) {
            return;
        }
        if (!hasNetworkNeighbor(level, pos)) {
            return;
        }

        members.remove(pos);

        level.setBlock(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState(), 3);

        BlockEntity newBe = ModBlockEntities.DEPTH_WORM_NEST.get().create(pos,
                ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState());

        if (!(newBe instanceof DepthWormNestBlockEntity nest)) {
            return;
        }

        nest.setNetworkId(preservedId);
        level.setBlockEntity(newBe);

        BlockEntity verify = level.getBlockEntity(pos);
        if (!(verify instanceof DepthWormNestBlockEntity) ||
                !preservedId.equals(((DepthWormNestBlockEntity)verify).getNetworkId())) {
            return;
        }

        wormCounts.put(pos, 0);
        members.add(pos);
        nestsBuiltTotal++;
        killsPool -= 15;

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

    private void spawnNewWormOptimally(Level level) {
        if (spawnRecursionDepth >= MAX_SPAWN_RECURSION) {
            spawnRecursionDepth = 0;
            return;
        }
        spawnRecursionDepth++;

        try {
            BlockPos bestNest = null;
            int minWorms = Integer.MAX_VALUE;
            int nests = wormCounts.size();
            int totalWorms = getTotalWormsIncludingActive(level);
            int maxCapacity = nests * 3;

            if (totalWorms >= maxCapacity) {
                if (killsPool >= 15 && wormCounts.size() < 8) {
                    int nestsBefore = wormCounts.size();
                    tryUpgradeSoilToNest(level, true);

                    if (wormCounts.size() > nestsBefore) {
                        spawnNewWormOptimally(level);
                    }
                }
                return;
            }

            for (BlockPos nestPos : wormCounts.keySet()) {
                if (!level.isLoaded(nestPos)) continue;

                BlockEntity be = level.getBlockEntity(nestPos);
                if (!(be instanceof DepthWormNestBlockEntity nest)) continue;

                int stored = nest.getStoredWormsCount();
                int active = activeWormCounts.getOrDefault(nestPos, 0);
                int totalAtNest = stored + active;

                if (totalAtNest < minWorms && !nest.isFull()) {
                    bestNest = nestPos;
                    minWorms = totalAtNest;
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

        if (!hasNetworkNeighbor) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isAir()) {
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

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("KillsPool", this.killsPool);
        tag.putLong("LastFed", this.lastFedTime);
        tag.putLong("StarvationStart", this.starvationStartTime);
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
        // ⭐ Гарантируем минимум 1 очко при загрузке если сеть не мертва
        if (net.killsPool <= 0) {
            net.killsPool = 1;
        }

        net.lastFedTime = tag.getLong("LastFed");
        net.starvationStartTime = tag.getLong("StarvationStart");

        try {
            net.currentScenario = DevelopmentScenario.valueOf(tag.getString("Scenario"));
        } catch (IllegalArgumentException | NullPointerException e) {
            net.currentScenario = DevelopmentScenario.STARTUP;
        }

        try {
            net.currentState = HiveState.valueOf(tag.getString("CurrentState"));
        } catch (IllegalArgumentException | NullPointerException e) {
            net.currentState = HiveState.EXPANSION;
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