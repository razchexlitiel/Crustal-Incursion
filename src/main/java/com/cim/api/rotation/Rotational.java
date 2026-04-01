package com.cim.api.rotation;

import net.minecraft.core.Direction;

/**
 * Базовый интерфейс для всего, что вращается.
 * Используется KineticNetwork для управления скоростью.
 */
public interface Rotational {
    // Получение текущих данных (для расчетов в сети)
    long getSpeed();
    long getTorque();

    // Установка данных (вызывается сетью после recalculate)
    void setSpeed(long speed);

    // Лимиты (нужны для проверки поломки/перегрузки)
    long getMaxSpeed();
    long getMaxTorque();

    /**
     * Возвращает true, если этот блок производит энергию (мотор, ветряк).
     * Помогает сети быстро наполнять список generators[cite: 2].
     */
    default boolean isSource() {
        return false;
    }

    default Direction[] getPropagationDirections() {
        return Direction.values(); // По умолчанию — все стороны
    }
}