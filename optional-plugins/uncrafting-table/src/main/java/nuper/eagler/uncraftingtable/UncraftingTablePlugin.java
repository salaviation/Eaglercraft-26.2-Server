package nuper.eagler.uncraftingtable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UncraftingTablePlugin extends JavaPlugin implements Listener {
    private static final String TABLE_NAME = "Uncrafting Table";

    private final Set<String> registeredTables = new LinkedHashSet<>();
    private final Map<UUID, CraftingInventory> openTables = new HashMap<>();
    private final Map<UUID, UncraftSession> sessions = new HashMap<>();
    private NamespacedKey hybridKey;
    private File tablesFile;

    @Override
    public void onEnable() {
        hybridKey = new NamespacedKey("elytrachestplate", "elytra_chestplate");
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create UncraftingTable plugin data directory.");
        }
        tablesFile = new File(getDataFolder(), "tables.yml");
        loadTables();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void loadTables() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(tablesFile);
        registeredTables.clear();
        registeredTables.addAll(data.getStringList("tables"));
    }

    private void saveTables() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("tables", new ArrayList<>(registeredTables));
        try {
            data.save(tablesFile);
        } catch (IOException e) {
            getLogger().severe("Unable to save Uncrafting Table locations: " + e.getMessage());
        }
    }

    private String locationKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private boolean isUncraftingTable(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() &&
            TABLE_NAME.equals(ChatColor.stripColor(meta.getDisplayName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTablePlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.CRAFTING_TABLE ||
            !isUncraftingTable(event.getItemInHand())) return;
        registeredTables.add(locationKey(event.getBlockPlaced()));
        saveTables();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTableBreak(BlockBreakEvent event) {
        if (registeredTables.remove(locationKey(event.getBlock()))) saveTables();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTableUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !registeredTables.contains(locationKey(block))) return;
        event.setCancelled(true);
        openTable(event.getPlayer(), block);
    }

    private void openTable(Player player, Block block) {
        InventoryView view = player.openWorkbench(block.getLocation(), true);
        if (!(view.getTopInventory() instanceof CraftingInventory inventory)) {
            player.closeInventory();
            getLogger().warning("Could not open the native workbench inventory for " + player.getName() + ".");
            return;
        }
        UUID playerId = player.getUniqueId();
        openTables.put(playerId, inventory);
        sessions.put(playerId, new UncraftSession());
        inventory.setMatrix(new ItemStack[9]);
        inventory.setResult(null);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        CraftingInventory inventory = openTables.get(playerId);
        UncraftSession session = sessions.get(playerId);
        if (inventory == null || session == null || event.getView().getTopInventory() != inventory) return;

        if (event.getClickedInventory() != inventory) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot == 0) {
            moveCursorToResult(event, inventory, session);
        } else if (rawSlot >= 1 && rawSlot <= 9) {
            takeIngredient(event, inventory, session, rawSlot - 1);
        }
    }

    private void moveCursorToResult(InventoryClickEvent event, CraftingInventory inventory,
                                    UncraftSession session) {
        if (event.getClick().isKeyboardClick() || event.getClick().isCreativeAction() ||
            session.committed) return;

        ItemStack cursor = copy(event.getCursor());
        ItemStack input = copy(session.input);
        boolean rightClick = event.isRightClick();

        if (isEmpty(cursor) && !isEmpty(input)) {
            cursor = input;
            input = null;
        } else if (!isEmpty(cursor) && isEmpty(input)) {
            input = cursor;
            cursor = null;
        } else if (!isEmpty(cursor) && !isEmpty(input) && cursor.isSimilar(input)) {
            int moved = Math.min(cursor.getAmount(), input.getMaxStackSize() - input.getAmount());
            if (moved <= 0) return;
            input.setAmount(input.getAmount() + moved);
            cursor.setAmount(cursor.getAmount() - moved);
            if (cursor.getAmount() <= 0) cursor = null;
        } else if (!isEmpty(cursor) && !isEmpty(input) && !rightClick) {
            ItemStack swap = input;
            input = cursor;
            cursor = swap;
        }

        session.input = input;
        event.setCursor(cursor);
        refreshPreview(inventory, session);
    }

    private void takeIngredient(InventoryClickEvent event, CraftingInventory inventory,
                                UncraftSession session, int slot) {
        ItemStack[] matrix = inventory.getMatrix();
        ItemStack ingredient = matrix[slot];
        if (isEmpty(ingredient)) return;

        ItemStack cursor = copy(event.getCursor());
        if (!isEmpty(cursor) && !cursor.isSimilar(ingredient)) return;
        int capacity = isEmpty(cursor) ? ingredient.getMaxStackSize() :
            cursor.getMaxStackSize() - cursor.getAmount();
        int moved = Math.min(capacity, ingredient.getAmount());
        if (moved <= 0) return;

        if (!session.committed) {
            UncraftResult result = findUncraftResult(session.input);
            if (result == null || session.input.getAmount() < result.inputAmount) return;
            session.input.setAmount(session.input.getAmount() - result.inputAmount);
            session.committed = true;
        }

        if (isEmpty(cursor)) {
            cursor = ingredient.clone();
            cursor.setAmount(moved);
        } else {
            cursor.setAmount(cursor.getAmount() + moved);
        }
        ingredient.setAmount(ingredient.getAmount() - moved);
        if (ingredient.getAmount() <= 0) matrix[slot] = null;
        inventory.setMatrix(matrix);
        inventory.setResult(copy(session.input));
        event.setCursor(cursor);

        if (allEmpty(inventory.getMatrix())) refreshPreview(inventory, session);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!openTables.containsValue(event.getView().getTopInventory())) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (Map.Entry<UUID, CraftingInventory> entry : openTables.entrySet()) {
            if (entry.getValue() != event.getInventory()) continue;
            UncraftSession session = sessions.get(entry.getKey());
            if (session != null) event.getInventory().setResult(copy(session.input));
            return;
        }
    }

    private void refreshPreview(CraftingInventory inventory, UncraftSession session) {
        ItemStack[] matrix = new ItemStack[9];
        UncraftResult result = findUncraftResult(session.input);
        session.committed = false;
        if (result != null) {
            for (int i = 0; i < result.ingredients.size(); i++) {
                matrix[i] = result.ingredients.get(i).clone();
            }
        }
        inventory.setMatrix(matrix);
        inventory.setResult(copy(session.input));
    }

    private boolean allEmpty(ItemStack[] items) {
        for (ItemStack item : items) if (!isEmpty(item)) return false;
        return true;
    }

    private UncraftResult findUncraftResult(ItemStack input) {
        if (isEmpty(input)) return null;
        if (isHybrid(input)) return hybridResult(input);

        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (!(recipe instanceof CraftingRecipe craftingRecipe)) continue;
            ItemStack crafted = craftingRecipe.getResult();
            if (crafted == null || !input.isSimilar(crafted) ||
                input.getAmount() < crafted.getAmount()) continue;
            List<ItemStack> ingredients = recipeIngredients(craftingRecipe);
            if (ingredients != null && !ingredients.isEmpty()) {
                return new UncraftResult(crafted.getAmount(), ingredients);
            }
        }
        return null;
    }

    private boolean isHybrid(ItemStack input) {
        return input != null && input.getType().name().endsWith("_CHESTPLATE") &&
            input.hasItemMeta() &&
            input.getItemMeta().getPersistentDataContainer().has(hybridKey, PersistentDataType.BYTE);
    }

    private UncraftResult hybridResult(ItemStack input) {
        ItemStack chestplate = input.clone();
        ItemMeta meta = chestplate.getItemMeta();
        meta.getPersistentDataContainer().remove(hybridKey);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.remove(ChatColor.AQUA + "Elytra");
        meta.setLore(lore.isEmpty() ? null : lore);
        chestplate.setItemMeta(meta);
        return new UncraftResult(1, List.of(chestplate, new ItemStack(Material.ELYTRA)));
    }

    private List<ItemStack> recipeIngredients(CraftingRecipe recipe) {
        List<ItemStack> ingredients = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            for (String row : shaped.getShape()) {
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (symbol == ' ') continue;
                    RecipeChoice choice = shaped.getChoiceMap().get(symbol);
                    ItemStack ingredient = choice == null ? null : choice.getItemStack();
                    if (isEmpty(ingredient)) return null;
                    ingredients.add(ingredient.clone());
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                ItemStack ingredient = choice.getItemStack();
                if (isEmpty(ingredient)) return null;
                ingredients.add(ingredient.clone());
            }
        } else {
            return null;
        }
        return ingredients.size() <= 9 ? ingredients : null;
    }

    private ItemStack copy(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private void returnItem(Player player, ItemStack item) {
        if (isEmpty(item)) return;
        for (ItemStack leftover : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        CraftingInventory inventory = openTables.remove(playerId);
        UncraftSession session = sessions.remove(playerId);
        if (inventory == null || session == null || event.getView().getTopInventory() != inventory) return;

        ItemStack[] matrix = inventory.getMatrix();
        inventory.setMatrix(new ItemStack[9]);
        inventory.setResult(null);
        returnItem(player, session.input);
        if (session.committed) {
            for (ItemStack ingredient : matrix) returnItem(player, ingredient);
        }
    }

    private record UncraftResult(int inputAmount, List<ItemStack> ingredients) {}

    private static final class UncraftSession {
        private ItemStack input;
        private boolean committed;
    }
}
