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

        // Получаем позицию блока, по которому кликнул игрок
        BlockPos posAgainst = pos.relative(clickedFace.getOpposite());
        BlockState stateAgainst = level.getBlockState(posAgainst);
        BlockEntity beAgainst = level.getBlockEntity(posAgainst);

        // 1. ЖЕСТКИЙ ЗАПРЕТ: Разрешаем ставить только на кинетические блоки!
        // Поскольку все наши механизмы (мотор, подшипник, вал) имеют BlockEntity
        // с интерфейсом Rotational, эта проверка отсекает вообще всё лишнее (землю, камень, воздух).
        if (!(beAgainst instanceof com.cim.api.rotation.Rotational)) {
            return null; // Отменяем установку
        }

        // Изначально предполагаем направление по взгляду игрока
        Direction placementFacing = context.getNearestLookingDirection().getOpposite();

        // --- ЛОГИКА ВЫРАВНИВАНИЯ И ПРОВЕРКИ ДИАМЕТРОВ ---
        if (stateAgainst.getBlock() instanceof ShaftBlock clickedShaft) {
            // Если мы пытаемся продолжить линию вала (кликаем в торец)
            if (stateAgainst.getValue(FACING).getAxis() == clickedFace.getAxis()) {
                if (clickedShaft.getDiameter() != this.diameter) {
                    return null; // Разные диаметры соединять нельзя
                }
                placementFacing = stateAgainst.getValue(FACING);
            } else {
                // Если кликнули сбоку по валу, делаем новый вал параллельным
                placementFacing = stateAgainst.getValue(FACING);
            }
        } else if (stateAgainst.getBlock() instanceof BearingBlock) {
            // Перенимаем ось подшипника
            placementFacing = stateAgainst.getValue(BearingBlock.FACING);
        } else if (stateAgainst.getBlock() instanceof MotorElectroBlock) {
            // Перенимаем ось мотора
            placementFacing = stateAgainst.getValue(MotorElectroBlock.FACING);
        }

        // --- ЗАЩИТА ОТ СОСЕДЕЙ ---
        // Проверяем, не упрется ли новый вал в вал другого диаметра с другой стороны
        for (Direction dir : new Direction[]{placementFacing, placementFacing.getOpposite()}) {
            BlockState neighborState = level.getBlockState(pos.relative(dir));

            if (neighborState.getBlock() instanceof ShaftBlock neighborShaft) {
                if (neighborState.getValue(FACING).getAxis() == placementFacing.getAxis()) {
                    if (neighborShaft.getDiameter() != this.diameter) {
                        return null; // С другой стороны стоит вал другого размера!
                    }
                }
            }
        }

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