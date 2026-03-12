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

import java.util.*;

public class HiveNetwork {
    public final UUID id;
    public final Set<BlockPos> members = new HashSet<>();
    public final Map<BlockPos, Integer> wormCounts = new HashMap<>();
    public final Map<BlockPos, List<CompoundTag>> nestWormData = new HashMap<>(); // НОВОЕ: Храним полные данные червей по гнездам
    public int killsPool = 0;
    private long lastFedTime = 0;

    public boolean isDead() {
        return wormCounts.isEmpty() && members.isEmpty();
    }

    public boolean isAbandoned() {
        return wormCounts.isEmpty() && !members.isEmpty();
    }

    // Новые поля для коллективного интеллекта
    public enum HiveState {
        EXPANSION, DEFENSIVE, AGGRESSIVE, RECOVERY, STARVATION
    }

    private HiveState currentState = HiveState.EXPANSION;
    private int threatLevel = 0;
    private int expansionPressure = 0;
    private long lastStateChange = 0;
    private int successfulAttacks = 0;
    private int failedDefenses = 0;
    private final Set<BlockPos> dangerZones = new HashSet<>();

    public HiveNetwork(UUID id) {
        this.id = id;
    }

    public void addMember(BlockPos pos, boolean isNest) {
        members.add(pos);
        if (isNest) {
            wormCounts.put(pos, 0);
            nestWormData.put(pos, new ArrayList<>()); // НОВОЕ
        }
    }

    public void clearNestWormData(BlockPos nestPos) {
        List<CompoundTag> data = nestWormData.get(nestPos);
        if (data != null) {
            data.clear();
        }
        wormCounts.put(nestPos, 0);
    }

    public void removeMember(BlockPos pos) {
        members.remove(pos);
        wormCounts.remove(pos);
        nestWormData.remove(pos); // НОВОЕ: Чистим данные червей
    }

    // НОВОЕ: Получить данные червей конкретного гнезда
    public List<CompoundTag> getNestWormData(BlockPos nestPos) {
        return nestWormData.getOrDefault(nestPos, new ArrayList<>());
    }

    // НОВОЕ: Добавить данные червя к конкретному гнезду
    public void addWormDataToNest(BlockPos nestPos, CompoundTag wormData) {
        // Проверяем, нет ли уже этого червя (по UUID если есть)
        List<CompoundTag> data = nestWormData.computeIfAbsent(nestPos, k -> new ArrayList<>());

        // Очищаем старые дубликаты если список слишком большой (защита от утечки)
        if (data.size() >= 3) {
            data.clear(); // Максимум 3 червя в гнезде
        }

        data.add(wormData);
        wormCounts.put(nestPos, data.size());
    }

    // НОВОЕ: Удалить данные червя из гнезда (при выпуске)
    public void removeWormDataFromNest(BlockPos nestPos, int index) {
        List<CompoundTag> data = nestWormData.get(nestPos);
        if (data != null && index < data.size()) {
            data.remove(index);
            wormCounts.put(nestPos, Math.max(0, wormCounts.getOrDefault(nestPos, 0) - 1));
        }
    }

    public boolean isNest(Level level, BlockPos pos) {
        return members.contains(pos) &&
                level.isLoaded(pos) &&
                level.getBlockState(pos).is(ModBlocks.DEPTH_WORM_NEST.get());
    }

    public int getTotalWorms() {
        return wormCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean addWorm(Level level, CompoundTag wormTag, BlockPos sourcePos) {
        if (wormCounts.isEmpty()) {
            System.out.println("[HiveNetwork] ERROR: No nests registered in network " + id);
            return false;
        }

        BlockPos bestNest = null;
        int minCount = Integer.MAX_VALUE;

        for (BlockPos nestPos : wormCounts.keySet()) {
            BlockEntity be = level.getBlockEntity(nestPos);
            if (be instanceof DepthWormNestBlockEntity nest) {
                int currentCount = wormCounts.getOrDefault(nestPos, 0);
                if (currentCount < 3 && currentCount < minCount) {
                    minCount = currentCount;
                    bestNest = nestPos;
                }
            }
        }

        if (bestNest != null) {
            DepthWormNestBlockEntity nestBE = (DepthWormNestBlockEntity) level.getBlockEntity(bestNest);
            nestBE.addWormTag(wormTag);
            addWormDataToNest(bestNest, wormTag); // НОВОЕ: Сохраняем в сети
            System.out.println("[HiveNetwork] SUCCESS: Worm assigned to nest " + bestNest);
            return true;
        }

        System.out.println("[HiveNetwork] WARNING: All nests full in network " + id);
        return false;
    }

    public void update(Level level) {
        if (level.isClientSide) return;

        if (wormCounts.isEmpty()) {
            if (members.isEmpty()) {
                return;
            }

            if (currentState != HiveState.STARVATION) {
                System.out.println("[Hive " + id + "] No nests - network abandoned");
                currentState = HiveState.STARVATION;
            }
            return;
        }

        if (level.getGameTime() % 40 == 0) {
            System.out.println("[Hive Tick] Network " + this.id + " | State: " + currentState +
                    " | Points: " + killsPool + " | Nodes: " + members.size() +
                    " | Worms: " + getTotalWormsReal() + " | Threat: " + threatLevel);

            if (members.isEmpty()) {
                System.out.println("[Hive Tick] ERROR: Member list empty!");
                return;
            }

            makeDecisions(level);
        }
    }

    // ==================== STATE SYSTEM ====================

    private void makeDecisions(Level level) {
        if (killsPool <= 0 && !members.isEmpty()) {
            enterStarvationMode(level);
            return;
        }

        if (killsPool <= 0) return;

        if (level.getGameTime() % 100 == 0) {
            analyzeSituation(level);
        }

        switch (currentState) {
            case STARVATION -> handleStarvation(level);
            case DEFENSIVE -> handleDefensive(level);
            case RECOVERY -> handleRecovery(level);
            case AGGRESSIVE -> handleAggressive(level);
            case EXPANSION -> handleExpansion(level);
        }
    }

    private void analyzeSituation(Level level) {
        int oldThreat = threatLevel;
        threatLevel = calculateThreatLevel(level);
        expansionPressure = calculateExpansionPressure(level);

        HiveState newState = determineOptimalState();
        if (newState != currentState) {
            currentState = newState;
            lastStateChange = level.getGameTime();
            System.out.println("[Hive " + id + "] State changed to: " + currentState);
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

    private int calculateExpansionPressure(Level level) {
        int worms = getTotalWorms();
        int territory = members.size();
        return territory > 0 ? (worms * 100) / territory : 0;
    }
    public int getTotalWormsReal() {
        int total = 0;
        for (List<CompoundTag> worms : nestWormData.values()) {
            total += worms.size();
        }
        return total;
    }
    private HiveState determineOptimalState() {
        if (killsPool <= 0) return HiveState.STARVATION;
        if (threatLevel > 20) return HiveState.DEFENSIVE;

        int injuredNests = 0;
        for (int count : wormCounts.values()) {
            if (count > 0 && threatLevel > 10) injuredNests++;
        }
        if (injuredNests > wormCounts.size() / 2) return HiveState.RECOVERY;

        if (getTotalWorms() >= 4 && threatLevel > 5) return HiveState.AGGRESSIVE;
        if (expansionPressure > 30) return HiveState.EXPANSION;

        return getTotalWorms() > 2 ? HiveState.AGGRESSIVE : HiveState.EXPANSION;
    }

    // ==================== STATE HANDLERS ====================

    private void enterStarvationMode(Level level) {
        if (currentState != HiveState.STARVATION) {
            currentState = HiveState.STARVATION;
            System.out.println("[Hive " + id + "] CRITICAL HUNGER! Survival mode activated.");
        }

        if (level.getGameTime() % 200 == 0 && getTotalWorms() > 0) {
            System.out.println("[Hive " + id + "] Starving... worms: " + getTotalWorms());
        }
    }

    private void handleStarvation(Level level) {
        if (killsPool > 0) {
            System.out.println("[Hive " + id + "] Nutrition restored! Exiting starvation.");
            currentState = HiveState.RECOVERY;
        }
    }

    private void handleDefensive(Level level) {
        List<BlockPos> threatenedNests = new ArrayList<>();

        for (BlockPos pos : new ArrayList<>(wormCounts.keySet())) {
            if (!level.isLoaded(pos)) continue;

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof DepthWormNestBlockEntity nest)) continue;

            AABB dangerZone = new AABB(pos).inflate(15);
            List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, dangerZone,
                    e -> e instanceof Player && !((Player)e).isCreative() && e.isAlive());

            if (!enemies.isEmpty() && !nest.getStoredWorms().isEmpty()) {
                threatenedNests.add(pos);
            }
        }

        if (!threatenedNests.isEmpty()) {
            BlockPos defendPos = threatenedNests.get(0);
            BlockEntity be = level.getBlockEntity(defendPos);
            if (be instanceof DepthWormNestBlockEntity nest && !nest.getStoredWorms().isEmpty()) {
                AABB dangerZone = new AABB(defendPos).inflate(15);
                List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, dangerZone,
                        e -> e instanceof Player && !((Player)e).isCreative());

                if (!enemies.isEmpty()) {
                    LivingEntity target = enemies.get(0);
                    nest.releaseWorms(defendPos, target);
                    killsPool -= 2;
                    System.out.println("[Hive] Emergency defense at nest " + defendPos);
                    return;
                }
            }
        }

        if (threatLevel > 30 && killsPool >= 3) {
            emergencyExpansion(level);
        }
    }

    private void handleRecovery(Level level) {
        List<DepthWormNestBlockEntity> injuredNests = new ArrayList<>();

        for (BlockPos pos : new ArrayList<>(wormCounts.keySet())) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DepthWormNestBlockEntity nest && nest.hasInjuredWorms()) {
                injuredNests.add(nest);
            }
        }

        for (DepthWormNestBlockEntity nest : injuredNests) {
            if (killsPool < 1) break;
            if (nest.healOneWorm()) {
                killsPool -= 1;
                System.out.println("[Hive] Healed worm at " + nest.getBlockPos());
                return;
            }
        }

        if (injuredNests.isEmpty()) {
            currentState = (getTotalWorms() >= 4) ? HiveState.AGGRESSIVE : HiveState.EXPANSION;
        }
    }

    private void handleAggressive(Level level) {
        int readyWorms = getTotalWorms();

        if (readyWorms < 4 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        if (readyWorms >= 3) {
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
                System.out.println("[Hive] Coordinated attack! Released: " + released +
                        " towards " + target.getName().getString());
            }
        }
    }

    // В HiveNetwork.handleExpansion — замени установку блока:
    public void handleExpansion(Level level) {
        if (killsPool < 5) return;

        BlockPos bestExpansion = findValuableExpansionTarget(level);
        if (bestExpansion != null) {
            BlockState currentState = level.getBlockState(bestExpansion);

            if (!currentState.isAir() && currentState.getDestroySpeed(level, bestExpansion) >= 0 &&
                    !currentState.is(ModBlocks.HIVE_SOIL.get()) &&
                    !currentState.is(ModBlocks.DEPTH_WORM_NEST.get())) {

                // Устанавливаем блок
                level.setBlock(bestExpansion, ModBlocks.HIVE_SOIL.get().defaultBlockState(), 3);

                // Принудительно создаём и настраиваем BlockEntity
                BlockEntity be = ModBlockEntities.HIVE_SOIL.get().create(bestExpansion, ModBlocks.HIVE_SOIL.get().defaultBlockState());
                if (be instanceof HiveSoilBlockEntity soil) {
                    soil.setNetworkId(this.id);
                    level.setBlockEntity(soil);

                    // Регистрируем в менеджере
                    HiveNetworkManager manager = HiveNetworkManager.get(level);
                    if (manager != null) {
                        manager.addNode(this.id, bestExpansion, false);
                    }

                    killsPool -= 5;
                    System.out.println("[Hive] Expansion: captured position " + bestExpansion + " for network " + this.id);

                    if (shouldBuildNestThere(level, bestExpansion) && killsPool >= 15) {
                        upgradeSoilToNest(level, bestExpansion);
                    }
                }
            }
        }
    }

    // ==================== UTILITY METHODS ====================

    private void emergencyExpansion(Level level) {
        for (BlockPos pos : new ArrayList<>(members)) {
            if (killsPool < 3) break;

            for (Direction dir : Direction.values()) {
                BlockPos target = pos.relative(dir);
                BlockState state = level.getBlockState(target);

                if (!state.isAir() && !state.is(ModBlocks.HIVE_SOIL.get()) &&
                        !state.is(ModBlocks.DEPTH_WORM_NEST.get()) &&
                        state.getDestroySpeed(level, target) >= 0) {

                    level.setBlockAndUpdate(target, ModBlocks.HIVE_SOIL.get().defaultBlockState());
                    killsPool -= 3;
                    System.out.println("[Hive] Emergency expansion: " + target);
                    return;
                }
            }
        }
    }

    private void spawnNewWormOptimally(Level level) {
        BlockPos bestNest = null;
        int minWorms = Integer.MAX_VALUE;

        for (Map.Entry<BlockPos, Integer> entry : wormCounts.entrySet()) {
            if (entry.getValue() < minWorms && entry.getValue() < 3) {
                BlockEntity be = level.getBlockEntity(entry.getKey());
                if (be instanceof DepthWormNestBlockEntity nest) {
                    bestNest = entry.getKey();
                    minWorms = entry.getValue();
                }
            }
        }

        if (bestNest != null && killsPool >= 10) {
            BlockEntity be = level.getBlockEntity(bestNest);
            if (be instanceof DepthWormNestBlockEntity nest) {
                spawnNewWorm(nest, bestNest);
            }
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

    private BlockPos findValuableExpansionTarget(Level level) {
        Map<Direction, Integer> directionValue = new EnumMap<>(Direction.class);

        for (BlockPos pos : new ArrayList<>(members)) {
            if (!level.isLoaded(pos)) continue;

            for (Direction dir : Direction.values()) {
                BlockPos checkPos = pos.relative(dir);
                if (!isValidExpansionTarget(level, checkPos)) continue;

                int value = evaluatePosition(level, checkPos);
                directionValue.merge(dir, value, Integer::sum);
            }
        }

        Direction bestDir = directionValue.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Direction.NORTH);

        for (BlockPos pos : members) {
            BlockPos target = pos.relative(bestDir);
            if (isValidExpansionTarget(level, target)) {
                return target;
            }
        }

        return null;
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

    private int evaluatePosition(Level level, BlockPos pos) {
        int value = 10;

        AABB area = new AABB(pos).inflate(20);
        List<Player> players = level.getEntitiesOfClass(Player.class, area,
                p -> !p.isCreative() && !p.isSpectator());
        value += players.size() * 20;

        int nearbyMembers = 0;
        for (BlockPos member : members) {
            if (pos.distSqr(member) < 9) nearbyMembers++;
        }
        value -= nearbyMembers * 5;

        if (level.getBlockState(pos.above()).isAir()) value += 5;

        return Math.max(0, value);
    }

    private boolean shouldBuildNestThere(Level level, BlockPos pos) {
        AABB dangerZone = new AABB(pos).inflate(10);
        List<Player> threats = level.getEntitiesOfClass(Player.class, dangerZone,
                p -> !p.isCreative() && !p.isSpectator());
        if (!threats.isEmpty()) return false;

        boolean nearExistingNest = wormCounts.keySet().stream()
                .anyMatch(nestPos -> nestPos.distSqr(pos) < 100);

        return nearExistingNest && wormCounts.size() < 5;
    }

    public boolean hasAnyLoadedChunk(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;

        ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;

        for (BlockPos pos : members) {
            ChunkPos chunkPos = new ChunkPos(pos);
            if (!chunkMap.getPlayers(chunkPos, false).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void upgradeSoilToNest(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof HiveSoilBlockEntity soil)) return;

        UUID netId = soil.getNetworkId();
        if (netId == null || !netId.equals(this.id)) return;

        // Сохраняем UUID
        UUID preservedId = netId;

        // Заменяем блок
        level.setBlock(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState(), 3);

        // Принудительно создаём гнездо с сохранённым UUID
        BlockEntity newBe = ModBlockEntities.DEPTH_WORM_NEST.get().create(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState());
        if (newBe instanceof DepthWormNestBlockEntity nest) {
            nest.setNetworkId(preservedId);
            level.setBlockEntity(nest);

            // Обновляем данные сети
            wormCounts.put(pos, 0);
            nestWormData.put(pos, new ArrayList<>());
            killsPool -= 15;

            System.out.println("[Hive] New nest built at " + pos + " for network " + preservedId);
        }
    }

    private void spawnNewWorm(DepthWormNestBlockEntity nest, BlockPos pos) {
        CompoundTag newWorm = new CompoundTag();
        newWorm.putFloat("Health", 15.0F);
        newWorm.putInt("Kills", 0);
        nest.addWormTag(newWorm);
        addWormDataToNest(pos, newWorm); // НОВОЕ
        killsPool -= 10;
        System.out.println("[Hive] New worm spawned at " + pos + ". Points remaining: " + killsPool);
    }

    // ==================== SERIALIZATION ====================

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("KillsPool", this.killsPool);
        tag.putLong("LastFed", this.lastFedTime);
        tag.putString("CurrentState", this.currentState.name());
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

                // НОВОЕ: Сохраняем данные червей гнезда
                ListTag wormDataList = new ListTag();
                List<CompoundTag> wormData = nestWormData.get(p);
                if (wormData != null) {
                    wormDataList.addAll(wormData);
                }
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

        return tag;
    }

    public static HiveNetwork fromNBT(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        HiveNetwork net = new HiveNetwork(id);

        net.killsPool = tag.getInt("KillsPool");
        net.lastFedTime = tag.getLong("LastFed");

        try {
            net.currentState = HiveState.valueOf(tag.getString("CurrentState"));
        } catch (IllegalArgumentException e) {
            net.currentState = HiveState.EXPANSION;
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

                // НОВОЕ: Восстанавливаем данные червей
                ListTag wormDataList = pTag.getList("WormData", 10);
                List<CompoundTag> wormData = new ArrayList<>();
                for (int j = 0; j < wormDataList.size(); j++) {
                    wormData.add(wormDataList.getCompound(j));
                }
                net.nestWormData.put(pos, wormData);
            }
        }

        ListTag dangerList = tag.getList("DangerZones", 10);
        for (int i = 0; i < dangerList.size(); i++) {
            net.dangerZones.add(NbtUtils.readBlockPos(dangerList.getCompound(i)));
        }

        return net;
    }

    private void die(Level level) {
        System.out.println("[HiveNetwork] CRITICAL: Network " + id + " died of starvation!");

        for (BlockPos pos : new HashSet<>(members)) {
            BlockState state = level.getBlockState(pos);

            if (state.is(ModBlocks.DEPTH_WORM_NEST.get())) {
                level.setBlockAndUpdate(pos, ModBlocks.DEPTH_WORM_NEST_DEAD.get().defaultBlockState());
            } else if (state.is(ModBlocks.HIVE_SOIL.get())) {
                level.setBlockAndUpdate(pos, ModBlocks.HIVE_SOIL_DEAD.get().defaultBlockState());
            }

            HiveNetworkManager manager = HiveNetworkManager.get(level);
            if (manager != null) {
                manager.removeNode(this.id, pos, level);
            }
        }

        members.clear();
        wormCounts.clear();
        nestWormData.clear(); // НОВОЕ
        killsPool = 0;
    }

    public void updateWormCount(BlockPos nestPos, int delta) {
        wormCounts.merge(nestPos, delta, Integer::sum);
        if (wormCounts.get(nestPos) < 0) {
            wormCounts.put(nestPos, 0);
        }
    }
}