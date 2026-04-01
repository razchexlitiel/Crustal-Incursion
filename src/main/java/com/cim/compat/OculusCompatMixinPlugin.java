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
            // Проверяем наличие ядра Окулуса в памяти
            boolean hasOculus = Thread.currentThread().getContextClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;

            if (!hasOculus) {
                System.out.println("[CIM] Oculus не найден в среде разработки. Миксины отключены.");
                return false; // Спасает от краша в runClient!
            }
            return true;
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