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
        long maxPotentialSpeed = 0;

        for (BlockPos genPos : generators) {
            if (level.getBlockEntity(genPos) instanceof Rotational gen) {
                totalGeneratedTorque += gen.getTorque(); // [cite: 8]
                maxPotentialSpeed = Math.max(maxPotentialSpeed, gen.getSpeed()); // [cite: 9]
            }
        }

        // Пока что упрощенное потребление: каждый блок в сети ест 1 единицу Torque
        long totalConsumption = members.size();

        if (totalGeneratedTorque < totalConsumption) {
            this.currentSpeed = 0; // Overstressed! [cite: 9]
        } else {
            this.currentSpeed = maxPotentialSpeed;
        }

        // Рассылаем скорость
        for (BlockPos pos : members) {
            if (level.getBlockEntity(pos) instanceof Rotational node) {
                node.setSpeed(this.currentSpeed); // [cite: 4]
            }
        }

        LOGGER.info("[Kinetic] Network {}: Torque {}/{}, Speed {}",
                networkId.toString().substring(0, 8), totalGeneratedTorque, members.size(), currentSpeed);
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

    public void removeMember(BlockPos pos) {
        this.members.remove(pos);
        this.generators.remove(pos);
    }
}