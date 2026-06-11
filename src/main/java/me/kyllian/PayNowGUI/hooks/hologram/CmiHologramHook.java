package me.kyllian.PayNowGUI.hooks.hologram;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

public class CmiHologramHook implements IHologramHook {

    @Override
    public void updateHologram(String hologramName, List<String> lines) {
        if (hologramName == null || hologramName.isBlank()) return;

        try {
            Plugin cmiPlugin = Bukkit.getPluginManager().getPlugin("CMI");
            if (cmiPlugin == null || !cmiPlugin.isEnabled()) return;

            Object hologramManager = call(cmiPlugin, "getHologramManager");
            Object hologram = call(hologramManager, "getByName", hologramName);
            if (hologram == null) {
                Bukkit.getLogger().warning("[paynow-gui] CMI hologram not found: " + hologramName);
                return;
            }

            clearLines(hologram);
            for (String line : lines) {
                call(hologram, "addLine", line);
            }
            call(hologram, "setSpacing", 0.25D);
            call(hologram, "update");
            call(hologram, "refresh");
            call(hologramManager, "save");
        } catch (ReflectiveOperationException exception) {
            Bukkit.getLogger().warning("[paynow-gui] Failed to update CMI hologram " + hologramName + ": " + exception.getMessage());
        }
    }

    private void clearLines(Object hologram) throws ReflectiveOperationException {
        Object currentLines = call(hologram, "getLines");
        if (!(currentLines instanceof List<?> lines)) return;

        for (int i = lines.size(); i >= 1; i--) {
            call(hologram, "removeLine", i);
        }
    }

    private Object call(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, args);
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        return method.invoke(target, args);
    }

    private Method findMethod(Class<?> targetClass, String methodName, Object[] args) {
        for (Method method : targetClass.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !isAssignable(parameterTypes[i], args[i].getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) return method;
        }
        return null;
    }

    private boolean isAssignable(Class<?> parameterType, Class<?> argType) {
        if (parameterType.isAssignableFrom(argType)) return true;
        if (!parameterType.isPrimitive()) return false;

        return parameterType == int.class && argType == Integer.class
                || parameterType == boolean.class && argType == Boolean.class
                || parameterType == double.class && argType == Double.class
                || parameterType == float.class && argType == Float.class
                || parameterType == long.class && argType == Long.class
                || parameterType == short.class && argType == Short.class
                || parameterType == byte.class && argType == Byte.class
                || parameterType == char.class && argType == Character.class;
    }
}
