package me.kyllian.PayNowGUI.hooks.npc;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

public class FancyNpcsHook implements INpcHook {

    @Override
    public void updateNpc(String npcId, String skinName, List<String> hologramLines) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("FancyNpcs");
            if (plugin == null || !plugin.isEnabled()) return;

            Object npcManager = call(plugin, "getNpcManager");
            Object npc = call(npcManager, "getNpc", npcId);
            if (npc == null) {
                npc = call(npcManager, "getNpcById", npcId);
            }
            if (npc == null) {
                Bukkit.getLogger().warning("[paynow-gui] FancyNpcs NPC not found: " + npcId);
                return;
            }

            Object npcData = call(npc, "getData");
            if (skinName != null && !skinName.isBlank()) {
                call(npcData, "setSkin", skinName);
            }
            call(npcData, "setDisplayName", String.join("\n", hologramLines));
            call(npc, "updateForAll");
            call(npc, "removeForAll");
            call(npc, "spawnForAll");
        } catch (ReflectiveOperationException exception) {
            Bukkit.getLogger().warning("[paynow-gui] Failed to update FancyNpcs NPC " + npcId + ": " + exception.getMessage());
        }
    }

    private Object call(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }

        Method method = target.getClass().getMethod(methodName, argTypes);
        return method.invoke(target, args);
    }
}
