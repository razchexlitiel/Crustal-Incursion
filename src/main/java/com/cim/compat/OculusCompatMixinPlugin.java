package com.cim.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class OculusCompatMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("compat.irisflw.mixin")) {
            // Проверяем наличие конкурентов ПРЯМО ТУТ (до начала загрузки миксинов)
            boolean hasConflict = Thread.currentThread().getContextClassLoader().getResource("irisflw.mixins.json") != null;

            if (hasConflict) {
                // Если нашли файл миксинов Леона - ПОЛНОСТЬЮ отключаем свои, чтобы не было 'overwrite conflict'
                return false;
            }

            // Если конкурентов нет, проверяем наличие Окулуса
            return Thread.currentThread().getContextClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;
        }
        return true;
    }

    // Остальные методы оставляем пустыми...
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}