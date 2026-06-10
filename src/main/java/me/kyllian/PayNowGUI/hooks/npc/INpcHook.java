package me.kyllian.PayNowGUI.hooks.npc;

import java.util.List;

public interface INpcHook {

    void updateNpc(String npcId, String skinName, List<String> hologramLines);

}
