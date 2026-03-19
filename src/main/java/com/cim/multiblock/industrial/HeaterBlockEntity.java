package com.cim.multiblock.industrial;


import com.cim.block.basic.ModBlocks;
import com.cim.block.entity.ModBlockEntities;
import com.cim.multiblock.MultiblockPattern;
import com.cim.multiblock.part.IMultiblockController;
import com.cim.multiblock.part.MultiblockPartBlock;
import com.cim.multiblock.part.MultiblockPartEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class HeaterBlockEntity extends BlockEntity implements IMultiblockController, BlockEntityTicker<HeaterBlockEntity> {
    private HeaterMultiblock controller;
    private List<BlockPos> partPositions = new ArrayList<>();
    private boolean isDestroying = false;

    public HeaterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEATER_BE.get(), pos, state);
    }

    public void createMultiblock(Level level, BlockPos origin) {
        this.controller = new HeaterMultiblock(level, origin);
        MultiblockPattern pattern = controller.getPattern();

        for (int y = 0; y < pattern.getHeight(); y++) {
            for (int x = 0; x < pattern.getWidth(); x++) {
                for (int z = 0; z < pattern.getDepth(); z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // сам контроллер
                    BlockPos partPos = origin.offset(x, y, z);
                    MultiblockPattern.PatternEntry entry = pattern.getEntry(x, y, z);
                    if (entry == MultiblockPattern.PatternEntry.EMPTY || entry == MultiblockPattern.PatternEntry.AIR) {
                        continue;
                    }
                    level.setBlock(partPos, ModBlocks.MULTIBLOCK_PART.get().defaultBlockState(), 3);
                    BlockEntity be = level.getBlockEntity(partPos);
                    if (be instanceof MultiblockPartEntity part) {
                        part.setControllerPos(origin);
                    }
                    partPositions.add(partPos);
                }
            }
        }
        controller.validate();
        setChanged();
    }

    @Override
    public void destroyMultiblock() {
        if (isDestroying || level == null) return;
        isDestroying = true;

        for (BlockPos pos : partPositions) {
            if (level.getBlockState(pos).getBlock() instanceof MultiblockPartBlock) {
                level.removeBlock(pos, false);
            }
        }
        level.removeBlock(worldPosition, false);

        if (controller != null) {
            controller.onBreak();
        }
    }

    public boolean isDestroying() {
        return isDestroying;
    }

    @Override
    public InteractionResult onUse(Player player, InteractionHand hand, BlockHitResult hit, BlockPos clickedPos) {
        if (controller != null) {
            return controller.onUse(player, hand, hit, clickedPos);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void tick(Level level, BlockPos pos, BlockState state, HeaterBlockEntity be) {
        if (controller != null && !isDestroying) {
            controller.tick();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (controller != null) {
            CompoundTag controllerTag = new CompoundTag();
            controller.save(controllerTag);
            tag.put("Controller", controllerTag);
        }
        tag.putLongArray("Parts", partPositions.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Controller") && level != null) {
            controller = new HeaterMultiblock(level, worldPosition);
            controller.load(tag.getCompound("Controller"));
        }
        partPositions.clear();
        for (long l : tag.getLongArray("Parts")) {
            partPositions.add(BlockPos.of(l));
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}