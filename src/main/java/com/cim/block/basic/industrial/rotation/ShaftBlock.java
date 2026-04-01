package com.cim.block.basic.industrial.rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import com.cim.block.entity.industrial.rotation.ShaftBlockEntity;

public class ShaftBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    // --- НАШИ НОВЫЕ ХИТБОКСЫ (4 ПИКСЕЛЯ) ---
    // Ось Y (Вертикально: Вверх/Вниз)
    private static final VoxelShape SHAPE_Y = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);
    // Ось Z (Горизонтально: Север/Юг)
    private static final VoxelShape SHAPE_Z = Block.box(6.0, 6.0, 0.0, 10.0, 10.0, 16.0);
    // Ось X (Горизонтально: Восток/Запад)
    private static final VoxelShape SHAPE_X = Block.box(0.0, 6.0, 6.0, 16.0, 10.0, 10.0);

    public ShaftBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // --- ЛОГИКА КОЛЛИЗИИ И ВЫДЕЛЕНИЯ ---
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Получаем ось из текущего направления и отдаем нужный хитбокс
        return switch (state.getValue(FACING).getAxis()) {
            case X -> SHAPE_X;
            case Y -> SHAPE_Y;
            case Z -> SHAPE_Z;
        };
    }

    // --- УМНАЯ УСТАНОВКА БЛОКА ---
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Получаем блок, по грани которого кликнул игрок
        BlockPos clickedPos = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState clickedState = context.getLevel().getBlockState(clickedPos);

        // Если мы ставим вал, кликая по другому валу — копируем его направление
        if (clickedState.getBlock() instanceof ShaftBlock) {
            return this.defaultBlockState().setValue(FACING, clickedState.getValue(FACING));
        }

        // Если ставим на обычный блок (камень, земля), вал будет смотреть в сторону клика
        // Например: кликнули по верхней грани (UP) -> вал смотрит вверх
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShaftBlockEntity(pos, state); // Убедись, что импорт правильный
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Обязательно оставляем этот параметр, чтобы избежать "крестов" и рендерить только Flywheel
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }
}