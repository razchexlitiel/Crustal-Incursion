package com.cim.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import net.minecraftforge.fml.loading.LoadingModList;

import java.util.List;
import java.util.Set;

public class OculusCompatMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Если миксин относится к нашей папке совместимости с Iris/Oculus
        if (mixinClassName.contains("compat.irisflw")) {
            try {
                // Проверяем наличие Окулуса самым надежным способом для этой стадии загрузки
                Class.forName("net.irisshaders.iris.Iris", false, this.getClass().getClassLoader());

                // Если мы здесь, значит Окулус в сборке есть.
                // Теперь проверяем, нет ли оригинального мода-костыля, чтобы не было конфликта
                return !isModLoaded("oculusflywheelcompat") && !isModLoaded("irisflw");
            } catch (ClassNotFoundException e) {
                // Окулуса нет — отключаем миксин, чтобы не было краша
                return false;
            }
        }
        return true;
    }

    private boolean isModLoaded(String modId) {
        try {
            return net.minecraftforge.fml.loading.LoadingModList.get().getModFileById(modId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // Остальные методы интерфейса просто оставляем пустыми
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
