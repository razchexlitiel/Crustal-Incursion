package com.cim.api.rotation;

import com.cim.api.rotation.KineticNetwork;
import com.cim.api.rotation.Rotational;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static com.cim.main.CrustalIncursionMod.LOGGER;

public class KineticNetworkManager {
    private final Map<BlockPos, KineticNetwork> blockToNetwork = new HashMap<>();
    private final ServerLevel level;

    public KineticNetworkManager(ServerLevel level) {
        this.level = level;
    }

    private static final java.util.WeakHashMap<ServerLevel, KineticNetworkManager> INSTANCES = new java.util.WeakHashMap<>();

    public static KineticNetworkManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, KineticNetworkManager::new);
    }

    public void updateNetworkAfterPlace(BlockPos pos) {
        LOGGER.info("[Kinetic] Block placed at {}", pos.toShortString());

        BlockEntity be = level.getBlockEntity(pos);
        // 'node' is already defined here by the pattern matching
        if (!(be instanceof Rotational node)) {
            LOGGER.warn("[Kinetic] Block at {} does not support rotation!", pos.toShortString());
            return;
        }

        Set<KineticNetwork> neighborNetworks = new HashSet<>();

        for (Direction dir : node.getPropagationDirections()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);

            if (neighborBE instanceof Rotational neighborNode) {
                Direction sideFromNeighbor = dir.getOpposite();
                boolean neighborCanConnect = false;

                for (Direction neighborDir : neighborNode.getPropagationDirections()) {
                    if (neighborDir == sideFromNeighbor) {
                        neighborCanConnect = true;
                        break;
                    }
                }

                if (neighborCanConnect) {
                    KineticNetwork net = blockToNetwork.get(neighborPos);
                    // МАГИЯ ТУТ: Если соседа нет в памяти менеджера (после перезахода),
                    // заставляем его "проснуться" и собрать свою сеть
                    if (net == null) {
                        net = createNewNetworkFrom(neighborPos);
                    }
                    if (net != null) neighborNetworks.add(net);
                }
            }
        }

        if (neighborNetworks.size() > 1) {
            long firstActiveSpeed = 0;
            boolean conflict = false;

            for (KineticNetwork net : neighborNetworks) {
                long netSpeed = net.getSpeed();
                if (netSpeed != 0) {
                    if (firstActiveSpeed == 0) {
                        firstActiveSpeed = netSpeed;
                    } else if (firstActiveSpeed != netSpeed) {
                        // Если скорости двух сетей разные (напр. 20 и -20) — КОНФЛИКТ!
                        conflict = true;
                        break;
                    }
                }
            }

            if (conflict) {
                LOGGER.info("[Kinetic] Rotational conflict at {}! Breaking shaft.", pos.toShortString());
                level.destroyBlock(pos, true); // Ломаем блок и выкидываем его как предмет
                return; // Прекращаем выполнение, сети не объединятся
            }
        }

        // --- ДАЛЬШЕ СТАНДАРТНАЯ ЛОГИКА СЛИЯНИЯ ---
        if (neighborNetworks.isEmpty()) {
            KineticNetwork newNet = new KineticNetwork();
            registerBlockToNetwork(pos, newNet);
            newNet.recalculate(level);
        } else if (neighborNetworks.size() == 1) {
            KineticNetwork existingNet = neighborNetworks.iterator().next();
            registerBlockToNetwork(pos, existingNet);
            existingNet.recalculate(level);
        } else {
            mergeNetworks(neighborNetworks, pos);
        }
    }

    public void updateNetworkAfterRemove(BlockPos pos) {
        KineticNetwork oldNet = blockToNetwork.remove(pos);
        if (oldNet == null) return;

        LOGGER.info("[Kinetic] Block removed at {}. Breaking network {}", pos.toShortString(), oldNet.getId().toString().substring(0, 8));

        Set<BlockPos> membersToRebuild = new HashSet<>(oldNet.getMembers());
        membersToRebuild.remove(pos);

        for (BlockPos memberPos : membersToRebuild) {
            blockToNetwork.remove(memberPos);
            if (level.getBlockEntity(memberPos) instanceof Rotational rot) {
                rot.setSpeed(0);
            }
        }

        LOGGER.info("[Kinetic] Network {} dissolved. Rebuilding {} blocks...", oldNet.getId().toString().substring(0, 8), membersToRebuild.size());

        for (BlockPos startPos : membersToRebuild) {
            if (!blockToNetwork.containsKey(startPos)) {
                createNewNetworkFrom(startPos);
            }
        }
    }

    private KineticNetwork createNewNetworkFrom(BlockPos start) {
        KineticNetwork newNet = new KineticNetwork();
        java.util.Queue<BlockPos> queue = new java.util.LinkedList<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (blockToNetwork.containsKey(current)) continue;

            if (level.getBlockEntity(current) instanceof Rotational node) {
                registerBlockToNetwork(current, newNet);

                for (Direction dir : node.getPropagationDirections()) {
                    BlockPos neighborPos = current.relative(dir);
                    if (level.getBlockEntity(neighborPos) instanceof Rotational neighborNode) {
                        for (Direction neighborDir : neighborNode.getPropagationDirections()) {
                            if (neighborDir == dir.getOpposite()) {
                                queue.add(neighborPos);
                                break;
                            }
                        }
                    }
                }
            }
        }
        newNet.recalculate(level);
        return newNet; // Теперь возвращаем сеть
    }

    private void mergeNetworks(Set<KineticNetwork> networks, BlockPos connectorPos) {
        KineticNetwork mainNet = networks.iterator().next();
        networks.remove(mainNet);

        for (KineticNetwork otherNet : networks) {
            // ФИКС БАГА: Переносим и обычные блоки, и ГЕНЕРАТОРЫ (моторы)
            for (BlockPos memberPos : otherNet.getMembers()) {
                blockToNetwork.put(memberPos, mainNet);
                mainNet.addMember(memberPos);
            }

            for (BlockPos genPos : otherNet.getGenerators()) {
                mainNet.addGenerator(genPos); // Теперь моторы не потеряются!
            }
        }

        registerBlockToNetwork(connectorPos, mainNet);
        mainNet.recalculate(level);
    }

    private void registerBlockToNetwork(BlockPos pos, KineticNetwork net) {
        blockToNetwork.put(pos, net);
        net.addMember(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Rotational rot && rot.isSource()) {
            net.addGenerator(pos);
        }
    }

    public KineticNetwork getNetworkFor(BlockPos pos) {
        return blockToNetwork.get(pos);
    }
}