package com.cim.multiblock.system.part;

import com.cim.multiblock.system.MultiblockBlockEntity;
import com.cim.multiblock.system.part.MultiblockPartEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MultiblockPartBlock extends BaseEntityBlock {

    public MultiblockPartBlock(Properties properties) {
        super(properties.strength(1.0f, 6.0f).noOcclusion());
    }

    /**
     * Возвращает общую форму мультиблока для обводки при наведении
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MultiblockBlockEntity part) {
            BlockPos controllerPos = part.getControllerPos();
            if (controllerPos != null) {
                BlockEntity controllerBe = level.getBlockEntity(controllerPos);
                if (controllerBe instanceof MultiblockBlockEntity controller) {
                    return controller.getFullMultiblockShape(pos);
                }
            }
        }
        return Shapes.block();
    }
    @Override
    public void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
        // Частицы только если игрок ломает конкретно эту часть (player != null)
        // Если разрушение каскадное из контроллера - player будет null, частиц не будет
        if (player != null && level.isClientSide) {
            for (int i = 0; i < 4; ++i) {
                double x = (double) pos.getX() + level.random.nextDouble();
                double y = (double) pos.getY() + level.random.nextDouble();
                double z = (double) pos.getZ() + level.random.nextDouble();
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 0.0D, 0.0D, 0.0D);
            }
        }
    }
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE; // Части невидимы (рендерятся как часть контроллера)
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(); // Дроп обрабатывается в playerWillDestroy
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    /**
     * При разрушении части дропаем предмет контроллера и разрушаем весь мультиблок
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MultiblockBlockEntity part) {
                BlockPos controllerPos = part.getControllerPos();
                if (controllerPos != null) {
                    BlockEntity controllerBe = level.getBlockEntity(controllerPos);
                    if (controllerBe instanceof MultiblockBlockEntity controller && !controller.isDestroying()) {
                        // Дропаем предмет контроллера (например, Heater)
                        ItemStack controllerItem = controller.getControllerItem();
                        if (!controllerItem.isEmpty()) {
                            Block.popResource(level, pos, controllerItem);
                        }
                        // Разрушаем весь мультиблок
                        controller.onPartBroken(pos);
                        return;
                    }
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * Проксируем использование к контроллеру
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MultiblockBlockEntity part) {
            BlockPos controllerPos = part.getControllerPos();
            if (controllerPos != null) {
                BlockEntity controllerBe = level.getBlockEntity(controllerPos);
                if (controllerBe instanceof MultiblockBlockEntity controller) {
                    return controller.onPartUse(player, hand, hit, pos);
                }
            }
        }
        return InteractionResult.PASS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MultiblockPartEntity(pos, state);
    }
}