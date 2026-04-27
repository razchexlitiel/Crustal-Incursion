package com.cim.entity.mobs.depth_worm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class DepthWormBrutalJumpGoal extends Goal {
    private final DepthWormBrutalEntity worm;
    private final double speedModifier;
    private final float jumpRangeMin, jumpRangeMax;

    private LivingEntity target;
    private int prepareTimer;
    private boolean jumpPerformed;
    private static final int PREPARE_TIME = 40; // ~2 секунды (40 тиков)

    // Параметры прыжка
    private Vec3 jumpStartPos;
    private Vec3 jumpTargetPos;
    private static final double BASE_HORIZONTAL_SPEED = 1.1;
    private static final double MAX_HORIZONTAL_SPEED = 2.2;
    private static final double MIN_VERTICAL_BOOST = 0.35;
    private static final double MAX_VERTICAL_BOOST = 0.85;

    public DepthWormBrutalJumpGoal(DepthWormBrutalEntity worm, double speedModifier, float jumpRangeMin, float jumpRangeMax) {
        this.worm = worm;
        this.speedModifier = speedModifier;
        this.jumpRangeMin = jumpRangeMin;
        this.jumpRangeMax = jumpRangeMax;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        this.target = this.worm.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;

        // Не прыгаем если уже кого-то насаживаем или готовимся
        if (this.worm.isImpaling() || this.worm.isPreparingJump()) return false;

        double dist = this.worm.distanceTo(this.target);
        return dist >= this.jumpRangeMin && dist <= this.jumpRangeMax;
    }

    @Override
    public boolean canContinueToUse() {
        if (jumpPerformed && !worm.isFlying() && worm.onGround()) return false;
        if (jumpPerformed) return true; // Долетаем/падаем
        return prepareTimer > 0 && target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.prepareTimer = PREPARE_TIME;
        this.jumpPerformed = false;
        this.worm.setPreparingJump(true);
        this.worm.getNavigation().stop();
        this.worm.setAttacking(true); // Для текстуры attack
    }

    @Override
    public void stop() {
        this.target = null;
        this.worm.setPreparingJump(false);
        this.worm.setAttacking(false);
        this.jumpPerformed = false;
        this.worm.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.target == null || !this.target.isAlive()) {
            abortPrepare();
            return;
        }

        double dist = this.worm.distanceTo(this.target);

        // Если цель убежала слишком далеко во время прицеливания — сброс
        if (!jumpPerformed && dist > this.jumpRangeMax + 4.0F) {
            abortPrepare();
            return;
        }

        // Смотрим на цель
        this.worm.getLookControl().setLookAt(this.target, 30.0F, 30.0F);

        if (!jumpPerformed) {
            // Фаза прицеливания
            if (--this.prepareTimer <= 0) {
                if (tryExecuteJump()) {
                    jumpPerformed = true;
                } else {
                    abortPrepare(); // Не удалось — траектория заблокирована
                }
            }
        } else {
            // Фаза полёта — проверяем столкновение с целью в воздухе
            checkMidAirCollision();
        }
    }

    private void abortPrepare() {
        this.prepareTimer = 0;
        this.worm.setPreparingJump(false);
        this.worm.setAttacking(false);
        this.jumpPerformed = true; // Чтобы canContinueToUse вернул false
    }

    /** Проверяет траекторию и выполняет прыжок на упреждение */
    private boolean tryExecuteJump() {
        Vec3 wormPos = this.worm.position();
        Vec3 targetPos = this.target.position();
        Vec3 targetVel = this.target.getDeltaMovement();

        // === РАСЧЁТ УПРЕЖДЕНИЯ ===
        // Предполагаемое время полёта ~1.5-2.5 сек в зависимости от дистанции
        double horizontalDist = Math.sqrt(
                (targetPos.x - wormPos.x) * (targetPos.x - wormPos.x) +
                        (targetPos.z - wormPos.z) * (targetPos.z - wormPos.z)
        );
        double estimatedFlightTime = 0.6 + horizontalDist * 0.08;
        estimatedFlightTime = Math.min(estimatedFlightTime, 2.5);

        // Куда цель будет через estimatedFlightTime
        Vec3 predictedPos = targetPos.add(
                targetVel.x * estimatedFlightTime,
                0, // Вертикаль не предсказываем (сложно)
                targetVel.z * estimatedFlightTime
        );

        // Разница по осям
        double dx = predictedPos.x - wormPos.x;
        double dy = (targetPos.y + this.target.getBbHeight() * 0.5) - (wormPos.y + this.worm.getBbHeight() * 0.3);
        double dz = predictedPos.z - wormPos.z;

        double predHorizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (predHorizontalDist < 1.0) return false; // Слишком близко после предсказания

        // === ПРОВЕРКА ТРАЕКТОРИИ НА БЛОКИ ===
        if (!isTrajectoryClear(wormPos, predictedPos, dy)) {
            return false;
        }

        // === РАСЧЁТ СКОРОСТИ ===
        double speed = BASE_HORIZONTAL_SPEED + (predHorizontalDist * 0.06);
        speed = Math.min(speed, MAX_HORIZONTAL_SPEED);

        // Вертикальный импульс
        double verticalBoost;
        if (dy > 3.0) {
            verticalBoost = MAX_VERTICAL_BOOST + Math.min(dy * 0.08, 0.4);
        } else if (dy > 0.5) {
            verticalBoost = 0.55 + dy * 0.06;
        } else if (dy > -1.5) {
            verticalBoost = MIN_VERTICAL_BOOST + 0.15;
        } else {
            verticalBoost = MIN_VERTICAL_BOOST; // Цель ниже — минимальный бросок вверх
        }

        // Нормализуем горизонтальное направление
        Vec3 horizontalDir = new Vec3(dx, 0, dz).normalize();

        // Итоговая скорость
        Vec3 velocity = horizontalDir.scale(speed).add(0, verticalBoost, 0);

        // Поворачиваем мордой
        double yaw = Math.atan2(dz, dx) * (180 / Math.PI) - 90;
        this.worm.setYRot((float) yaw);
        this.worm.yHeadRot = (float) yaw;
        this.worm.yBodyRot = (float) yaw;

        // Применяем
        this.worm.setDeltaMovement(velocity);
        this.worm.setFlying(true);
        this.worm.hasImpulse = true;
        this.worm.setPreparingJump(false);
        this.worm.setAttacking(false); // Сбрасываем attack, включается jump анимация

        this.jumpStartPos = wormPos;
        this.jumpTargetPos = predictedPos;

        return true;
    }

    /** Проверяет, что между start и end нет сплошных блоков */
    private boolean isTrajectoryClear(Vec3 start, Vec3 end, double targetYDiff) {
        Vec3 dir = end.subtract(start);
        double length = dir.length();
        if (length < 0.1) return true;

        Vec3 step = dir.normalize().scale(0.5); // Шаг проверки — полблока
        int steps = (int) (length / 0.5) + 1;

        // Проверяем траекторию с небольшим запасом по высоте (червь ~0.6 блока высотой)
        AABB wormBox = new AABB(-0.3, 0, -0.3, 0.3, 0.6, 0.3);

        Vec3 current = start;
        for (int i = 0; i < steps; i++) {
            // Поднимаем проверочную точку по параболе (упрощённо)
            double progress = i / (double) steps;
            double arcHeight = Math.sin(progress * Math.PI) * Math.max(0.5, targetYDiff * 0.3);
            Vec3 checkPos = current.add(0, arcHeight, 0);

            AABB checkBox = wormBox.move(checkPos);

            // Проверяем столкновение со сплошными блоками
            BlockPos min = BlockPos.containing(checkBox.minX, checkBox.minY, checkBox.minZ);
            BlockPos max = BlockPos.containing(checkBox.maxX, checkBox.maxY, checkBox.maxZ);

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = this.worm.level().getBlockState(pos);
                        if (state.isSolid() && state.getCollisionShape(this.worm.level(), pos) != null) {
                            // Есть блок на пути
                            return false;
                        }
                    }
                }
            }

            current = current.add(step);
        }

        return true;
    }

    /** Проверяет столкновение с целью в полёте */
    private void checkMidAirCollision() {
        if (this.worm.isImpaling()) return; // Уже насажили

        AABB wormBox = this.worm.getBoundingBox().inflate(0.4);
        if (wormBox.intersects(this.target.getBoundingBox())) {
            executeImpaleOrBounce();
        }
    }

    /** Либо насаживаем, либо отскакиваем */
    private void executeImpaleOrBounce() {
        int armor = this.target.getArmorValue();

        if (armor < 12) {
            // === НАСАЖИВАНИЕ ===
            this.target.hurt(this.worm.damageSources().mobAttack(this.worm), 10.0F);
            this.worm.setImpaledTarget(this.target);

            // Червь продолжает лететь, но чуть замедляется от тяжести
            Vec3 vel = this.worm.getDeltaMovement();
            this.worm.setDeltaMovement(vel.scale(0.85).add(0, 0.05, 0));

        } else {
            // === ОТСКОК ===
            this.target.hurt(this.worm.damageSources().mobAttack(this.worm), 5.0F);

            // Червь отскакивает назад и вверх
            Vec3 bounce = this.worm.getLookAngle().scale(-0.6).add(0, 0.4, 0);
            this.worm.setDeltaMovement(bounce);

            // Короткий стан
            this.worm.setFlying(false);
        }
    }
}