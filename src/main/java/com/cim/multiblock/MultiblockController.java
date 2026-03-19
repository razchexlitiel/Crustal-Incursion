package com.cim.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;

public abstract class MultiblockController {
    protected final Level level;
    protected final BlockPos origin;
    protected final MultiblockPattern pattern;
    protected final Set<BlockPos> componentPositions = new HashSet<>();
    protected boolean isValid = false;

    public MultiblockController(Level level, BlockPos origin, MultiblockPattern pattern) {
        this.level = level;
        this.origin = origin;
        this.pattern = pattern;
        calculateComponentPositions();
    }

    private void calculateComponentPositions() {
        componentPositions.clear();
        for (int y = 0; y < pattern.getHeight(); y++) {
            for (int x = 0; x < pattern.getWidth(); x++) {
                for (int z = 0; z < pattern.getDepth(); z++) {
                    MultiblockPattern.PatternEntry entry = pattern.getEntry(x, y, z);
                    if (entry != MultiblockPattern.PatternEntry.EMPTY &&
                            entry != MultiblockPattern.PatternEntry.AIR) {
                        componentPositions.add(origin.offset(x, y, z));
                    }
                }
            }
        }
    }

    public boolean validate() {
        isValid = pattern.matches(level, origin);
        return isValid;
    }

    public boolean isPartOfMultiblock(BlockPos pos) {
        return componentPositions.contains(pos);
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public MultiblockPattern getPattern() {
        return pattern;
    }

    public boolean isValid() {
        return isValid;
    }

    public abstract void tick();
    public abstract InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit, BlockPos clickedPos);
    public abstract void onBreak();
    public abstract CompoundTag save(CompoundTag tag);
    public abstract void load(CompoundTag tag);

    protected void notifyPlayers(String message) {
        for (Player player : level.players()) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.distanceToSqr(origin.getCenter()) < 256) {
                    serverPlayer.sendSystemMessage(Component.literal(message));
                }
            }
        }
    }
}