package com.cim.api.hive;

import com.cim.block.basic.ModBlocks;
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
    public int killsPool = 0;
    private long lastFedTime = 0;
    public boolean isDead() {
        return wormCounts.isEmpty() && members.isEmpty();
    }

    public boolean isAbandoned() {
        return wormCounts.isEmpty() && !members.isEmpty(); // Есть почва, нет гнезд
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
        if (isNest) wormCounts.put(pos, 0);
    }

    public void removeMember(BlockPos pos) {
        members.remove(pos);
        wormCounts.remove(pos);
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
            System.out.println("[HiveNetwork] ОШИБКА: В сети " + id + " нет зарегистрированных гнезд!");
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
            wormCounts.put(bestNest, wormCounts.get(bestNest) + 1);
            System.out.println("[HiveNetwork] УСПЕХ: Червь направлен в гнездо " + bestNest);
            return true;
        }

        System.out.println("[HiveNetwork] ВАРНИНГ: Все гнезда в сети " + id + " переполнены!");
        return false;
    }

    public void update(Level level) {
        if (level.isClientSide) return;

        // Проверяем, есть ли вообще гнезда в сети
        if (wormCounts.isEmpty()) {
            // Нет гнезд — сеть "мертва", но почва остаётся
            // Опционально: превращаем почву в мёртвую или удаляем сеть
            if (members.isEmpty()) {
                return; // Полностью пустая сеть — ничего не делаем
            }

            // Если есть только почва без гнезд — переход в режим "заброшенности"
            if (currentState != HiveState.STARVATION) {
                System.out.println("[Hive " + id + "] Нет гнезд — сеть заброшена");
                currentState = HiveState.STARVATION;
            }

            // Не тратим очки, не делаем действий — только ждём возможного восстановления
            return;
        }
        if (level.getGameTime() % 40 == 0) {
            System.out.println("[Hive Tick] Сеть " + this.id + " | Состояние: " + currentState +
                    " | Очки: " + killsPool + " | Узлов: " + members.size() +
                    " | Червей: " + getTotalWorms() + " | Угроза: " + threatLevel);

            if (members.isEmpty()) {
                System.out.println("[Hive Tick] ОШИБКА: Список узлов пуст!");
                return;
            }

            makeDecisions(level);
        }
    }

    // ==================== СИСТЕМА СОСТОЯНИЙ ====================

    private void makeDecisions(Level level) {
        if (killsPool <= 0 && !members.isEmpty()) {
            enterStarvationMode(level);
            return;
        }

        if (killsPool <= 0) return;

        // Анализируем ситуацию каждые 5 секунд (100 тиков)
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
            System.out.println("[Hive " + id + "] Смена состояния: " + currentState);
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

    private HiveState determineOptimalState() {
        // Приоритеты: Голод > Оборона > Восстановление > Агрессия > Экспансия

        if (killsPool <= 0) return HiveState.STARVATION;
        if (threatLevel > 20) return HiveState.DEFENSIVE;

        // Проверяем нужду в лечении
        int injuredNests = 0;
        for (int count : wormCounts.values()) {
            // Упрощенная проверка - в реальности нужно проверять каждое гнездо
            if (count > 0 && threatLevel > 10) injuredNests++;
        }
        if (injuredNests > wormCounts.size() / 2) return HiveState.RECOVERY;

        // Если много червей и есть угроза - атакуем
        if (getTotalWorms() >= 4 && threatLevel > 5) return HiveState.AGGRESSIVE;

        // Если мало территории относительно червей - экспансия
        if (expansionPressure > 30) return HiveState.EXPANSION;

        // По умолчанию - агрессия если есть червя, иначе экспансия
        return getTotalWorms() > 2 ? HiveState.AGGRESSIVE : HiveState.EXPANSION;
    }

    // ==================== ОБРАБОТЧИКИ СОСТОЯНИЙ ====================

    private void enterStarvationMode(Level level) {
        if (currentState != HiveState.STARVATION) {
            currentState = HiveState.STARVATION;
            System.out.println("[Hive " + id + "] КРИТИЧЕСКИЙ ГОЛОД! Переход в режим выживания.");
        }

        // В режиме голодания ничего не делаем, только ждем
        // Можно добавить логику "поедания" собственных червей для выживания
        if (level.getGameTime() % 200 == 0 && getTotalWorms() > 0) {
            System.out.println("[Hive " + id + "] Голодание... червей: " + getTotalWorms());
        }
    }

    private void handleStarvation(Level level) {
        // Ждем пока появятся очки (от убитых врагов)
        if (killsPool > 0) {
            System.out.println("[Hive " + id + "] Восстановлено питание! Выход из голодания.");
            currentState = HiveState.RECOVERY;
        }
    }

    private void handleDefensive(Level level) {
        // Ищем гнезда под угрозой и выпускаем червей для защиты
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

        // Защищаем одно гнездо за тик
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
                    killsPool -= 2; // Штраф за экстренный выпуск
                    System.out.println("[Hive] Экстренная защита гнезда " + defendPos);
                    return;
                }
            }
        }

        // Если угроза есть, но червей нет - экстренная экспансия для отвлечения
        if (threatLevel > 30 && killsPool >= 3) {
            emergencyExpansion(level);
        }
    }

    private void handleRecovery(Level level) {
        // Собираем раненые гнезда
        List<DepthWormNestBlockEntity> injuredNests = new ArrayList<>();

        for (BlockPos pos : new ArrayList<>(wormCounts.keySet())) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DepthWormNestBlockEntity nest && nest.hasInjuredWorms()) {
                injuredNests.add(nest);
            }
        }

        // Хилим по одному червю за тик
        for (DepthWormNestBlockEntity nest : injuredNests) {
            if (killsPool < 1) break;
            if (nest.healOneWorm()) {
                killsPool -= 1;
                System.out.println("[Hive] Вылечен червь в " + nest.getBlockPos());
                return; // Одно действие за тик
            }
        }

        // Если все здоровы - переходим в другое состояние
        if (injuredNests.isEmpty()) {
            currentState = (getTotalWorms() >= 4) ? HiveState.AGGRESSIVE : HiveState.EXPANSION;
        }
    }

    private void handleAggressive(Level level) {
        int readyWorms = getTotalWorms();

        // Фаза накопления
        if (readyWorms < 4 && killsPool >= 10) {
            spawnNewWormOptimally(level);
            return;
        }

        // Фаза атаки
        if (readyWorms >= 3) {
            LivingEntity target = findBestTarget(level);
            if (target == null) return;

            // Находим ближайшие гнезда к цели
            List<BlockPos> nearbyNests = findNestsNearTarget(level, target.blockPosition(), 25);

            int released = 0;
            for (BlockPos nestPos : nearbyNests) {
                if (released >= 2) break; // Максимум 2 гнезда за раз

                BlockEntity be = level.getBlockEntity(nestPos);
                if (be instanceof DepthWormNestBlockEntity nest && !nest.getStoredWorms().isEmpty()) {
                    nest.releaseWorms(nestPos, target);
                    released++;
                }
            }

            if (released > 0) {
                killsPool = Math.max(0, killsPool - released);
                successfulAttacks++;
                System.out.println("[Hive] Координированная атака! Выпущено: " + released +
                        " к " + target.getName().getString());
            }
        }
    }

    private void handleExpansion(Level level) {
        if (killsPool < 5) return;

        // Стратегическая экспансия
        BlockPos bestExpansion = findValuableExpansionTarget(level);
        if (bestExpansion != null) {
            BlockState currentState = level.getBlockState(bestExpansion);

            // Проверяем можно ли заменить блок
            if (!currentState.isAir() && currentState.getDestroySpeed(level, bestExpansion) >= 0 &&
                    !currentState.is(ModBlocks.HIVE_SOIL.get()) &&
                    !currentState.is(ModBlocks.DEPTH_WORM_NEST.get())) {

                level.setBlockAndUpdate(bestExpansion, ModBlocks.HIVE_SOIL.get().defaultBlockState());
                killsPool -= 5;
                System.out.println("[Hive] Экспансия: захвачена позиция " + bestExpansion);

                // Если хорошее место и много очков - строим гнездо
                if (shouldBuildNestThere(level, bestExpansion) && killsPool >= 15) {
                    upgradeSoilToNest(level, bestExpansion);
                }
            }
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private void emergencyExpansion(Level level) {
        // Быстрая экспансия для отвлечения врага
        for (BlockPos pos : new ArrayList<>(members)) {
            if (killsPool < 3) break;

            for (Direction dir : Direction.values()) {
                BlockPos target = pos.relative(dir);
                BlockState state = level.getBlockState(target);

                if (!state.isAir() && !state.is(ModBlocks.HIVE_SOIL.get()) &&
                        !state.is(ModBlocks.DEPTH_WORM_NEST.get()) &&
                        state.getDestroySpeed(level, target) >= 0) {

                    level.setBlockAndUpdate(target, ModBlocks.HIVE_SOIL.get().defaultBlockState());
                    killsPool -= 3; // Дешевле обычной экспансии
                    System.out.println("[Hive] Экстренная экспансия: " + target);
                    return;
                }
            }
        }
    }

    private void spawnNewWormOptimally(Level level) {
        // Ищем гнездо с минимумом червей
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
                double score = 1000.0 / (dist + 1); // Ближе = лучше

                // Бонус за одиночку
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

        // Сортируем по расстоянию
        result.sort(Comparator.comparingDouble(p -> p.distSqr(targetPos)));
        return result;
    }

    private BlockPos findValuableExpansionTarget(Level level) {
        // Ищем направление с потенциальной "ценностью" (где может быть еда/враги)
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

        // Выбираем лучшее направление
        Direction bestDir = directionValue.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Direction.NORTH);

        // Ищем первую валидную позицию в этом направлении от любого члена сети
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
        int value = 10; // Базовое значение

        // Бонус за близость к игрокам (потенциальная еда)
        AABB area = new AABB(pos).inflate(20);
        List<Player> players = level.getEntitiesOfClass(Player.class, area,
                p -> !p.isCreative() && !p.isSpectator());
        value += players.size() * 20;

        // Бонус за расстояние от других членов сети (разреженность)
        int nearbyMembers = 0;
        for (BlockPos member : members) {
            if (pos.distSqr(member) < 9) nearbyMembers++; // Расстояние < 3 блоков
        }
        value -= nearbyMembers * 5; // Штраф за толпу

        // Бонус за открытое пространство (воздух сверху)
        if (level.getBlockState(pos.above()).isAir()) value += 5;

        return Math.max(0, value);
    }

    private boolean shouldBuildNestThere(Level level, BlockPos pos) {
        // Строим гнездо если:
        // 1. Это безопасное место (нет игроков рядом)
        // 2. Есть поддержка от других гнезд (не изолировано)
        // 3. Мало гнезд в сети

        AABB dangerZone = new AABB(pos).inflate(10);
        List<Player> threats = level.getEntitiesOfClass(Player.class, dangerZone,
                p -> !p.isCreative() && !p.isSpectator());
        if (!threats.isEmpty()) return false;

        // Проверяем связность с существующими гнездами
        boolean nearExistingNest = wormCounts.keySet().stream()
                .anyMatch(nestPos -> nestPos.distSqr(pos) < 100); // < 10 блоков

        return nearExistingNest && wormCounts.size() < 5; // Максимум 5 гнезд
    }
    public boolean hasAnyLoadedChunk(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;

        ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;

        for (BlockPos pos : members) {
            ChunkPos chunkPos = new ChunkPos(pos);
            // getPlayers возвращает List, проверяем через isEmpty()
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

        // Заменяем блок
        level.setBlockAndUpdate(pos, ModBlocks.DEPTH_WORM_NEST.get().defaultBlockState());

        // Обновляем данные сети
        wormCounts.put(pos, 0);
        killsPool -= 15;

        System.out.println("[Hive] Построено новое гнездо в " + pos);
    }

    private void spawnNewWorm(DepthWormNestBlockEntity nest, BlockPos pos) {
        CompoundTag newWorm = new CompoundTag();
        newWorm.putFloat("Health", 15.0F);
        newWorm.putInt("Kills", 0);
        nest.addWormTag(newWorm);
        wormCounts.put(pos, wormCounts.getOrDefault(pos, 0) + 1);
        killsPool -= 10;
        System.out.println("[Hive] Рожден новый червь в " + pos + ". Остаток очков: " + killsPool);
    }

    // ==================== СЕРИАЛИЗАЦИЯ ====================

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
            }
            membersList.add(pTag);
        }
        tag.put("Members", membersList);

        // Сохраняем опасные зоны
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

        // Восстанавливаем состояние
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
            }
        }

        // Восстанавливаем опасные зоны
        ListTag dangerList = tag.getList("DangerZones", 10);
        for (int i = 0; i < dangerList.size(); i++) {
            net.dangerZones.add(NbtUtils.readBlockPos(dangerList.getCompound(i)));
        }

        return net;
    }

    private void die(Level level) {
        System.out.println("[HiveNetwork] КРИТИЧЕСКАЯ ОШИБКА: Сеть " + id + " погибла от голода!");

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
        killsPool = 0;
    }

    public void updateWormCount(BlockPos nestPos, int delta) {
        wormCounts.merge(nestPos, delta, Integer::sum);
        if (wormCounts.get(nestPos) < 0) {
            wormCounts.put(nestPos, 0);
        }
    }
}