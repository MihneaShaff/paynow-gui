package me.kyllian.PayNowGUI.handlers;

import gg.paynow.sdk.PayNowClient;
import gg.paynow.sdk.storefront.api.CartApi;
import gg.paynow.sdk.storefront.api.CustomerApi;
import gg.paynow.sdk.storefront.api.ProductsApi;
import gg.paynow.sdk.storefront.client.ApiException;
import gg.paynow.sdk.storefront.model.*;
import lombok.Getter;
import me.kyllian.PayNowGUI.PayNowGUIPlugin;
import me.kyllian.PayNowGUI.models.GUIProduct;
import me.kyllian.PayNowGUI.utils.Statistics;
import me.kyllian.PayNowGUI.utils.YMLFile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static me.kyllian.PayNowGUI.utils.StringUtils.colorize;
import static org.bukkit.Bukkit.getScheduler;

@Getter
public class ProductHandler extends YMLFile<PayNowGUIPlugin> {

    private boolean debug;
    private String storeId;
    private PayNowClient client;

    private final HashMap<Object, GUIProduct> guiProductMap = new HashMap<>(); // Map slot to GUIProduct (Display data)

    private final Map<UUID, String> customerTokens = new HashMap<>();

    public ProductHandler(PayNowGUIPlugin plugin) {
        super(plugin, "products.yml");

        allowAdditionalStorefrontProductFields();
        loadProducts();
    }

    private void allowAdditionalStorefrontProductFields() {
        StorefrontProductDto.openapiFields.add("tier_group_id");
        StorefrontProductDto.openapiFields.add("active_tier_group");
    }

    public void loadProducts() {
        this.debug = getPlugin().getConfig().getBoolean("debug", false);
        this.storeId = getPlugin().getConfig().getString("store_identifier");
        this.client = PayNowClient.forStorefront(this.storeId);

        try {
            ProductsApi productsApi = client.getStorefrontApi(ProductsApi.class);
            List<StorefrontProductDto> products = new ArrayList<>(productsApi.getStorefrontProducts(storeId, null, null, null, "en-US"));

            guiProductMap.clear();

            products.forEach(p -> {
                if (getFileConfiguration().get(p.getId().toString()) != null) {
                    String displayName = getFileConfiguration().getString(p.getId().toString() + ".display_name", "&a" + p.getName());
                    String materialName = getFileConfiguration().getString(p.getId().toString() + ".material", "STONE");
                    int customModelData = getFileConfiguration().getInt(p.getId().toString() + ".custom_model_data", 0);
                    GUIProduct guiProduct = new GUIProduct(displayName, Material.valueOf(materialName), customModelData);
                    guiProductMap.put(p.getId(), guiProduct);
                } else {
                    GUIProduct product = new GUIProduct(p);
                    guiProductMap.put(p.getId(), product);

                    getFileConfiguration().set(p.getId().toString() + ".display_name", product.getDisplayName());
                    getFileConfiguration().set(p.getId().toString() + ".material", product.getMaterial().name());
                }
            });

            Statistics.products = products.size();
            Statistics.tags = products.stream()
                    .flatMap(p -> p.getTags().stream())
                    .collect(Collectors.toSet())
                    .size();

            save();
            Bukkit.getLogger().info("[paynow-gui] Cached " + products.size() + " products");
        } catch (ApiException exception) {
            if (debug) exception.printStackTrace();
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private void runAsync(Runnable task) {
        getScheduler().runTaskAsynchronously(getPlugin(), task);
    }

    private <T> void withAuth(Player player, ThrowingFunction<PayNowClient, T> task, Consumer<T> onSuccess) {
        if (!hasToken(player)) {
            error(player);
            return;
        }
        runAsync(() -> {
            try {
                PayNowClient authClient = PayNowClient.forStorefrontWithAuth(storeId, "customer " + customerTokens.get(player.getUniqueId()));
                T result = task.apply(authClient);
                consumeOnMain(onSuccess, result);
            } catch (Exception e) {
                error(player);
                if (debug) e.printStackTrace();
            }
        });
    }

    public void getProducts(Player player, Consumer<List<StorefrontProductDto>> successCallback) {
        withAuth(player,
                c -> c.getStorefrontApi(ProductsApi.class).getStorefrontProducts(storeId, null, null, player.getAddress().getHostName(), "en-US"),
                successCallback);
    }

    public void authenticate(Player player, Consumer<String> successCallback) {
        getScheduler().runTaskAsynchronously(getPlugin(), () -> {
            CustomerApi customerApi = client.getStorefrontApi(CustomerApi.class);
            try {
                AuthenticateStorefrontCustomerResponseDto response = customerApi.authenticateStorefrontCustomer(storeId, null, "en-US",
                        new AuthenticateStorefrontCustomerRequestDto()
                                .platform(CustomerProfilePlatform.MINECRAFT)
                                .id(player.getName())
                );
                customerTokens.put(player.getUniqueId(), response.getCustomerToken());
                consumeOnMain(successCallback, response.getCustomerToken());
            } catch (Exception e) {
                error(player);
                if (debug) e.printStackTrace();
            }
        });
    }

    public void getCart(Player player, Consumer<CartDto> successCallback) {
        withAuth(player,
                c -> c.getStorefrontApi(CartApi.class).getCart(null, player.getAddress().getHostName(), "en-US"),
                successCallback);
    }

    public void setProductQuantityInCart(Player player, Object gameServerId, Object productId, int quantity, int delta, Consumer<Void> successCallback) {
        if (delta < 0) Statistics.productsRemoved.getAndAdd(Math.abs(delta));
        else Statistics.productsAdded.getAndAdd(delta);

        withAuth(player, c -> {
            CartApi cartApi = c.getStorefrontApi(CartApi.class);
            cartApi.addLine(productId.toString(), quantity, null, null, gameServerId != null ? gameServerId.toString() : null, null, null, null, null, null, player.getAddress().getHostName(), null);
            return null;
        }, successCallback);
    }

    public void clearCart(Player player, Consumer<Void> successCallback) {
        withAuth(player, c -> {
            c.getStorefrontApi(CartApi.class).clearCart(player.getAddress().getHostName(), null);
            return null;
        }, successCallback);
    }

    public void createCheckout(Player player, Consumer<CreateCheckoutSessionResponseDto> successCallback) {
        withAuth(player, c -> {
            CreateCartCheckoutSessionDto request = new CreateCartCheckoutSessionDto()
                    .returnUrl(getPlugin().getConfig().getString("return_url"))
                    .cancelUrl(getPlugin().getConfig().getString("cancel_url"));
            return c.getStorefrontApi(CartApi.class).createCartCheckout(player.getAddress().getHostName(), null, request);
        }, successCallback);
    }

    private boolean hasToken(Player player) {
        return player != null && customerTokens.containsKey(player.getUniqueId());
    }

    private <T> void consumeOnMain(Consumer<T> consumer, T param) {
        if (consumer == null) return;
        getScheduler().runTask(getPlugin(), () -> consumer.accept(param));
    }

    private void error(Player player) {
        if (player == null || !player.isOnline()) return;
        getScheduler().runTask(getPlugin(), () -> {
            player.closeInventory();
            player.sendMessage(colorize(getPlugin().getConfig().getString("messages.error")));
        });
    }
}
