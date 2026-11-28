package nuper.eagler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public final class EaglerEnhancementsPlugin extends JavaPlugin implements Listener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double LOGIN_X = 0.5;
    private static final double LOGIN_Y = 200.0;
    private static final double LOGIN_Z = 0.5;
    private final Set<UUID> unauthenticated = Collections.synchronizedSet(new HashSet<>());
    private final Set<UUID> sanctuaryInside = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    private final Map<UUID, BossBar> hudBars = new HashMap<>();
    private final List<Sanctuary> sanctuaries = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private File accountDirectory;
    private File accountDataFile;
    private NamespacedKey customItemKey;
    private NamespacedKey extraHeartsKey;
    private boolean authEnabled = true;
    private boolean spawnSanctuaryEnabled = true;
    private int defaultSanctuaryRadius = 11;
    private BukkitTask periodTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        accountDirectory = new File(getDataFolder(), "accounts");
        accountDirectory.mkdirs();
        accountDataFile = new File(getDataFolder(), "state.json");
        customItemKey = new NamespacedKey(this, "custom_item");
        extraHeartsKey = new NamespacedKey(this, "extra_hearts");
        loadState();
        ensureLoginPlatform();
        registerCustomRecipes();

        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();

        periodTask = Bukkit.getScheduler().runTaskTimer(this, this::periodicTick, 20L, 10L);
        getLogger().info("EaglerEnhancements enabled. Authentication is " +
            (authEnabled ? "ENABLED" : "DISABLED") +
            "; default spawn sanctuary is " + (spawnSanctuaryEnabled ? "ENABLED" : "DISABLED") + ".");
    }

    @Override
    public void onDisable() {
        if (periodTask != null) periodTask.cancel();
        saveState();
        Bukkit.removeRecipe(new NamespacedKey(this, "shard_of_life"));
        Bukkit.removeRecipe(new NamespacedKey(this, "heart_of_undying"));
        for (Player player : Bukkit.getOnlinePlayers()) {
            unauthenticated.remove(player.getUniqueId());
            player.sendActionBar("");
        }
        hudBars.values().forEach(BossBar::removeAll);
        hudBars.clear();
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("auth")).setExecutor(this::authCommand);
        Objects.requireNonNull(getCommand("register")).setExecutor(this::registerCommand);
        Objects.requireNonNull(getCommand("login")).setExecutor(this::loginCommand);
        Objects.requireNonNull(getCommand("changepassword")).setExecutor(this::changePasswordCommand);
        Objects.requireNonNull(getCommand("spawnprotect")).setExecutor(this::spawnProtectCommand);
        Objects.requireNonNull(getCommand("sanctuary")).setExecutor(this::sanctuaryCommand);
        Objects.requireNonNull(getCommand("removesanctuary")).setExecutor(this::removeSanctuaryCommand);
    }

    private void registerCustomRecipes() {
        NamespacedKey shardKey = new NamespacedKey(this, "shard_of_life");
        NamespacedKey heartKey = new NamespacedKey(this, "heart_of_undying");
        Bukkit.removeRecipe(shardKey);
        Bukkit.removeRecipe(heartKey);

        ShapedRecipe shardRecipe = new ShapedRecipe(shardKey,
            createCustomItem("shard_of_life", "Shard of Life", ChatColor.AQUA));
        shardRecipe.shape("TTT", "TTT", "TTT");
        shardRecipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
        Bukkit.addRecipe(shardRecipe);

        ShapedRecipe heartRecipe = new ShapedRecipe(heartKey,
            createCustomItem("heart_of_undying", "Heart of Undying", ChatColor.LIGHT_PURPLE));
        heartRecipe.shape("SSS", "SSS", "SSS");
        heartRecipe.setIngredient('S', new RecipeChoice.ExactChoice(
            createCustomItem("shard_of_life", "Shard of Life", ChatColor.AQUA)));
        Bukkit.addRecipe(heartRecipe);
    }

    private ItemStack createCustomItem(String id, String name, ChatColor color) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color + name);
        meta.getPersistentDataContainer().set(customItemKey, PersistentDataType.STRING, id);
        if ("heart_of_undying".equals(id)) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCustomItem(ItemStack item, String id) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return false;
        return id.equals(item.getItemMeta().getPersistentDataContainer()
            .get(customItemKey, PersistentDataType.STRING));
    }

    private int extraHearts(Player player) {
        return player.getPersistentDataContainer().getOrDefault(extraHeartsKey, PersistentDataType.INTEGER, 0);
    }

    private void applyExtraHearts(Player player) {
        int extraHearts = extraHearts(player);
        if (extraHearts == 0) return;
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            getLogger().severe("Could not restore extra hearts for " + player.getName() + ": max-health attribute is missing.");
            return;
        }
        maxHealth.setBaseValue(20.0 + extraHearts * 2.0);
    }

    private void resetExtraHearts(Player player) {
        player.getPersistentDataContainer().remove(extraHeartsKey);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            getLogger().severe("Could not reset extra hearts for " + player.getName() + ": max-health attribute is missing.");
            return;
        }
        maxHealth.setBaseValue(20.0);
    }

    private void loadState() {
        authEnabled = getConfig().getBoolean("authentication.enabled", true);
        spawnSanctuaryEnabled = getConfig().getBoolean("sanctuary.spawn-enabled", true);
        defaultSanctuaryRadius = getConfig().getInt("sanctuary.default-radius", 11);
        sanctuaries.clear();

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "state.yml"));
        ConfigurationSection list = cfg.getConfigurationSection("custom-sanctuaries");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(key);
                if (s == null) continue;
                World world = Bukkit.getWorld(Objects.toString(s.getString("world"), ""));
                if (world != null) {
                    sanctuaries.add(new Sanctuary(world, s.getDouble("x"), s.getInt("y"), s.getDouble("z"),
                        s.getInt("radius"), false));
                }
            }
        }
    }

    private void saveState() {
        getConfig().set("authentication.enabled", authEnabled);
        getConfig().set("sanctuary.spawn-enabled", spawnSanctuaryEnabled);
        getConfig().set("sanctuary.default-radius", defaultSanctuaryRadius);
        saveConfig();

        YamlConfiguration cfg = new YamlConfiguration();
        int i = 0;
        for (Sanctuary sanctuary : sanctuaries) {
            String key = "sanctuary-" + i++;
            cfg.set("custom-sanctuaries." + key + ".world", sanctuary.world.getName());
            cfg.set("custom-sanctuaries." + key + ".x", sanctuary.x);
            cfg.set("custom-sanctuaries." + key + ".y", sanctuary.y);
            cfg.set("custom-sanctuaries." + key + ".z", sanctuary.z);
            cfg.set("custom-sanctuaries." + key + ".radius", sanctuary.radius);
        }
        try {
            cfg.save(new File(getDataFolder(), "state.yml"));
        } catch (Exception e) {
            getLogger().warning("Unable to save sanctuary state: " + e.getMessage());
        }
    }

    private AccountData account(Player player) {
        return accounts.computeIfAbsent(player.getUniqueId(), uuid -> loadAccount(uuid, player.getName()));
    }

    private File accountFile(UUID uuid) {
        return new File(accountDirectory, uuid.toString() + ".json");
    }

    private AccountData loadAccount(UUID uuid, String name) {
        File file = accountFile(uuid);
        if (!file.exists()) return new AccountData(name);
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            AccountData data = GSON.fromJson(reader, AccountData.class);
            if (data == null) return new AccountData(name);
            data.name = name;
            return data;
        } catch (Exception e) {
            getLogger().warning("Unable to load account for " + name + ": " + e.getMessage());
            return new AccountData(name);
        }
    }

    private void saveAccount(UUID uuid, AccountData data) {
        try (Writer writer = Files.newBufferedWriter(accountFile(uuid).toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            getLogger().warning("Unable to save account for " + data.name + ": " + e.getMessage());
        }
    }

    private void ensureLoginPlatform() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) return;
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (x * x + z * z <= 25) {
                    world.getBlockAt(x, 199, z).setType(Material.NETHERITE_BLOCK, false);
                    for (int y = 200; y <= 209; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                boolean edge = Math.abs(x) == 5 || Math.abs(z) == 5;
                if (edge) {
                    for (int y = 200; y <= 209; y++) world.getBlockAt(x, y, z).setType(Material.GLASS, false);
                }
            }
        }
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (x * x + z * z <= 25) world.getBlockAt(x, 210, z).setType(Material.GLASS, false);
            }
        }
    }

    private boolean isLoginPlatform(Location location) {
        return location.getWorld() != null && location.getWorld().equals(Bukkit.getWorlds().getFirst())
            && location.getBlockX() >= -5 && location.getBlockX() <= 5
            && location.getBlockZ() >= -5 && location.getBlockZ() <= 5
            && location.getBlockY() >= 199 && location.getBlockY() <= 210;
    }

    private boolean isAuthenticated(Player player) {
        return !authEnabled || !unauthenticated.contains(player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) applyExtraHearts(player);
        });
        unauthenticated.remove(player.getUniqueId());
        if (!authEnabled) return;

        AccountData data = account(player);
        if (data.previousLocation == null) {
            data.previousLocation = serialiseLocation(player.getLocation());
        }
        unauthenticated.add(player.getUniqueId());
        player.setFallDistance(0.0f);
        player.setAllowFlight(false);
        player.teleport(new Location(Bukkit.getWorlds().getFirst(), LOGIN_X, LOGIN_Y, LOGIN_Z));

        if (data.hash == null) {
            red(player, "You must register before playing!");
            red(player, "Use /register <password> <confirm-password>");
        } else {
            red(player, "Authentication required. Enter your password to continue.");
            red(player, "Use /login <password>");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player) && !isLoginPlatform(player.getLocation())) {
            AccountData data = account(player);
            data.previousLocation = serialiseLocation(player.getLocation());
            saveAccount(player.getUniqueId(), data);
        }
        unauthenticated.remove(player.getUniqueId());
        sanctuaryInside.remove(player.getUniqueId());
        BossBar hudBar = hudBars.remove(player.getUniqueId());
        if (hudBar != null) hudBar.removeAll();
    }

    private boolean authCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            redSender(sender, "Only authorised OP players may use /auth.");
            return true;
        }
        if (args.length == 0) {
            redSender(sender, "Usage: /auth enable | disable | status | reset <player>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "enable" -> {
                authEnabled = true;
                saveState();
                ensureLoginPlatform();
                green(sender, "Authentication ENABLED. Players must authenticate before playing.");
            }
            case "disable" -> {
                authEnabled = false;
                unauthenticated.clear();
                saveState();
                green(sender, "Authentication DISABLED. Existing registrations were preserved.");
            }
            case "status" -> sender.sendMessage(ChatColor.GOLD + "Authentication is " +
                (authEnabled ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));
            case "reset" -> {
                if (args.length != 2) {
                    redSender(sender, "Usage: /auth reset <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                UUID uuid = target != null ? target.getUniqueId()
                    : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                AccountData data = target != null ? account(target) : loadAccount(uuid, args[1]);
                data.hash = null;
                data.salt = null;
                data.previousLocation = null;
                accounts.put(uuid, data);
                saveAccount(uuid, data);
                unauthenticated.remove(uuid);
                if (target != null && authEnabled) {
                    unauthenticated.add(uuid);
                    red(target, "Your registration was reset. Register again with /register <password> <confirm-password>");
                    target.teleport(new Location(Bukkit.getWorlds().getFirst(), LOGIN_X, LOGIN_Y, LOGIN_Z));
                }
                green(sender, "Password registration reset for " + args[1] + ".");
            }
            default -> redSender(sender, "Usage: /auth enable | disable | status | reset <player>");
        }
        return true;
    }

    private boolean registerCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                    String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!authEnabled) {
            red(player, "Authentication is disabled.");
            return true;
        }
        if (!unauthenticated.contains(player.getUniqueId())) {
            red(player, "You are already authenticated.");
            return true;
        }
        AccountData data = account(player);
        if (data.hash != null) {
            red(player, "This account is already registered. Use /login <password>");
            return true;
        }
        if (args.length != 2) {
            red(player, "Usage: /register <password> <confirm-password>");
            return true;
        }
        String password = args[0];
        if (!validPassword(password) || !password.equals(args[1])) {
            red(player, "Passwords must be 1-30 characters and both entries must match.");
            return true;
        }
        PasswordHash hash = hashPassword(password);
        data.hash = hash.hash;
        data.salt = hash.salt;
        saveAccount(player.getUniqueId(), data);
        completeAuthentication(player);
        green(player, "Registration success!");
        return true;
    }

    private boolean loginCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                 String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!authEnabled) return true;
        if (!unauthenticated.contains(player.getUniqueId())) {
            red(player, "You are already authenticated.");
            return true;
        }
        AccountData data = account(player);
        if (data.hash == null) {
            red(player, "You must register before playing!");
            return true;
        }
        if (args.length != 1) {
            red(player, "Usage: /login <password>");
            return true;
        }
        if (!verifyPassword(args[0], data)) {
            red(player, "Incorrect password. Please try again.");
            return true;
        }
        completeAuthentication(player);
        green(player, "Welcome back!");
        return true;
    }

    private boolean changePasswordCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                          String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!isAuthenticated(player)) {
            red(player, "You must authenticate before changing your password.");
            return true;
        }
        if (args.length != 2) {
            red(player, "Usage: /changepassword <old-password> <new-password>");
            return true;
        }
        AccountData data = account(player);
        if (data.hash == null || !verifyPassword(args[0], data)) {
            red(player, "Incorrect old password.");
            return true;
        }
        if (!validPassword(args[1])) {
            red(player, "The new password must be 1-30 characters.");
            return true;
        }
        PasswordHash hash = hashPassword(args[1]);
        data.hash = hash.hash;
        data.salt = hash.salt;
        saveAccount(player.getUniqueId(), data);
        green(player, "Password changed successfully.");
        return true;
    }

    private void completeAuthentication(Player player) {
        player.setFallDistance(0.0f);
        AccountData data = account(player);
        Location destination = deserialiseLocation(data.previousLocation);
        if (destination == null) destination = player.getServer().getWorlds().getFirst().getSpawnLocation();
        if (!destination.getBlock().isLiquid() && destination.getWorld() != null) {
            destination = destination.clone();
        } else {
            destination = destination.getWorld() != null
                ? destination.getWorld().getHighestBlockAt(destination).getLocation().add(0.5, 1, 0.5)
                : player.getServer().getWorlds().getFirst().getSpawnLocation();
        }
        Location returnLocation = destination;
        int[] progress = {0};
        player.sendTitle("", "Remembering where you left off... 0%", 0, 10, 0);
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                task[0].cancel();
                return;
            }
            if (progress[0] < 100) {
                progress[0] = Math.min(progress[0] + 5, 100);
                player.sendTitle("", "Remembering where you left off... " + progress[0] + "%", 0, 10, 0);
                return;
            }
            task[0].cancel();
            player.resetTitle();
            unauthenticated.remove(player.getUniqueId());
            player.setFallDistance(0.0f);
            player.teleport(returnLocation);
        }, 5L, 5L);
    }

    private boolean validPassword(String password) {
        return password != null && password.codePointCount(0, password.length()) >= 1
            && password.codePointCount(0, password.length()) <= 30;
    }

    private PasswordHash hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt).withIterations(3).withMemoryPowOfTwo(16).withParallelism(1).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] hash = new byte[32];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), hash);
        return new PasswordHash(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash));
    }

    private boolean verifyPassword(String password, AccountData data) {
        if (data == null || data.hash == null || data.salt == null) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(data.salt);
            byte[] expected = Base64.getDecoder().decode(data.hash);
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withIterations(3).withMemoryPowOfTwo(16).withParallelism(1).build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            byte[] actual = new byte[expected.length];
            generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), actual);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private String serialiseLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return location.getWorld().getName() + "|" + location.getX() + "|" + location.getY() + "|" +
            location.getZ() + "|" + location.getYaw() + "|" + location.getPitch();
    }

    private Location deserialiseLocation(String value) {
        if (value == null) return null;
        String[] p = value.split("\\|");
        if (p.length != 6) return null;
        World world = Bukkit.getWorld(p[0]);
        try {
            return world == null ? null : new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean spawnProtectCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                        String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            redSender(sender, "Only authorised OP players may use /spawnprotect.");
            return true;
        }
        spawnSanctuaryEnabled = !spawnSanctuaryEnabled;
        saveState();
        if (!spawnSanctuaryEnabled) sanctuaryInside.clear();
        green(sender, "Default spawn sanctuary " + (spawnSanctuaryEnabled ? "ENABLED" : "DISABLED") +
            " (radius " + defaultSanctuaryRadius + ").");
        return true;
    }

    private boolean sanctuaryCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                     String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            redSender(sender, "Only authorised OP players may use /sanctuary.");
            return true;
        }
        if (args.length != 2 || !"r".equalsIgnoreCase(args[0])) {
            redSender(sender, "Usage: /sanctuary r <positive-integer>");
            return true;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (Exception e) {
            redSender(sender, "Radius must be a positive integer.");
            return true;
        }
        if (radius <= 0) {
            redSender(sender, "Radius must be a positive integer.");
            return true;
        }
        Location center = player.getLocation();
        Sanctuary candidate = new Sanctuary(center.getWorld(), center.getBlockX() + 0.5,
            center.getBlockY(), center.getBlockZ() + 0.5, radius, false);
        List<Sanctuary> active = activeSanctuaries(candidate.world);
        for (Sanctuary existing : active) {
            double distance = Math.sqrt(
                Math.pow(candidate.x - existing.x, 2) +
                Math.pow(candidate.y - existing.y, 2) +
                Math.pow(candidate.z - existing.z, 2));
            if (distance < candidate.radius + existing.radius) {
                red(player, "This sanctuary would overlap an existing sanctuary. Choose another location.");
                return true;
            }
        }
        sanctuaries.add(candidate);
        saveState();
        green(player, "Sanctuary created at (" + center.getBlockX() + ", " + center.getBlockY() + ", " +
            center.getBlockZ() + ") with radius " + radius + ".");
        return true;
    }

    private boolean removeSanctuaryCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                                           String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            redSender(sender, "Only authorised OP players may use /removesanctuary.");
            return true;
        }

        Location location = player.getLocation();
        Sanctuary spawnSanctuary = defaultSanctuary();
        boolean removeSpawnSanctuary = spawnSanctuary != null && spawnSanctuary.contains(location);
        int previousCount = sanctuaries.size();
        sanctuaries.removeIf(sanctuary -> sanctuary.contains(location));
        int removedCount = previousCount - sanctuaries.size();
        if (removeSpawnSanctuary) {
            spawnSanctuaryEnabled = false;
            removedCount++;
        }
        if (removedCount == 0) {
            red(player, "You are not standing inside a sanctuary.");
            return true;
        }

        saveState();
        green(player, "Removed " + removedCount + (removedCount == 1 ? " sanctuary." : " sanctuaries."));
        return true;
    }

    private Sanctuary defaultSanctuary() {
        if (!spawnSanctuaryEnabled) return null;
        Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        return new Sanctuary(spawn.getWorld(), spawn.getBlockX() + 0.5, spawn.getBlockY(),
            spawn.getBlockZ() + 0.5, defaultSanctuaryRadius, true);
    }

    private List<Sanctuary> activeSanctuaries(World world) {
        List<Sanctuary> active = new ArrayList<>();
        Sanctuary defaultSanctuary = defaultSanctuary();
        if (defaultSanctuary != null && Objects.equals(defaultSanctuary.world, world)) active.add(defaultSanctuary);
        for (Sanctuary s : sanctuaries) if (Objects.equals(s.world, world)) active.add(s);
        return active;
    }

    private boolean inAnySanctuary(Location location) {
        if (location == null || location.getWorld() == null) return false;
        for (Sanctuary s : activeSanctuaries(location.getWorld())) {
            if (s.contains(location)) return true;
        }
        return false;
    }

    private void periodicTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isAuthenticated(player)) {
                hideHud(player);
                continue;
            }
            if (inAnySanctuary(player.getLocation())) {
                player.setHealth(Math.min(player.getMaxHealth(), player.getMaxHealth()));
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
                player.sendActionBar(ChatColor.GOLD + "Sanctuary of Protection");
                sanctuaryInside.add(player.getUniqueId());
            } else {
                if (sanctuaryInside.remove(player.getUniqueId())) {
                    player.sendActionBar("");
                }
            }
            sendHud(player);
        }
    }

    private void sendHud(Player player) {
        Location location = player.getLocation();
        long time = (location.getWorld().getTime() + 6000L) % 24000L;
        int hours = (int) (time / 1000L);
        int minutes = (int) ((time % 1000L) * 60L / 1000L);
        String biome = location.getBlock().getBiome().getKey().getKey().replace('_', ' ');
        String text = String.format(Locale.ROOT, "Time: %02d:%02d  |  Biome: %s  |  XYZ: %d, %d, %d",
            hours, minutes, toTitleCase(biome),
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BossBar hudBar = hudBars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BossBar bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
            bar.setProgress(0.0);
            bar.addPlayer(player);
            return bar;
        });
        hudBar.setTitle(ChatColor.AQUA + text);
    }

    private void hideHud(Player player) {
        BossBar hudBar = hudBars.remove(player.getUniqueId());
        if (hudBar != null) hudBar.removeAll();
    }

    private String toTitleCase(String value) {
        String[] words = value.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.toString();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isAuthenticated(player)) {
            event.setTo(event.getFrom());
            player.setFallDistance(0.0f);
            if (!isLoginPlatform(event.getTo())) {
                player.teleport(new Location(Bukkit.getWorlds().getFirst(), LOGIN_X, LOGIN_Y, LOGIN_Z));
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isAuthenticated(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isAuthenticated(event.getPlayer())) return;
        String command = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!command.startsWith("/register") && !command.startsWith("/login") &&
            !command.startsWith("/changepassword") && !command.startsWith("/auth")) {
            event.setCancelled(true);
            red(event.getPlayer(), "Authentication required.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isAuthenticated(event.getPlayer()) || isLoginPlatform(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isAuthenticated(event.getPlayer()) || isLoginPlatform(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isAuthenticated(player) || (event.getClickedBlock() != null &&
            isLoginPlatform(event.getClickedBlock().getLocation()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || !isCustomItem(event.getItem(), "heart_of_undying") ||
            (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            getLogger().severe("Could not grant an extra heart to " + player.getName() + ": max-health attribute is missing.");
            return;
        }

        event.setCancelled(true);
        int hearts = extraHearts(player) + 1;
        player.getPersistentDataContainer().set(extraHeartsKey, PersistentDataType.INTEGER, hearts);
        maxHealth.setBaseValue(20.0 + hearts * 2.0);
        player.setHealth(Math.min(maxHealth.getValue(), player.getHealth() + 2.0));

        ItemStack item = event.getItem();
        if (item.getAmount() == 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
        player.sendMessage(ChatColor.LIGHT_PURPLE + "You gained an extra heart. It will be lost when you die.");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        resetExtraHearts(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            resetExtraHearts(player);
            player.setHealth(20.0);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isAuthenticated(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!isAuthenticated(player)) event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            if (!isAuthenticated(victim)) {
                event.setCancelled(true);
                return;
            }
            Player attacker = directOrProjectilePlayer(event.getDamager());
            if (attacker != null && inAnySanctuary(victim.getLocation())) event.setCancelled(true);
        }
    }

    private Player directOrProjectilePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isLoginPlatform(event.getBlock().getLocation())) event.setCancelled(true);
        for (Block block : event.getBlocks()) if (isLoginPlatform(block.getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isLoginPlatform(event.getBlock().getLocation())) event.setCancelled(true);
        for (Block block : event.getBlocks()) if (isLoginPlatform(block.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (isLoginPlatform(block.getLocation()) || inAnySanctuary(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isLoginPlatform(event.getBlock().getLocation()) || inAnySanctuary(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Monster && inAnySanctuary(entity.getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (entity instanceof Monster && inAnySanctuary(event.getTo())) {
            event.setCancelled(true);
            entity.setVelocity(entity.getVelocity().multiply(0));
        }
    }

    private void red(Player player, String message) {
        player.sendMessage(ChatColor.RED + message);
    }

    private void green(Player player, String message) {
        player.sendMessage(ChatColor.GREEN + message);
    }

    private void redSender(org.bukkit.command.CommandSender sender, String message) {
        sender.sendMessage(ChatColor.RED + message);
    }

    private void green(org.bukkit.command.CommandSender sender, String message) {
        sender.sendMessage(ChatColor.GREEN + message);
    }

    private record PasswordHash(String salt, String hash) {}

    private static final class AccountData {
        private String name;
        private String salt;
        private String hash;
        private String previousLocation;

        private AccountData() {}
        private AccountData(String name) { this.name = name; }
    }

    private record Sanctuary(World world, double x, int y, double z, int radius, boolean defaultSanctuary) {
        private boolean contains(Location location) {
            if (location == null || location.getWorld() == null || !location.getWorld().equals(world)) return false;
            double dy = location.getY() - y;
            if (dy < 0) return false;
            double dx = location.getX() - x;
            double dz = location.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
        }
    }
}
