package com.cim.multiblock.system;

import com.cim.multiblock.system.part.MultiblockPartBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public abstract class MultiblockBlockEntity extends BlockEntity {
    protected List<BlockPos> partPositions = new ArrayList<>();
    protected BlockPos controllerPos;
    protected BlockPos origin;
    protected boolean isFormed = false;
    protected boolean isDestroying = false;
    protected boolean isController = false;

    public MultiblockBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Создает мультиблок и устанавливает части
     */
    public void createMultiblock(Level level, BlockPos origin) {
        this.origin = origin;
        this.controllerPos = worldPosition;
        this.isController = true;
        this.isFormed = true;

        if (level.isClientSide) return;

        MultiblockPattern pattern = getPattern();
        if (pattern == null) return;

        for (int y = 0; y < pattern.getHeight(); y++) {
            for (int x = 0; x < pattern.getWidth(); x++) {
                for (int z = 0; z < pattern.getDepth(); z++) {
                    BlockPos partPos = origin.offset(x, y, z);

                    if (partPos.equals(worldPosition)) continue;

                    MultiblockPattern.PatternEntry entry = pattern.getEntry(x, y, z);
                    if (entry == MultiblockPattern.PatternEntry.EMPTY ||
                            entry == MultiblockPattern.PatternEntry.AIR) {
                        continue;
                    }

                    Block partBlock = getPartBlock();
                    if (partBlock != null) {
                        level.setBlock(partPos, partBlock.defaultBlockState(), 3);

                        BlockEntity be = level.getBlockEntity(partPos);
                        if (be instanceof MultiblockBlockEntity part) {
                            part.setAsPart(worldPosition, origin);
                            partPositions.add(partPos);
                        }
                    }
                }
            }
        }
        setChanged();
    }

    public void destroyMultiblock() {
        if (isDestroying || level == null) return;
        isDestroying = true;
        isFormed = false;

        for (BlockPos pos : partPositions) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof MultiblockPartBlock || state.getBlock() instanceof MultiblockBlock) {
                level.removeBlock(pos, false);
            }
        }

        onMultiblockBreak();

        if (isController) {
            level.removeBlock(worldPosition, false);
        }
    }

    /**
     * Вызывается когда часть мультиблока сломана игроком
     */
    public void onPartBroken(BlockPos partPos) {
        destroyMultiblock();
    }

    /**
     * Обработка использования части мультиблока (проксирует к контроллеру)
     */
    public InteractionResult onPartUse(Player player, InteractionHand hand, BlockHitResult hit, BlockPos partPos) {
        // По умолчанию проксируем к обычному use контроллера
        return onMultiblockUse(player, hand, hit);
    }

    /**
     * Обработка использования контроллера (должен быть переопределен в наследниках)
     */
    public abstract InteractionResult onMultiblockUse(Player player, InteractionHand hand, BlockHitResult hit);

    /**
     * Возвращает ItemStack контроллера (для дропа при разрушении части)
     */
    public ItemStack getControllerItem() {
        return new ItemStack(getBlockState().getBlock());
    }

    /**
     * Возвращает форму всего мультиблока относительно текущего блока (для обводки)
     */
    public VoxelShape getMultiblockShape(BlockPos currentPos) {
        if (!isFormed || origin == null) return Shapes.block();

        MultiblockPattern pattern = getPattern();
        if (pattern == null) return Shapes.block();

        int relX = currentPos.getX() - origin.getX();
        int relY = currentPos.getY() - origin.getY();
        int relZ = currentPos.getZ() - origin.getZ();

        int w = pattern.getWidth();
        int h = pattern.getHeight();
        int d = pattern.getDepth();

        return Shapes.box(-relX, -relY, -relZ, w - relX, h - relY, d - relZ);
    }

    /**
     * Alias для совместимости с MultiblockPartBlock
     */
    public VoxelShape getFullMultiblockShape(BlockPos currentPos) {
        return getMultiblockShape(currentPos);
    }

    protected void setAsPart(BlockPos controllerPos, BlockPos origin) {
        this.controllerPos = controllerPos;
        this.origin = origin;
        this.isController = false;
        this.isFormed = true;
        setChanged();
    }

    public boolean isController() {
        return isController;
    }

    public boolean isFormed() {
        return isFormed && !isDestroying;
    }

    public boolean isDestroying() {
        return isDestroying;
    }

    public BlockPos getControllerPos() {
        return isController ? worldPosition : controllerPos;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public List<BlockPos> getPartPositions() {
        return partPositions;
    }

    public abstract MultiblockPattern getPattern();
    protected abstract Block getPartBlock();
    protected abstract void onMultiblockBreak();
    public abstract void tickMultiblock();

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("IsFormed", isFormed);
        tag.putBoolean("IsController", isController);
        if (origin != null) tag.putLong("Origin", origin.asLong());
        if (controllerPos != null) tag.putLong("ControllerPos", controllerPos.asLong());

        long[] parts = new long[partPositions.size()];
        for (int i = 0; i < partPositions.size(); i++) {
            parts[i] = partPositions.get(i).asLong();
        }
        tag.putLongArray("Parts", parts);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        isFormed = tag.getBoolean("IsFormed");
        isController = tag.getBoolean("IsController");
        if (tag.contains("Origin")) origin = BlockPos.of(tag.getLong("Origin"));
        if (tag.contains("ControllerPos")) controllerPos = BlockPos.of(tag.getLong("ControllerPos"));

        partPositions.clear();
        long[] parts = tag.getLongArray("Parts");
        for (long l : parts) {
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