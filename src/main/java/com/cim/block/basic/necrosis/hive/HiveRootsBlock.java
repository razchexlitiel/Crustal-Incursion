package com.cim.block.basic.necrosis.hive;

import com.cim.block.basic.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

public class HiveRootsBlock extends Block implements BonemealableBlock {
    // AGE = 0, 1, 2... максимальная высота сегмента (0 = нижний/верхний конец)
    public static final IntegerProperty AGE = BlockStateProperties.AGE_3; // 0-3, можно заменить на AGE_25 для большей высоты
    // UP = true если есть продолжение сверху
    public static final BooleanProperty UP = BlockStateProperties.UP;
    // DOWN = true если есть продолжение снизу
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    // HANGING = true если растёт сверху вниз (как лианы)
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");

    public HiveRootsBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AGE, 0)
                .setValue(UP, false)
                .setValue(DOWN, false)
                .setValue(HANGING, false));
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        boolean hanging = state.getValue(HANGING);

        if (hanging) {
            // Висячие корни: должны быть прикреплены к блоку СВЕРХУ или к другому висячему корню
            BlockPos abovePos = pos.above();
            BlockState above = level.getBlockState(abovePos);

            // Либо к опорному блоку, либо к другому висячему корню с DOWN=true
            return above.is(ModBlocks.HIVE_SOIL.get())
                    || above.is(ModBlocks.DEPTH_WORM_NEST.get())
                    || (above.is(this) && above.getValue(HANGING) && above.getValue(DOWN));
        } else {
            // Растущие вверх корни: должны стоять на блоке СНИЗУ или на другом корне
            BlockPos belowPos = pos.below();
            BlockState below = level.getBlockState(belowPos);

            return below.is(ModBlocks.HIVE_SOIL.get())
                    || below.is(ModBlocks.DEPTH_WORM_NEST.get())
                    || (below.is(this) && !below.getValue(HANGING) && below.getValue(UP));
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();

        // Определяем, висячие это корни или растущие вверх
        // Если кликаем по потолку (face == DOWN) или снизу в блок — висячие
        boolean hanging = false;
        BlockPos supportPos;

        if (face == Direction.DOWN) {
            // Кликнули по дну блока сверху — висячие корни
            hanging = true;
            supportPos = pos.above();
        } else if (face == Direction.UP) {
            // Кликнули по верху блока снизу — обычные корни
            hanging = false;
            supportPos = pos.below();
        } else {
            // Кликнули сбоку — определяем по ближайшему опорному блоку
            BlockState above = level.getBlockState(pos.above());
            BlockState below = level.getBlockState(pos.below());

            if (above.is(ModBlocks.HIVE_SOIL.get()) || above.is(ModBlocks.DEPTH_WORM_NEST.get())) {
                hanging = true;
                supportPos = pos.above();
            } else {
                hanging = false;
                supportPos = pos.below();
            }
        }

        // Проверяем валидность опоры
        BlockState support = level.getBlockState(supportPos);
        boolean validSupport = support.is(ModBlocks.HIVE_SOIL.get())
                || support.is(ModBlocks.DEPTH_WORM_NEST.get())
                || support.is(this);

        if (!validSupport) {
            return null;
        }

        return this.defaultBlockState().setValue(HANGING, hanging);
    }

    // Добавление нового сегмента при клике
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);

        if (!stack.is(this.asItem())) {
            return InteractionResult.PASS;
        }

        boolean hanging = state.getValue(HANGING);
        Direction growDir = hanging ? Direction.DOWN : Direction.UP;
        BlockPos newPos = pos.relative(growDir);

        // Проверяем, можно ли расти дальше
        if (!level.getBlockState(newPos).isAir()) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide) {
            // Ставим новый блок
            BlockState newState = this.defaultBlockState()
                    .setValue(HANGING, hanging)
                    .setValue(AGE, Math.min(state.getValue(AGE) + 1, 3));

            level.setBlock(newPos, newState, 3);

            // Обновляем текущий блок — теперь у него есть продолжение
            level.setBlock(pos, state.setValue(hanging ? DOWN : UP, true), 3);

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // Случайный тик для роста (как лианы)
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(5) != 0) return; // 20% шанс

        boolean hanging = state.getValue(HANGING);
        Direction growDir = hanging ? Direction.DOWN : Direction.UP;
        BlockPos growPos = pos.relative(growDir);

        // Растём только если воздух и не слишком длинно
        if (level.getBlockState(growPos).isAir() && state.getValue(AGE) < 3) {
            // Проверяем длину цепочки
            int length = getLength(level, pos, hanging);
            if (length < 15) { // Максимальная длина 15 блоков
                BlockState newState = this.defaultBlockState()
                        .setValue(HANGING, hanging)
                        .setValue(AGE, state.getValue(AGE) + 1);

                level.setBlock(growPos, newState, 3);
                level.setBlock(pos, state.setValue(hanging ? DOWN : UP, true), 3);
            }
        }
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, boolean isClient) {
        boolean hanging = state.getValue(HANGING);
        Direction growDir = hanging ? Direction.DOWN : Direction.UP;
        BlockPos growPos = pos.relative(growDir);
        return level.getBlockState(growPos).isAir() && getLength(level, pos, hanging) < 15;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        boolean hanging = state.getValue(HANGING);
        Direction growDir = hanging ? Direction.DOWN : Direction.UP;
        BlockPos growPos = pos.relative(growDir);

        if (level.getBlockState(growPos).isAir()) {
            BlockState newState = this.defaultBlockState()
                    .setValue(HANGING, hanging)
                    .setValue(AGE, Math.min(state.getValue(AGE) + 1, 3));

            level.setBlock(growPos, newState, 3);
            level.setBlock(pos, state.setValue(hanging ? DOWN : UP, true), 3);
        }
    }

    // Подсчёт длины цепочки корней
    private int getLength(LevelReader level, BlockPos pos, boolean hanging) {
        int length = 1;
        Direction dir = hanging ? Direction.DOWN : Direction.UP;
        BlockPos checkPos = pos.relative(dir);

        while (level.getBlockState(checkPos).is(this)) {
            length++;
            checkPos = checkPos.relative(dir);
            if (length > 20) break; // Защита от бесконечного цикла
        }

        return length;
    }

    // Разрушение цепочки
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            boolean hanging = state.getValue(HANGING);
            Direction breakDir = hanging ? Direction.DOWN : Direction.UP;

            // Ломаем всю цепочку ниже/выше
            BlockPos breakPos = pos.relative(breakDir);
            while (level.getBlockState(breakPos).is(this)) {
                BlockState breakState = level.getBlockState(breakPos);
                if (breakState.getValue(HANGING) == hanging) {
                    level.destroyBlock(breakPos, true);
                    breakPos = breakPos.relative(breakDir);
                } else {
                    break;
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {

        boolean hanging = state.getValue(HANGING);

        // Проверяем опору
        Direction supportDir = hanging ? Direction.UP : Direction.DOWN;
        if (direction == supportDir) {
            if (!canSurvive(state, level, pos)) {
                return Blocks.AIR.defaultBlockState();
            }
        }

        // Обновляем состояние UP/DOWN на основе соседей
        if (direction == Direction.UP && !hanging) {
            boolean hasUp = neighborState.is(this) && !neighborState.getValue(HANGING);
            if (state.getValue(UP) != hasUp) {
                return state.setValue(UP, hasUp);
            }
        }
        if (direction == Direction.DOWN && !hanging) {
            boolean hasDown = neighborState.is(this) && !neighborState.getValue(HANGING);
            if (state.getValue(DOWN) != hasDown) {
                return state.setValue(DOWN, hasDown);
            }
        }
        if (direction == Direction.UP && hanging) {
            boolean hasUp = neighborState.is(this) && neighborState.getValue(HANGING);
            if (state.getValue(UP) != hasUp) {
                return state.setValue(UP, hasUp);
            }
        }
        if (direction == Direction.DOWN && hanging) {
            boolean hasDown = neighborState.is(this) && neighborState.getValue(HANGING);
            if (state.getValue(DOWN) != hasDown) {
                return state.setValue(DOWN, hasDown);
            }
        }

        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE, UP, DOWN, HANGING);
    }
}