package com.cim.api.vein;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VeinManager extends SavedData {
    private static final String DATA_NAME = "cim_vein_manager";

    // Только мета-информация о жилах (не полные данные)
    private final Map<UUID, VeinMetadata> veinIndex = new HashMap<>();

    // Кэш активных (загруженных) жил
    private final Map<UUID, VeinData> activeVeins = new ConcurrentHashMap<>();

    // Обратный индекс: чанк -> список жил в нём
    private final Map<ChunkPos, Set<UUID>> chunkToVeins = new HashMap<>();

    public static VeinManager get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(
                VeinManager::load,
                VeinManager::new,
                DATA_NAME
        );
    }

    /**
     * Регистрирует новую жилу при генерации мира
     */
    public UUID registerVein(Set<BlockPos> blocks, VeinType type) {
        UUID id = UUID.randomUUID();

        // Вычисляем границы
        int minX = blocks.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = blocks.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = blocks.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = blocks.stream().mapToInt(BlockPos::getZ).max().orElse(0);

        ChunkPos minChunk = new ChunkPos(minX >> 4, minZ >> 4);
        ChunkPos maxChunk = new ChunkPos(maxX >> 4, maxZ >> 4);

        // Индексируем по чанкам
        for (int cx = minChunk.x; cx <= maxChunk.x; cx++) {
            for (int cz = minChunk.z; cz <= maxChunk.z; cz++) {
                chunkToVeins.computeIfAbsent(new ChunkPos(cx, cz), k -> new HashSet<>()).add(id);
            }
        }

        // Создаём метаданные
        VeinMetadata meta = new VeinMetadata(id, type, blocks.size() * 1000, minChunk, maxChunk);
        veinIndex.put(id, meta);

        // Создаём активные данные
        VeinData data = new VeinData(id, type, blocks);
        activeVeins.put(id, data);

        setDirty();
        return id;
    }

    /**
     * Получает жилу (из кэша или загружает)
     */
    public VeinData getVein(UUID id) {
        // Если в кэше — отдаём
        VeinData cached = activeVeins.get(id);
        if (cached != null) return cached;

        // Иначе загружаем из NBT чанка (lazy loading)
        // Реализация ниже...
        return loadVeinFromStorage(id);
    }

    /**
     * Вызывается при загрузке чанка
     */
    public void onChunkLoad(ChunkPos pos) {
        Set<UUID> veinsInChunk = chunkToVeins.get(pos);
        if (veinsInChunk == null) return;

        for (UUID veinId : veinsInChunk) {
            if (!activeVeins.containsKey(veinId)) {
                // Ленивая загрузка
                VeinData data = loadVeinFromStorage(veinId);
                if (data != null) activeVeins.put(veinId, data);
            }
        }
    }

    /**
     * Вызывается при выгрузке чанка
     */
    public void onChunkUnload(ChunkPos pos) {
        Set<UUID> veinsInChunk = chunkToVeins.get(pos);
        if (veinsInChunk == null) return;

        // Проверяем, все ли чанки жилы выгружены
        for (UUID veinId : veinsInChunk) {
            VeinMetadata meta = veinIndex.get(veinId);
            if (meta != null && areAllChunksUnloaded(meta)) {
                // Сохраняем и выгружаем из RAM
                VeinData data = activeVeins.remove(veinId);
                if (data != null) saveVeinToStorage(data);
            }
        }
    }

    private boolean areAllChunksUnloaded(VeinMetadata meta) {
        // Проверка через ServerChunkCache...
        return false; // Заглушка
    }

    private VeinData loadVeinFromStorage(UUID id) {
        // Загрузка из DimensionDataStorage или NBT чанка
        return null;
    }

    private void saveVeinToStorage(VeinData data) {
        // Сохранение
    }

    // ==== Сериализация ====

    @Override
    public CompoundTag save(CompoundTag tag) {
        // Сохраняем только индекс
        ListTag indexList = new ListTag();
        veinIndex.values().forEach(meta -> indexList.add(meta.serialize()));
        tag.put("VeinIndex", indexList);
        return tag;
    }

    public static VeinManager load(CompoundTag tag) {
        VeinManager manager = new VeinManager();
        ListTag indexList = tag.getList("VeinIndex", 10);
        indexList.forEach(nbt -> {
            VeinMetadata meta = VeinMetadata.deserialize((CompoundTag) nbt);
            manager.veinIndex.put(meta.id, meta);
        });
        return manager;
    }

    // ==== Внутренние классы ====

    public static class VeinMetadata {
        public final UUID id;
        public final VeinType type;
        public final int maxUnits;
        public final ChunkPos minChunk;
        public final ChunkPos maxChunk;
        public int remainingUnits; // Обновляется при сохранении

        public VeinMetadata(UUID id, VeinType type, int maxUnits, ChunkPos minChunk, ChunkPos maxChunk) {
            this.id = id;
            this.type = type;
            this.maxUnits = maxUnits;
            this.remainingUnits = maxUnits;
            this.minChunk = minChunk;
            this.maxChunk = maxChunk;
        }

        public CompoundTag serialize() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putString("Type", type.name());
            tag.putInt("MaxUnits", maxUnits);
            tag.putInt("Remaining", remainingUnits);
            tag.putInt("MinCX", minChunk.x);
            tag.putInt("MinCZ", minChunk.z);
            tag.putInt("MaxCX", maxChunk.x);
            tag.putInt("MaxCZ", maxChunk.z);
            return tag;
        }
        public static VeinMetadata deserialize(CompoundTag tag) {
            UUID id = tag.getUUID("Id");
            VeinType type = VeinType.valueOf(tag.getString("Type"));
            int maxUnits = tag.getInt("MaxUnits");  // Было: int max = ... (конфликт с параметром max)
            ChunkPos min = new ChunkPos(tag.getInt("MinCX"), tag.getInt("MinCZ"));
            ChunkPos maxChunk = new ChunkPos(tag.getInt("MaxCX"), tag.getInt("MaxCZ"));  // Было: max (конфликт)
            VeinMetadata meta = new VeinMetadata(id, type, maxUnits, min, maxChunk);
            meta.remainingUnits = tag.getInt("Remaining");
            return meta;
        }
    }

    public static class VeinData {
        public final UUID id;
        public final VeinType type;
        public final Set<BlockPos> blocks;
        private int remainingUnits;
        private final Map<String, Integer> composition;

        public VeinData(UUID id, VeinType type, Set<BlockPos> blocks) {
            this.id = id;
            this.type = type;
            this.blocks = blocks;
            this.remainingUnits = blocks.size() * 1000;
            this.composition = type.generateComposition();
        }

        public void consumeUnits(int amount) {
            this.remainingUnits = Math.max(0, remainingUnits - amount);
            VeinManager.get(null).setDirty(); // Нужен доступ к менеджеру
        }

        public int getRemainingUnits() { return remainingUnits; }
        public boolean isDepleted() { return remainingUnits <= 0; }
        public float getDepletionRatio() {
            return 1.0f - ((float)remainingUnits / (blocks.size() * 1000f));
        }
        public Map<String, Integer> getComposition() { return composition; }

        public String getTypeName() {
            return type.name().toLowerCase();
        }
    }

    public enum VeinType {
        IRON_COPPER("iron", "copper", "slag"),
        LEAD_ZINC("lead", "zinc", "slag"),
        TIN_ANTIMONY("tin", "antimony", "slag"),
        RARE_EARTH("neodymium", "lanthanum", "thorium", "slag"),
        GOLD_SILVER("gold", "silver", "slag");

        private final String[] metals;

        VeinType(String... metals) {
            this.metals = metals;
        }

        public Map<String, Integer> generateComposition() {
            Map<String, Integer> comp = new HashMap<>();
            Random rand = new Random();
            int remaining = 100;

            // Последний всегда шлак/камень
            for (int i = 0; i < metals.length - 1; i++) {
                int max = remaining - (metals.length - i - 1) * 5; // Минимум 5% на оставшиеся
                int percent = 10 + rand.nextInt(Math.max(1, max - 10));
                comp.put(metals[i], percent);
                remaining -= percent;
            }
            comp.put(metals[metals.length - 1], remaining);

            return comp;
        }
    }
}