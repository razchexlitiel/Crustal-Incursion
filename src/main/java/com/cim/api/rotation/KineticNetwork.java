package com.cim.api.rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.cim.api.rotation.Rotational; // Твой интерфейс для получения Speed/Torque

import static com.cim.main.CrustalIncursionMod.LOGGER;

public class KineticNetwork {
    private final UUID networkId;

    // Все участники сети (валы, шестерни и т.д.)
    private final Set<BlockPos> members = new HashSet<>();
    // Только источники энергии (моторы, ветряки)
    private final Set<BlockPos> generators = new HashSet<>();

    private long currentSpeed = 0;
    private long totalGeneratedTorque = 0;
    private long totalConsumedTorque = 0;

    public KineticNetwork() {
        this.networkId = UUID.randomUUID();
    }

    public UUID getId() { return networkId; }

    /**
     * Главный мозг сети. Вызывается при любом изменении состава блоков.
     */
    public void recalculate(ServerLevel level) {
        this.totalGeneratedTorque = 0;
        long networkSpeed = 0; // Знаковое число скорости сети

        for (BlockPos genPos : generators) {
            if (level.getBlockEntity(genPos) instanceof Rotational gen) {
                totalGeneratedTorque += gen.getTorque();

                if (networkSpeed == 0) {
                    long speed = gen.getSpeed();

                    // --- МАГИЯ СИНХРОНИЗАЦИИ ОСЕЙ ---
                    // Получаем стейт генератора, чтобы узнать куда он смотрит
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(genPos);
                    if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                        net.minecraft.core.Direction facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);

                        // Если генератор смотрит в положительном направлении глобальных осей,
                        // мы инвертируем его скорость, чтобы она совпадала с визуальной физикой валов
                        if (facing == net.minecraft.core.Direction.SOUTH ||
                                facing == net.minecraft.core.Direction.EAST ||
                                facing == net.minecraft.core.Direction.UP) {
                            speed = -speed;
                        }
                    }

                    networkSpeed = speed;
                }
            }
        }

        long totalConsumption = members.size();

        // Если крутящего момента не хватает — останавливаем сеть
        if (totalGeneratedTorque < totalConsumption) {
            this.currentSpeed = 0;
        } else {
            this.currentSpeed = networkSpeed;
        }

        // Рассылаем итоговую скорость всем участникам
        for (BlockPos pos : members) {
            if (level.getBlockEntity(pos) instanceof Rotational node) {
                node.setSpeed(this.currentSpeed);
            }
        }
    }

    public boolean checkConflict(ServerLevel level) {
        // Если мотор один или их вообще нет — конфликта быть не может
        if (generators.size() <= 1) return false;

        long baselineSpeed = 0;

        for (BlockPos genPos : generators) {
            BlockEntity be = level.getBlockEntity(genPos);
            if (be instanceof Rotational gen) {
                long speed = gen.getSpeed();

                // Синхронизируем оси так же, как мы это делаем при расчетах скорости
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(genPos);
                if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                    net.minecraft.core.Direction facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                    if (facing == net.minecraft.core.Direction.SOUTH ||
                            facing == net.minecraft.core.Direction.EAST ||
                            facing == net.minecraft.core.Direction.UP) {
                        speed = -speed;
                    }
                }

                // Запоминаем скорость первого мотора
                if (baselineSpeed == 0) {
                    baselineSpeed = speed;
                } else if (baselineSpeed != speed) {
                    // Как только находим мотор с другой скоростью (или направлением) — трубим тревогу!
                    return true;
                }
            }
        }
        return false;
    }

    private void updateMembers(ServerLevel level) {
        for (BlockPos pos : members) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Rotational node) {
                node.setSpeed(this.currentSpeed);
                // Тут мы будем вызывать обновление визуалов Flywheel через пакеты [cite: 11, 12]
            }
        }
    }

    public void addMember(BlockPos pos) {
        this.members.add(pos);
    }

    public void addGenerator(BlockPos pos) {
        this.generators.add(pos);
        this.members.add(pos); // Генератор всегда является и обычным участником [cite: 2]
    }

    // Также добавь геттер для members, если его нет (нужен для Merge)
    public Set<BlockPos> getMembers() {
        return members;
    }

    // Добавь эти методы в KineticNetwork.java
    public long getSpeed() { return currentSpeed; }
    public Set<BlockPos> getGenerators() { return generators; }

    public void removeMember(BlockPos pos) {
        this.members.remove(pos);
        this.generators.remove(pos);
    }
}