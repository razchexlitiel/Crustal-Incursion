package com.cim.block.basic.industrial.rotation;

import com.cim.api.rotation.KineticNetworkManager;
import com.cim.block.entity.ModBlockEntities;
import com.cim.block.entity.industrial.rotation.MotorElectroBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;
import net.minecraft.network.chat.Component;

public class MotorElectroBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public MotorElectroBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public net.minecraft.world.InteractionResult use(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (player.getItemInHand(hand).isEmpty()) {
            if (!level.isClientSide) {
                if (level.getBlockEntity(pos) instanceof MotorElectroBlockEntity motor) {
                    // 1. Меняем направление мотора
                    motor.toggleDirection();

                    // 2. Получаем текущую сеть
                    com.cim.api.rotation.KineticNetworkManager manager = com.cim.api.rotation.KineticNetworkManager.get((ServerLevel) level);
                    com.cim.api.rotation.KineticNetwork net = manager.getNetworkFor(pos);

                    if (net != null) {
                        // 3. Спрашиваем сеть: "Все ли моторы теперь согласны?"
                        if (net.checkConflict((ServerLevel) level)) {
                            // КОНФЛИКТ! Ищем блок, через который мотор подключен к сети
                            Direction motorFacing = state.getValue(FACING);
                            BlockPos adjacentPos = pos.relative(motorFacing);

                            // Убеждаемся, что перед нами кинетический блок
                            if (level.getBlockEntity(adjacentPos) instanceof com.cim.api.rotation.Rotational) {
                                // Ломаем связующее звено!
                                // true означает, что блок выпадет как предмет (дроп)
                                level.destroyBlock(adjacentPos, true);
                                player.displayClientMessage(Component.literal("Конфликт вращения! Вал не выдержал напряжения."), true);
                            }
                        } else {
                            // Если всё мирно, просто обновляем скорости для всех
                            net.recalculate((ServerLevel) level);
                            player.displayClientMessage(Component.literal("Направление изменено!"), true);
                        }
                    }
                }
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.use(state, level, pos, player, hand, hit);
    }



    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Мотор будет смотреть в сторону игрока при установке
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide) {
            // Регистрируем мотор в кинетической сети [cite: 14]
            KineticNetworkManager.get((ServerLevel) level).updateNetworkAfterPlace(pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && state.getBlock() != newState.getBlock()) {
            // 1. Сначала вызываем super, чтобы Майнкрафт гарантированно стёр BlockEntity из мира
            super.onRemove(state, level, pos, newState, isMoving);
            // 2. И только теперь пересобираем сеть — мертвый блок в неё уже не попадёт
            com.cim.api.rotation.KineticNetworkManager.get((ServerLevel) level).updateNetworkAfterRemove(pos);
            return;
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED; // Рендерим через Flywheel [cite: 13]
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MotorElectroBlockEntity(pos, state);
    }
}
