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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class UncraftingTablePlugin extends JavaPlugin implements Listener {
    private static final String TABLE_NAME = "Uncrafting Table";
    private static final int INPUT_SLOT = 22;
    private static final int[] OUTPUT_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    private final Set<String> registeredTables = new LinkedHashSet<>();
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
        openTable(event.getPlayer());
    }

    private void openTable(Player player) {
        TableHolder holder = new TableHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_PURPLE + TABLE_NAME);
        holder.inventory = inventory;
        inventory.setItem(INPUT_SLOT, null);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TableHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == INPUT_SLOT) {
            moveCursorToInput(event, holder.inventory);
            refreshPreview(holder.inventory);
        } else if (isOutputSlot(slot)) {
            uncraftOne(player, holder.inventory);
        }
    }

    private void moveCursorToInput(InventoryClickEvent event, Inventory inventory) {
        if (event.getClick().isKeyboardClick() || event.getClick().isCreativeAction()) return;

        ItemStack cursor = copy(event.getCursor());
        ItemStack input = copy(inventory.getItem(INPUT_SLOT));
        boolean rightClick = event.isRightClick();

        if (isEmpty(cursor) && !isEmpty(input)) {
            int amount = rightClick ? (input.getAmount() + 1) / 2 : input.getAmount();
            ItemStack taken = input.clone();
            taken.setAmount(amount);
            input.setAmount(input.getAmount() - amount);
            if (input.getAmount() <= 0) input = null;
            cursor = taken;
        } else if (!isEmpty(cursor) && isEmpty(input)) {
            int amount = rightClick ? 1 : cursor.getAmount();
            input = cursor.clone();
            input.setAmount(amount);
            cursor.setAmount(cursor.getAmount() - amount);
            if (cursor.getAmount() <= 0) cursor = null;
        } else if (!isEmpty(cursor) && !isEmpty(input) && cursor.isSimilar(input)) {
            int amount = rightClick ? 1 : cursor.getAmount();
            int moved = Math.min(amount, input.getMaxStackSize() - input.getAmount());
            input.setAmount(input.getAmount() + moved);
            cursor.setAmount(cursor.getAmount() - moved);
            if (cursor.getAmount() <= 0) cursor = null;
        } else if (!isEmpty(cursor) && !isEmpty(input) && !rightClick) {
            ItemStack swap = input;
            input = cursor;
            cursor = swap;
        }

        inventory.setItem(INPUT_SLOT, input);
        event.setCursor(cursor);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TableHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private void refreshPreview(Inventory inventory) {
        clearPreview(inventory);
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (isEmpty(input)) return;

        UncraftResult result = findUncraftResult(input);
        if (result == null) {
            inventory.setItem(4, named(Material.BARRIER, ChatColor.RED + "No known crafting recipe"));
            return;
        }

        for (int i = 0; i < Math.min(result.ingredients.size(), OUTPUT_SLOTS.length); i++) {
            inventory.setItem(OUTPUT_SLOTS[i], result.ingredients.get(i).clone());
        }
    }

    private void clearPreview(Inventory inventory) {
        for (int slot : OUTPUT_SLOTS) inventory.setItem(slot, null);
    }

    private boolean isOutputSlot(int slot) {
        for (int outputSlot : OUTPUT_SLOTS) if (slot == outputSlot) return true;
        return false;
    }

    private void uncraftOne(Player player, Inventory inventory) {
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (isEmpty(input)) return;
        UncraftResult result = findUncraftResult(input);
        if (result == null) return;

        input.setAmount(input.getAmount() - result.inputAmount);
        if (input.getAmount() <= 0) inventory.setItem(INPUT_SLOT, null);
        else inventory.setItem(INPUT_SLOT, input);

        for (ItemStack ingredient : result.ingredients) {
            for (ItemStack leftover : player.getInventory().addItem(ingredient.clone()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        refreshPreview(inventory);
    }

    private UncraftResult findUncraftResult(ItemStack input) {
        if (isHybrid(input)) return hybridResult(input);

        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (!(recipe instanceof CraftingRecipe craftingRecipe)) continue;
            ItemStack crafted = craftingRecipe.getResult();
            if (crafted == null || !input.isSimilar(crafted) || input.getAmount() < crafted.getAmount()) continue;
            List<ItemStack> ingredients = recipeIngredients(craftingRecipe);
            if (ingredients != null && !ingredients.isEmpty()) {
                return new UncraftResult(crafted.getAmount(), ingredients);
            }
        }
        return null;
    }

    private boolean isHybrid(ItemStack input) {
        return input.getType().name().endsWith("_CHESTPLATE") && input.hasItemMeta() &&
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

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack copy(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TableHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        ItemStack input = holder.inventory.getItem(INPUT_SLOT);
        if (isEmpty(input)) return;
        for (ItemStack leftover : player.getInventory().addItem(input.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        holder.inventory.setItem(INPUT_SLOT, null);
    }

    private record UncraftResult(int inputAmount, List<ItemStack> ingredients) {}

    private static final class TableHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
