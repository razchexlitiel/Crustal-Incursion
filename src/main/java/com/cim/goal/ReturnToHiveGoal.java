package com.cim.goal;

import com.cim.api.hive.HiveNetwork;
import com.cim.api.hive.HiveNetworkManager;
import com.cim.api.hive.HiveNetworkMember;
import com.cim.block.entity.hive.DepthWormNestBlockEntity;
import com.cim.entity.mobs.DepthWormEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

public class ReturnToHiveGoal extends Goal {
    private final DepthWormEntity worm;
    private BlockPos targetPos;
    private int nextSearchTick;

    public ReturnToHiveGoal(DepthWormEntity worm) {
        this.worm = worm;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = worm.getTarget();
        if (target != null && target.isAlive()) return false;

        if (worm.tickCount < nextSearchTick) return false;
        nextSearchTick = worm.tickCount + 10 + worm.getRandom().nextInt(10);

        // Проверяем привязку к гнезду
        BlockPos boundNest = worm.getBoundNestPos();
        if (boundNest != null && isValidNest(boundNest)) {
            this.targetPos = boundNest;
            return true;
        }

        // Ищем новое ближайшее гнездо
        BlockPos home = worm.getHomePos();
        if (home != null) {
            if (isValidNest(home)) {
                this.targetPos = home;
                worm.bindToNest(home); // Привязываем к найденному гнезду
                return true;
            }
            worm.setHomePos(null);
        }

        this.targetPos = findNearestEntry();
        if (this.targetPos != null) {
            worm.setHomePos(this.targetPos);
            worm.bindToNest(this.targetPos); // Привязываем
            return true;
        }
        return false;
    }

    private boolean isValidNest(BlockPos pos) {
        BlockEntity be = worm.level().getBlockEntity(pos);
        if (be instanceof DepthWormNestBlockEntity nest) {
            HiveNetworkManager manager = HiveNetworkManager.get(worm.level());
            return !nest.isFull() && nest.getNetworkId() != null;
        }
        return false;
    }

    private BlockPos findNearestEntry() {
        BlockPos wormPos = worm.blockPosition();
        HiveNetworkManager manager = HiveNetworkManager.get(worm.level());
        if (manager == null) return null;

        BlockPos bestNest = null;
        double bestDist = Double.MAX_VALUE;
        int radius = 16;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = wormPos.offset(x, y, z);
                    BlockEntity be = worm.level().getBlockEntity(p);

                    if (be instanceof DepthWormNestBlockEntity nest) {
                        if (!nest.isFull()) {
                            double d = worm.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                            if (d < bestDist) {
                                bestDist = d;
                                bestNest = p.immutable();
                            }
                        }
                    }
                }
            }
        }
        return bestNest;
    }

    @Override
    public void tick() {
        if (targetPos == null) return;

        double targetX = targetPos.getX() + 0.5;
        double targetY = targetPos.getY() + 0.8;
        double targetZ = targetPos.getZ() + 0.5;

        double distSq = worm.distanceToSqr(targetX, targetY, targetZ);

        if (distSq < 4.0D) {
            worm.getNavigation().stop();
            Vec3 pull = new Vec3(targetX - worm.getX(), targetY - worm.getY(), targetZ - worm.getZ())
                    .normalize()
                    .scale(0.15);
            worm.setDeltaMovement(worm.getDeltaMovement().add(pull));
            worm.getLookControl().setLookAt(targetX, targetY, targetZ, 30.0F, 30.0F);
        } else {
            worm.getNavigation().moveTo(targetX, targetY, targetZ, 1.2D);
            worm.getLookControl().setLookAt(targetX, targetY + 0.5, targetZ);
        }

        if (distSq < 1.5D) {
            HiveNetworkManager manager = HiveNetworkManager.get(worm.level());
            if (manager == null) return;

            BlockEntity be = worm.level().getBlockEntity(targetPos);
            if (be instanceof DepthWormNestBlockEntity nest) {
                UUID netId = nest.getNetworkId();
                if (netId != null) {
                    HiveNetwork network = manager.getNetwork(netId);
                    if (network != null) {
                        int kills = worm.getKills();
                        network.killsPool = Math.min(50, network.killsPool + kills);

                        System.out.println("[Hive] Worm entered nest. Network points: " + network.killsPool);

                        CompoundTag tag = new CompoundTag();
                        worm.saveWithoutId(tag);
                        tag.putInt("Kills", 0);

                        // Сохраняем привязку к гнезду в NBT червя
                        tag.putLong("BoundNest", targetPos.asLong());

                        if (manager.addWormToNetwork(netId, tag, targetPos, worm.level())) {
                            worm.discard();
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean canContinueToUse() {
        return targetPos != null && isValidNest(targetPos) && worm.getTarget() == null;
    }

    @Override
    public void stop() {
        this.targetPos = null;
        worm.getNavigation().stop();
    }
}