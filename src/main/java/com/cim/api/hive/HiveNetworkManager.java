package com.cim.api.hive;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import com.cim.block.entity.hive.DepthWormNestBlockEntity;

import java.util.*;

public class HiveNetworkManager {
    private final Map<UUID, Set<BlockPos>> networkNodes = new HashMap<>();
    private final Map<UUID, HiveNetwork> networks = new HashMap<>();
    private final Map<BlockPos, UUID> posToNetwork = new HashMap<>();

    public void tick(Level level) {
        if (networks.isEmpty()) return;

        List<HiveNetwork> safeCopy = new ArrayList<>(networks.values());

        for (HiveNetwork network : safeCopy) {
            // ⭐ ИСПРАВЛЕНО: Передаём level в isDead()
            if (network.isDead(level)) {
                // Проверяем ещё раз через тик - может червяк вернулся
                if (network.isDead(level)) {
                    networks.remove(network.id);
                    System.out.println("[HiveManager] Removed dead network " + network.id);
                }
                continue;
            }

            // ⭐ ИСПРАВЛЕНО: Передаём level в isAbandoned()
            if (network.isAbandoned(level)) {
                System.out.println("[HiveManager] Network " + network.id + " is abandoned but keeping for now");
                continue;
            }

            if (!network.hasAnyLoadedChunk(level)) continue;
            network.update(level);
        }
    }

    public static HiveNetworkManager get(Level level) {
        return level.getCapability(HiveNetworkManagerProvider.HIVE_NETWORK_MANAGER).orElse(null);
    }

    public HiveNetwork getNetwork(UUID id) {
        if (id == null) return null;
        return networks.computeIfAbsent(id, HiveNetwork::new);
    }

    public void addNode(UUID networkId, BlockPos pos, boolean isNest) {
        HiveNetwork network = getNetwork(networkId);
        network.addMember(pos, isNest);
        posToNetwork.put(pos, networkId);
        networkNodes.computeIfAbsent(networkId, k -> new HashSet<>()).add(pos);
    }

    // ⭐ ИСПРАВЛЕНО: mergeNetworks без nestWormData
    public void mergeNetworks(UUID mainId, UUID secondId, Level level) {
        if (mainId.equals(secondId)) return;

        HiveNetwork mainNet = getNetwork(mainId);
        HiveNetwork secondNet = networks.get(secondId);
        if (secondNet == null) return;

        System.out.println("[Hive] Merging networks! " + secondId + " into " + mainId);

        // Переносим очки
        int killsToTransfer = Math.min(50, secondNet.killsPool);
        mainNet.killsPool = Math.min(50, mainNet.killsPool + killsToTransfer);

        // Копируем членов во временный список
        Set<BlockPos> membersToTransfer = new HashSet<>(secondNet.members);

        for (BlockPos pos : membersToTransfer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HiveNetworkMember member) {
                member.setNetworkId(mainId);
            }

            posToNetwork.put(pos, mainId);

            // ⭐ ИСПРАВЛЕНО: Копируем только счётчики, не дублируем данные червей
            if (secondNet.wormCounts.containsKey(pos)) {
                int wormCount = secondNet.wormCounts.getOrDefault(pos, 0);
                // Получаем данные напрямую из BlockEntity, не из кеша сети
                mainNet.wormCounts.put(pos, wormCount);
            }
            mainNet.members.add(pos);
        }

        // Переносим активных червей
        for (Map.Entry<BlockPos, Integer> entry : secondNet.activeWormCounts.entrySet()) {
            mainNet.activeWormCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        // Удаляем вторую сеть
        networks.remove(secondId);
        networkNodes.remove(secondId);

        System.out.println("[Hive] Merge complete. Main network now has " + mainNet.members.size() + " members");
    }

    public void removeNode(UUID networkId, BlockPos pos, Level level) {
        HiveNetwork network = networks.get(networkId);
        if (network != null) {
            network.removeMember(pos);
            if (network.isDead(level)) { // ⭐ ИСПРАВЛЕНО
                networks.remove(networkId);
            }
        }
        posToNetwork.remove(pos);
        Set<BlockPos> nodes = networkNodes.get(networkId);
        if (nodes != null) nodes.remove(pos);
    }

    public BlockPos findNearestNode(UUID networkId, Vec3 targetPos, Level level) {
        HiveNetwork network = getNetwork(networkId);
        if (network == null || network.members.isEmpty()) return null;

        BlockPos targetBlockPos = BlockPos.containing(targetPos);
        BlockPos closest = null;
        double minDistance = Double.MAX_VALUE;

        for (BlockPos pos : network.members) {
            double dist = pos.distSqr(targetBlockPos);
            if (dist < minDistance) {
                minDistance = dist;
                closest = pos;
            }
        }
        return closest;
    }

    // ⭐ ИСПРАВЛЕНО: addWormToNetwork с проверкой заполненности
    public boolean addWormToNetwork(UUID networkId, CompoundTag wormData, BlockPos entryPos, Level level) {
        HiveNetwork network = getNetwork(networkId);
        if (network == null) return false;

        // Ищем ближайшее НЕ ЗАПОЛНЕННОЕ гнездо
        BlockPos bestNest = null;
        int minWorms = Integer.MAX_VALUE;

        for (BlockPos pos : network.members) {
            if (!network.wormCounts.containsKey(pos)) continue; // Только гнезда

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof DepthWormNestBlockEntity nest)) continue;
            if (nest.isFull()) continue; // Пропускаем заполненные!

            int count = nest.getStoredWormsCount();
            if (count < minWorms) {
                minWorms = count;
                bestNest = pos;
            }
        }

        if (bestNest == null) return false; // Нет места

        BlockEntity be = level.getBlockEntity(bestNest);
        if (be instanceof DepthWormNestBlockEntity nest) {
            nest.addWormTag(wormData);
            network.wormCounts.put(bestNest, nest.getStoredWormsCount());
            return true;
        }

        return false;
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag networksList = new ListTag();
        for (HiveNetwork net : networks.values()) {
            networksList.add(net.toNBT());
        }
        tag.put("Networks", networksList);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        networks.clear();
        posToNetwork.clear();
        networkNodes.clear();

        ListTag networksList = tag.getList("Networks", 10);
        for (int i = 0; i < networksList.size(); i++) {
            HiveNetwork net = HiveNetwork.fromNBT(networksList.getCompound(i));
            networks.put(net.id, net);

            for (BlockPos pos : net.members) {
                posToNetwork.put(pos, net.id);
                networkNodes.computeIfAbsent(net.id, k -> new HashSet<>()).add(pos);
            }
        }
    }

    public void updateWormCount(UUID networkId, BlockPos pos, int delta) {
        HiveNetwork network = getNetwork(networkId);
        if (network != null) {
            network.updateWormCount(pos, delta);
        }
    }

    public static final Capability<HiveNetworkManager> HIVE_NETWORK_MANAGER =
            CapabilityManager.get(new CapabilityToken<>(){});
}