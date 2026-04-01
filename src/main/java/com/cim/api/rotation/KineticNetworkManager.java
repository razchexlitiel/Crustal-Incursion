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
                    if (net != null) neighborNetworks.add(net);
                }
            }
        }

        // Logical decision making
        if (neighborNetworks.isEmpty()) {
            KineticNetwork newNet = new KineticNetwork();
            registerBlockToNetwork(pos, newNet);
            LOGGER.info("[Kinetic] Created new network {} for {}", newNet.getId().toString().substring(0, 8), pos.toShortString());
            newNet.recalculate(level);
        } else if (neighborNetworks.size() == 1) {
            KineticNetwork existingNet = neighborNetworks.iterator().next();
            registerBlockToNetwork(pos, existingNet);
            LOGGER.info("[Kinetic] Block joined network {}", existingNet.getId().toString().substring(0, 8));
            existingNet.recalculate(level);
        } else {
            LOGGER.info("[Kinetic] Merging {} networks at {}", neighborNetworks.size(), pos.toShortString());
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

    private void createNewNetworkFrom(BlockPos start) {
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
                    BlockEntity neighborBE = level.getBlockEntity(neighborPos);

                    if (neighborBE instanceof Rotational neighborNode) {
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
        LOGGER.info("[Kinetic] New segment created {}: {} blocks", newNet.getId().toString().substring(0, 8), newNet.getMembers().size());
    }

    private void mergeNetworks(Set<KineticNetwork> networks, BlockPos connectorPos) {
        KineticNetwork mainNet = networks.iterator().next();
        String mainId = mainNet.getId().toString().substring(0, 8);
        networks.remove(mainNet);

        for (KineticNetwork otherNet : networks) {
            String otherId = otherNet.getId().toString().substring(0, 8);
            int movedBlocks = otherNet.getMembers().size();

            for (BlockPos memberPos : otherNet.getMembers()) {
                blockToNetwork.put(memberPos, mainNet);
                mainNet.addMember(memberPos);
            }
            LOGGER.info("[Kinetic] Network {} absorbed network {} (moved {} blocks)", mainId, otherId, movedBlocks);
        }

        registerBlockToNetwork(connectorPos, mainNet);
        mainNet.recalculate(level);
        LOGGER.info("[Kinetic] Merge complete. Final network {}: {} members", mainId, mainNet.getMembers().size());
    }

    private void registerBlockToNetwork(BlockPos pos, KineticNetwork net) {
        blockToNetwork.put(pos, net);
        net.addMember(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Rotational rot && rot.isSource()) {
            net.addGenerator(pos);
        }
    }
}