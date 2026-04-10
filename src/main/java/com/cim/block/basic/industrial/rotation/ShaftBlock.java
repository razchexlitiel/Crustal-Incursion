package com.cim.block.basic.industrial.rotation;

import com.cim.api.rotation.KineticNetworkManager;
import com.cim.api.rotation.ShaftDiameter;
import com.cim.api.rotation.ShaftMaterial;
import com.cim.block.entity.industrial.rotation.ShaftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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

public class ShaftBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    // НОВЫЕ ПЕРЕМЕННЫЕ
    private final ShaftMaterial material;
    private final ShaftDiameter diameter;

    // НОВЫЙ КОНСТРУКТОР НА 3 АРГУМЕНТА
    public ShaftBlock(Properties properties, ShaftMaterial material, ShaftDiameter diameter) {
        super(properties);
        this.material = material;
        this.diameter = diameter;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    // Геттеры для сущности (BlockEntity)
    public ShaftMaterial getMaterial() { return material; }
    public ShaftDiameter getDiameter() { return diameter; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // ДИНАМИЧЕСКИЙ ХИТБОКС (зависит от диаметра)
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        double p = diameter.pixels / 2.0;
        double min = 8.0 - p;
        double max = 8.0 + p;

        return switch (state.getValue(FACING).getAxis()) {
            case X -> Block.box(0.0, min, min, 16.0, max, max);
            case Y -> Block.box(min, 0.0, min, max, 16.0, max);
            case Z -> Block.box(min, min, 0.0, max, max, 16.0);
        };
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();

        // 1. Получаем блок, по которому мы кликнули (чтобы понять, к чему крепимся)
        BlockPos posAgainst = pos.relative(clickedFace.getOpposite());
        BlockState stateAgainst = level.getBlockState(posAgainst);

        // Изначально предполагаем направление по взгляду игрока
        Direction placementFacing = context.getNearestLookingDirection().getOpposite();

        // --- ЛОГИКА КЛИКА ПО ДРУГОМУ БЛОКУ ---
        if (stateAgainst.getBlock() instanceof ShaftBlock clickedShaft) {
            // Если мы пытаемся продолжить линию вала (кликаем в торец)
            if (stateAgainst.getValue(FACING).getAxis() == clickedFace.getAxis()) {

                // ГЛАВНАЯ ПРОВЕРКА: Если диаметры разные — отменяем установку!
                if (clickedShaft.getDiameter() != this.diameter) {
                    return null;
                }
                // Если диаметры совпали (материал не важен), выстраиваем в линию
                placementFacing = stateAgainst.getValue(FACING);

            } else {
                // Если кликнули сбоку по валу, логично сделать новый вал параллельным старому
                placementFacing = stateAgainst.getValue(FACING);
            }
        } else if (stateAgainst.getBlock() instanceof BearingBlock) {
            // Опционально: если ставим вал кликая по подшипнику, перенимаем его ось
            placementFacing = stateAgainst.getValue(BearingBlock.FACING);
        }


        // --- ЗАЩИТА ОТ ХИТРЕЦОВ ---
        // Игрок мог кликнуть по полу, чтобы поставить валы встык.
        // Проверяем соседей спереди и сзади по оси вращения будущего вала.
        for (Direction dir : new Direction[]{placementFacing, placementFacing.getOpposite()}) {
            BlockState neighborState = level.getBlockState(pos.relative(dir));

            if (neighborState.getBlock() instanceof ShaftBlock neighborShaft) {
                // Если соседний вал лежит на той же оси (будет соединен с нашим)
                if (neighborState.getValue(FACING).getAxis() == placementFacing.getAxis()) {

                    // Проверяем диаметр. Разные? Запрет установки!
                    if (neighborShaft.getDiameter() != this.diameter) {
                        return null;
                    }
                }
            }
        }

        // Если все проверки пройдены, ставим блок!
        return this.defaultBlockState().setValue(FACING, placementFacing);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShaftBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide) {
            KineticNetworkManager.get((ServerLevel) level).updateNetworkAfterPlace(pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && state.getBlock() != newState.getBlock()) {
            super.onRemove(state, level, pos, newState, isMoving);
            KineticNetworkManager.get((ServerLevel) level).updateNetworkAfterRemove(pos);
            return;
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}