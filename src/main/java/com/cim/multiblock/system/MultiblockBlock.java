package com.cim.multiblock.system;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class MultiblockBlock extends BaseEntityBlock {
    protected final MultiblockPattern pattern;

    public MultiblockBlock(Properties properties, MultiblockPattern pattern) {
        super(properties.strength(1.0f, 6.0f));
        this.pattern = pattern;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MultiblockBlockEntity mbe && mbe.isFormed()) {
            return mbe.getMultiblockShape(pos);
        }
        return Shapes.block();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of();
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MultiblockBlockEntity mbe) {
                if (isControllerPosition(pos)) {
                    if (!canPlaceMultiblock(level, pos)) {
                        level.removeBlock(pos, false);
                        if (placer instanceof Player player) {
                            player.addItem(new ItemStack(this.asItem()));
                        }
                        return;
                    }

                    BlockPos origin = getOriginFromController(pos);
                    mbe.createMultiblock(level, origin);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MultiblockBlockEntity mbe) {
                if (!mbe.isDestroying()) {
                    Block.popResource(level, pos, new ItemStack(this.asItem()));
                }

                if (mbe.isFormed()) {
                    BlockPos controllerPos = mbe.isController() ? pos : mbe.getControllerPos();
                    if (controllerPos != null) {
                        BlockEntity controllerBe = level.getBlockEntity(controllerPos);
                        if (controllerBe instanceof MultiblockBlockEntity controller && !controller.isDestroying()) {
                            controller.destroyMultiblock();
                        }
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MultiblockBlockEntity mbe && mbe.isFormed()) {
            BlockPos controllerPos = mbe.isController() ? pos : mbe.getControllerPos();
            if (controllerPos != null) {
                BlockEntity controllerBe = level.getBlockEntity(controllerPos);
                if (controllerBe instanceof MultiblockBlockEntity controller) {
                    return onMultiblockUse(controller, player, hand, hit);
                }
            }
        }
        return InteractionResult.PASS;
    }

    protected abstract InteractionResult onMultiblockUse(MultiblockBlockEntity controller, Player player,
                                                         InteractionHand hand, BlockHitResult hit);

    protected boolean canPlaceMultiblock(Level level, BlockPos controllerPos) {
        BlockPos origin = getOriginFromController(controllerPos);

        for (int y = 0; y < pattern.getHeight(); y++) {
            for (int x = 0; x < pattern.getWidth(); x++) {
                for (int z = 0; z < pattern.getDepth(); z++) {
                    BlockPos checkPos = origin.offset(x, y, z);
                    if (checkPos.equals(controllerPos)) continue;

                    BlockState state = level.getBlockState(checkPos);
                    if (!state.isAir() && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public BlockPos getOriginFromController(BlockPos controllerPos) {
        for (int y = 0; y < pattern.getHeight(); y++) {
            for (int x = 0; x < pattern.getWidth(); x++) {
                for (int z = 0; z < pattern.getDepth(); z++) {
                    if (pattern.pattern[y][x][z] == MultiblockPattern.PatternEntry.CONTROLLER) {
                        return controllerPos.offset(-x, -y, -z);
                    }
                }
            }
        }
        int centerX = pattern.getWidth() / 2;
        int centerZ = pattern.getDepth() / 2;
        return controllerPos.offset(-centerX, 0, -centerZ);
    }

    @Override
    public void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
        // Спавним частицы только если это прямое разрушение игроком (player != null)
        // При каскадном разрушении (из destroyMultiblock) player будет null
        if (player != null && level.isClientSide) {
            for (int i = 0; i < 4; ++i) {
                double x = (double) pos.getX() + level.random.nextDouble();
                double y = (double) pos.getY() + level.random.nextDouble();
                double z = (double) pos.getZ() + level.random.nextDouble();
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 0.0D, 0.0D, 0.0D);
            }
        }
    }
    protected boolean isControllerPosition(BlockPos pos) {
        return true;
    }

    public MultiblockPattern getPattern() {
        return pattern;
    }

    @Nullable
    @Override
    public abstract BlockEntity newBlockEntity(BlockPos pos, BlockState state);

    // ИСПРАВЛЕНИЕ: Убран свой createTickerHelper, используем унаследованный из BaseEntityBlock
    // Он уже там есть с правильной сигнатурой:
    // createTickerHelper(BlockEntityType<A>, BlockEntityType<E>, BlockEntityTicker<? super E>)
}