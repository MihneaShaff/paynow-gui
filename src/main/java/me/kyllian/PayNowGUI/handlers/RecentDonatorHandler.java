package me.kyllian.PayNowGUI.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import gg.paynow.sdk.PayNowClient;
import gg.paynow.sdk.storefront.api.ModulesApi;
import gg.paynow.sdk.storefront.model.ModuleDto;
import me.kyllian.PayNowGUI.PayNowGUIPlugin;
import me.kyllian.PayNowGUI.hooks.npc.INpcHook;
import me.kyllian.PayNowGUI.models.RecentOrder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static me.kyllian.PayNowGUI.utils.StringUtils.colorize;

public class RecentDonatorHandler {

    private final PayNowGUIPlugin plugin;
    private final INpcHook npcHook;
    private final Gson gson = new Gson();
    private int taskId = -1;

    public RecentDonatorHandler(PayNowGUIPlugin plugin, INpcHook npcHook) {
        this.plugin = plugin;
        this.npcHook = npcHook;

        start();
    }

    public void start() {
        if (!isNpcEnabled("recent_donator_npc")
                && !isNpcEnabled("top_donator_all_time_npc")
                && !isNpcEnabled("top_donator_month_npc")) return;

        long intervalTicks = plugin.getConfig().getInt(
                "donation_npc_update_every",
                plugin.getConfig().getInt("recent_donator_npc.update_every", 60)
        ) * 20L;
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchAndUpdate, 0L, intervalTicks).getTaskId();
    }

    private void fetchAndUpdate() {
        try {
            String storeId = plugin.getConfig().getString("store_identifier");
            PayNowClient client = PayNowClient.forStorefront(storeId);

            ModulesApi modulesApi = client.getStorefrontApi(ModulesApi.class);
            List<ModuleDto> modules = modulesApi.getPreparedModules(storeId);

            for (ModuleDto module : modules) {
                String moduleId = module.getId().toString().toLowerCase(Locale.ROOT);
                JsonElement moduleData = gson.toJsonTree(module.getData());

                if (moduleId.equals("recent_payments")) {
                    updateRecentDonatorNpc(moduleData);
                    continue;
                }

                if (moduleId.contains("top") && (moduleId.contains("donator") || moduleId.contains("donor"))) {
                    updateTopDonatorNpcs(moduleId, moduleData);
                }
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                e.printStackTrace();
            }
        }
    }

    private void updateRecentDonatorNpc(JsonElement moduleData) {
        if (!isNpcEnabled("recent_donator_npc")) return;

        List<RecentOrder> orders = gson.fromJson(
                firstArray(moduleData, "orders", "payments", "recent_payments"),
                new TypeToken<List<RecentOrder>>() {}.getType()
        );
        if (orders == null || orders.isEmpty()) return;

        RecentOrder mostRecent = orders.getFirst();
        String customerName = mostRecent.getCustomer() != null
                ? mostRecent.getCustomer().getName()
                : "Unknown";

        ConfigurationSection config = plugin.getConfig().getConfigurationSection("recent_donator_npc");
        if (config == null) return;
        String packageFormat = config.getString("package_format", "&8- &7%name% &d&l%amount%");

        StringBuilder packagesBuilder = new StringBuilder();
        List<RecentOrder.RecentOrderLine> lines = mostRecent.getLines();
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                RecentOrder.RecentOrderLine line = lines.get(i);
                String name = line.getProductName() != null ? line.getProductName() : "Unknown";
                String amount = String.format("$%.2f", line.getPrice() / 100.0);
                packagesBuilder.append(packageFormat
                        .replace("%name%", name)
                        .replace("%amount%", amount));
                if (i < lines.size() - 1) packagesBuilder.append("\n");
            }
        }

        String total = mostRecent.getTotalAmountStr() != null
                ? mostRecent.getTotalAmountStr()
                : String.format("$%.2f", mostRecent.getTotalAmount() / 100.0);

        updateNpc(config, customerName, placeholders(config)
                .replace("%packages%", packagesBuilder.toString())
                .replace("%name%", customerName)
                .replace("%total%", total));
    }

    private void updateTopDonatorNpcs(String moduleId, JsonElement moduleData) {
        if (isNpcEnabled("top_donator_all_time_npc") && !moduleId.contains("month")) {
            TopDonator topDonator = parseTopDonator(moduleData, true);
            if (topDonator != null) {
                updateTopDonatorNpc("top_donator_all_time_npc", topDonator);
            }
        }

        if (isNpcEnabled("top_donator_month_npc") && !moduleId.contains("all")) {
            TopDonator topDonator = parseTopDonator(moduleData, false);
            if (topDonator != null) {
                updateTopDonatorNpc("top_donator_month_npc", topDonator);
            }
        }
    }

    private void updateTopDonatorNpc(String path, TopDonator topDonator) {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection(path);
        updateNpc(config, topDonator.skin(), placeholders(config)
                .replace("%name%", topDonator.name())
                .replace("%total%", topDonator.total()));
    }

    private void updateNpc(ConfigurationSection config, String skinName, String hologramText) {
        if (config == null) return;

        String npcId = config.getString("npc_id", "99");
        List<String> hologramLines = new ArrayList<>();
        for (String hologramLine : hologramText.split("\n")) {
            hologramLines.add(colorize(hologramLine));
        }

        Bukkit.getScheduler().runTask(plugin, () -> npcHook.updateNpc(npcId, skinName, hologramLines));
    }

    private String placeholders(ConfigurationSection config) {
        return config != null ? config.getString("hologram", "") : "";
    }

    private boolean isNpcEnabled(String path) {
        return plugin.getConfig().getBoolean(path + ".enabled", false);
    }

    private JsonArray firstArray(JsonElement element, String... keys) {
        JsonElement result = firstElement(element, keys);
        if (result != null && result.isJsonArray()) return result.getAsJsonArray();
        if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        return new JsonArray();
    }

    private TopDonator parseTopDonator(JsonElement moduleData, boolean allTime) {
        JsonElement scoped = firstElement(moduleData, allTime
                ? new String[]{"all_time", "allTime", "lifetime", "overall", "top_all_time"}
                : new String[]{"this_month", "thisMonth", "month", "monthly", "current_month", "top_month"});

        JsonElement source = scoped != null ? scoped : moduleData;
        JsonObject entry = directTopDonatorObject(source);
        if (entry == null) {
            JsonArray entries = firstArray(source, "donators", "donors", "customers", "payments", "orders", "top");
            if (entries.isEmpty()) return null;

            entry = entries.get(0).isJsonObject() ? entries.get(0).getAsJsonObject() : null;
        }
        if (entry == null) return null;

        JsonObject customer = object(entry, "customer", "user", "player");
        String name = firstString(entry, "name", "username", "customer_name", "player_name");
        if (name == null && customer != null) {
            name = firstString(customer, "name", "username", "customer_name", "player_name");
        }
        if (name == null || name.isBlank()) name = "Unknown";

        String skin = firstString(entry, "minecraft_uuid", "uuid", "skin", "skin_name");
        if (skin == null && customer != null) {
            skin = firstString(customer, "minecraft_uuid", "uuid", "skin", "skin_name");
        }
        if (skin == null || skin.isBlank()) skin = name;

        String total = firstString(entry, "total_amount_str", "amount_str", "total_str", "total", "amount");
        if (total == null && customer != null) {
            total = firstString(customer, "total_amount_str", "amount_str", "total_str", "total", "amount");
        }
        if (total == null) {
            total = "$0.00";
        } else if (total.matches("\\d+(\\.\\d+)?")) {
            total = "$" + total;
        }

        return new TopDonator(name, skin, total);
    }

    private JsonObject directTopDonatorObject(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;

        JsonObject object = element.getAsJsonObject();
        if (hasAny(object, "name", "username", "customer_name", "player_name", "customer", "user", "player")) {
            return object;
        }
        return null;
    }

    private JsonElement firstElement(JsonElement element, String... keys) {
        if (element == null || !element.isJsonObject()) return null;

        JsonObject object = element.getAsJsonObject();
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key);
            }
        }

        for (String key : object.keySet()) {
            JsonElement child = object.get(key);
            if (child != null && child.isJsonObject()) {
                JsonElement nested = firstElement(child, keys);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private JsonObject object(JsonObject object, String... keys) {
        JsonElement element = firstElement(object, keys);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;

            JsonElement element = object.get(key);
            if (element.isJsonPrimitive()) return element.getAsString();
        }
        return null;
    }

    private boolean hasAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) return true;
        }
        return false;
    }

    private record TopDonator(String name, String skin, String total) {}
}
