package nuper.eagler.elytrachestplate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ElytraChestplatePlugin extends JavaPlugin implements Listener {
    private static final Set<Material> CHESTPLATES = Set.of(
        Material.LEATHER_CHESTPLATE,
        Material.CHAINMAIL_CHESTPLATE,
        Material.IRON_CHESTPLATE,
        Material.GOLDEN_CHESTPLATE,
        Material.DIAMOND_CHESTPLATE,
        Material.NETHERITE_CHESTPLATE
    );

    private final Map<UUID, Integer> glidingTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> heldRightClickUntil = new ConcurrentHashMap<>();
    private NamespacedKey hybridKey;
    private NamespacedKey recipeKey;
    private BukkitTask flightTask;

    @Override
    public void onEnable() {
        hybridKey = new NamespacedKey(this, "elytra_chestplate");
        recipeKey = new NamespacedKey(this, "combine_chestplate_elytra");
        registerRecipe();
        Bukkit.getPluginManager().registerEvents(this, this);
        flightTask = Bukkit.getScheduler().runTaskTimer(this, this::tickFlight, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (flightTask != null) flightTask.cancel();
        Bukkit.removeRecipe(recipeKey);
        glidingTicks.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isHybrid(player.getInventory().getChestplate()) && player.isGliding()) {
                player.setGliding(false);
            }
        }
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, new ItemStack(Material.IRON_CHESTPLATE));
        recipe.addIngredient(new RecipeChoice.MaterialChoice(List.copyOf(CHESTPLATES)));
        recipe.addIngredient(Material.ELYTRA);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed) || !recipeKey.equals(keyed.getKey())) {
            preserveHybridInCraftResult(event);
            return;
        }

        ItemStack chestplate = null;
        boolean hasElytra = false;
        int ingredientCount = 0;
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            ingredientCount++;
            if (ingredient.getType() == Material.ELYTRA && !hasElytra) {
                hasElytra = true;
            } else if (CHESTPLATES.contains(ingredient.getType()) &&
                       chestplate == null && !isHybrid(ingredient)) {
                chestplate = ingredient;
            } else {
                event.getInventory().setResult(null);
                return;
            }
        }

        if (ingredientCount != 2 || !hasElytra || chestplate == null) {
            event.getInventory().setResult(null);
            return;
        }
        event.getInventory().setResult(createHybrid(chestplate));
    }

    private void preserveHybridInCraftResult(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || !CHESTPLATES.contains(result.getType())) return;
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isHybrid(ingredient) && ingredient.getType() == result.getType()) {
                event.getInventory().setResult(markHybrid(result));
                return;
            }
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack base = event.getInventory().getInputEquipment();
        ItemStack result = event.getInventory().getResult();
        if (!isHybrid(base) || result == null || !CHESTPLATES.contains(result.getType())) return;
        event.getInventory().setResult(markHybrid(result));
    }

    private ItemStack createHybrid(ItemStack chestplate) {
        return markHybrid(chestplate.clone());
    }

    private ItemStack markHybrid(ItemStack hybrid) {
        ItemMeta meta = hybrid.getItemMeta();
        meta.getPersistentDataContainer().set(hybridKey, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String elytraLore = ChatColor.AQUA + "Elytra";
        if (!lore.contains(elytraLore)) lore.add(elytraLore);
        meta.setLore(lore);
        hybrid.setItemMeta(meta);
        return hybrid;
    }

    private boolean isHybrid(ItemStack item) {
        if (item == null || !CHESTPLATES.contains(item.getType()) || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(hybridKey, PersistentDataType.BYTE);
    }

    private void tickFlight() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack chestplate = player.getInventory().getChestplate();
            if (!isHybrid(chestplate)) {
                boolean wasHybridGliding = glidingTicks.containsKey(player.getUniqueId());
                glidingTicks.remove(player.getUniqueId());
                if (wasHybridGliding && player.isGliding()) player.setGliding(false);
                continue;
            }

            if (player.isOnGround() || player.isInWater() || player.isSwimming()) {
                glidingTicks.remove(player.getUniqueId());
                heldRightClickUntil.remove(player.getUniqueId());
                if (player.isGliding()) player.setGliding(false);
                continue;
            }
            long now = System.currentTimeMillis();
            Long holdUntil = heldRightClickUntil.get(player.getUniqueId());
            boolean rightClickHeld = holdUntil != null && now < holdUntil;
            if (!rightClickHeld) heldRightClickUntil.remove(player.getUniqueId());
            if (rightClickHeld) {
                glidingTicks.remove(player.getUniqueId());
                if (player.isGliding()) player.setGliding(false);
                continue;
            }
            if (!player.isGliding() && player.getFallDistance() >= 1.0f &&
                player.getVelocity().getY() < -0.02) {
                player.setGliding(true);
            }
            if (!player.isGliding()) {
                glidingTicks.remove(player.getUniqueId());
                continue;
            }

            int ticks = glidingTicks.merge(player.getUniqueId(), 1, Integer::sum);
            if (ticks >= 10) {
                ItemStack wornChestplate = player.getInventory().getChestplate();
                if (isHybrid(wornChestplate) && wornChestplate.getItemMeta() instanceof Damageable) {
                    player.getInventory().setChestplate(wornChestplate.damage(1, player));
                }
                glidingTicks.put(player.getUniqueId(), 0);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        glidingTicks.remove(event.getPlayer().getUniqueId());
        heldRightClickUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
            event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!isHybrid(player.getInventory().getChestplate())) return;
        if (player.isOnGround() || player.isInWater() || player.isSwimming()) return;
        if (usesItemNormally(player.getInventory().getItemInMainHand()) ||
            usesItemNormally(player.getInventory().getItemInOffHand())) return;
        heldRightClickUntil.put(player.getUniqueId(), System.currentTimeMillis() + 500L);
    }

    private boolean usesItemNormally(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return type == Material.WIND_CHARGE ||
            type == Material.FIREWORK_ROCKET ||
            type.isEdible();
    }
}
