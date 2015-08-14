package net.cubebeaters;

// Local imports for CBTokens
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.md_5.bungee.api.ChatColor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import static org.bukkit.Bukkit.getScheduler;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 *
 * This Plugin is used for specifically the CubeBeaters server and is used as a
 * reward system for players simply logging onto the server at least once per
 * day. This plugin does this by rewarding tokens upon joining the server if the
 * amount of time that has passed since their last token gain was 24+ hours.
 * Players will at the beginning only be able to redeem tokens for cash. Later
 * on, though, players will be able to redeem tokens for custom items and armor
 * in addition to redeeming for money.
 *
 * @author Systemx86
 * @version 1.3
 */
public class CBTokens extends JavaPlugin implements Listener {

    // MSG_PREFIX is to be used when sending messages to console and players.
    private String MSG_PREFIX = ChatColor.GOLD + "[" + ChatColor.BLUE + "CB" + ChatColor.DARK_GREEN + "Tokens" + ChatColor.GOLD + "] " + ChatColor.WHITE;
    // This is the menu for the store to redeem tokens.
    @SuppressWarnings("FieldMayBeFinal")
    private final Inventory storeMenu = Bukkit.createInventory(null, 18, ChatColor.GOLD + "Token Store"); // Creats GUI Menu
    private final Inventory moneyMenu = Bukkit.createInventory(null, 18, ChatColor.GOLD + "Token Store - Money");
    private final Inventory rankMenu = Bukkit.createInventory(null, 18, ChatColor.GOLD + "Token Store - Ranks");
    private final Inventory expMenu = Bukkit.createInventory(null, 18, ChatColor.GOLD + "Token Store - Experience");
    private final Inventory abilitiesMenu = Bukkit.createInventory(null, 18, ChatColor.GOLD + "Token Store - Abilities");
    private Economy econ = null; // Creates an Economy hook to deposit currency upon call.
    private Permission perms; // Creates permissions hook to add ranks to players.
    private final boolean useSQL = this.getConfig().getBoolean("SQL-Info.Enabled");
    private final boolean enablePerms = this.getConfig().getBoolean("ExtraSettings.EnablePerms");
    private final boolean enableExp = this.getConfig().getBoolean("ExtraSettings.EnableExpShop");
    private final boolean enableMoney = this.getConfig().getBoolean("ExtraSettings.EnableMoney");
    private final boolean customPerms = this.getConfig().getBoolean("ExtraSettings.customPerms");
    private Connection con; // SQL connection.
    private String host, port, database, username, password; // SQL connections info
    private Statement statement; // Used for executing SQL commands.
    @SuppressWarnings("FieldMayBeFinal")
    private FileConfiguration tokenStorage = null; // For FlatFile saving method.
    @SuppressWarnings("FieldMayBeFinal")
    private File tokensFile = null; // For FlatFile saving method.
    @SuppressWarnings("FieldMayBeFinal")
    private int coolTime;
    @SuppressWarnings("FieldMayBeFinal")
    private HashMap<String, Integer> cooldowns = new HashMap<>();

    /*
     TODO: Add Permission buying support.
     TODO: Fix Exp Bug where player XP bar goes off-screen
     */
    /**
     * Creates config and reloads when the plugin starts up!
     */
    @Override
    public void onEnable() {
        getLogger().info("Creating/Loading Configuration.");
        this.saveDefaultConfig();
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Events Registered.");
        if (useSQL) { // Start and use SQL if enabled in config.
            getLogger().info("Intializing SQL");
            host = this.getConfig().getString("SQL-Info.Host");
            port = this.getConfig().getString("SQL-Info.Port");
            database = this.getConfig().getString("SQL-Info.dbName");
            username = this.getConfig().getString("SQL-Info.Username");
            password = this.getConfig().getString("SQL-Info.Password");
            getLogger().log(Level.INFO, "{0}SQL Configuration loaded. Connecting...", ChatColor.YELLOW);
            try { // Connect to SQL DB while catching possible errors.
                openConnection();
                statement = con.createStatement();
            } catch (SQLException ex) {
                getLogger().log(Level.INFO, "{0}ERROR: SQL Connection", ChatColor.RED);
            } catch (ClassNotFoundException ex) {
                getLogger().log(Level.INFO, "{0}ERROR: ClassNotFoundException", ChatColor.RED);
            }
        } else {
            try {
                this.saveDefaultStorage(); // Save defaults of custom configuration.
                getLogger().info("Loaded Defaults for tokenStorage.yml");
            } catch (IOException ex) {
                Logger.getLogger(CBTokens.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (enableMoney) {
            getLogger().info("Setting up Economy Hook.");
            if (!setupEconomy()) {
                getLogger().info(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
                getServer().getPluginManager().disablePlugin(this);
            }
        } else {
            getLogger().info("Economy options have been disabled in the Config. They will not be used.");
        }
        if (enablePerms || customPerms) {
            if (!setupPermissions()) {
                getLogger().info(String.format("[%s] - Disabled due to no Vault dependency found!", getDescription().getName()));
                getServer().getPluginManager().disablePlugin(this);
            }
        } else {
            getLogger().info("Perms have been disabled in the Config. They will not be used.");
        }

        getLogger().info("Setting custom prefix...");
        MSG_PREFIX = ChatColor.translateAlternateColorCodes('&', this.getConfig().getString("Messages.CommandPrefix").replace("'", "").replace("'", ""));
        getLogger().info("Set. Continuing");

        coolTime = this.getConfig().getInt("DailyTokens.Settings.cooldown"); // Sets the cooldown timer from the Config
        // Bukkit Scheduler for Token Cooldowns.
        getScheduler().runTaskTimer(this, new BukkitRunnable() {
            @Override
            public void run() {
                for (String s : cooldowns.keySet()) {
                    cooldowns.put(s, cooldowns.get(s) - 1);
                }
            }
        }, 1 * 20, 1 * 20);
    }

    /**
     * Saves the config and stops the plugin and any other background processes
     * associated with running CBTokens.
     */
    @Override
    public void onDisable() {
        getLogger().log(Level.INFO, "{0}Saving configuration.", ChatColor.YELLOW);
        this.saveStorage(); // Save the storage configuration.
        this.saveConfig(); // Saves the Config.yml file.
        getLogger().log(Level.INFO, "{0}Saved. Stopping...", ChatColor.GREEN);
        if (useSQL) { // SQL is enabled in Configuration.
            try {
                con.close();
            } catch (SQLException ex) {
                Logger.getLogger(ex.toString());
            }
        }
        getLogger().log(Level.INFO, "{0}Stopped CBTokens", ChatColor.GREEN);
    }

    /**
     *
     * This method checks for a vault compatable Economy plugin then adds it to
     * the econ variable so that you can make deposits and perform various vault
     * actions using the economy that the server may have already installed.
     *
     * @return True if the variable "econ" is not null.
     */
    public boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    /**
     * This method sets up permissions so that this plugin can add
     * ranks/permissions to players that have purchased them with tokens.
     */
    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = rsp.getProvider();
        if (perms == null) {
            throw new IllegalStateException("No vault compatable permission plugins found.");
        }
        if (!perms.hasGroupSupport()) {
            throw new IllegalStateException("Your permissions plugin does not have group support.");
        }
        if (!perms.hasSuperPermsCompat()) {
            throw new IllegalStateException("Your permissions plugin does not have super perms compatability.");
        }

        return perms != null;

    }

    /**
     *
     * Reloads the Token Storage Configuration file.
     *
     * @throws UnsupportedEncodingException
     */
    @SuppressWarnings("null")
    public void reloadStorage() throws UnsupportedEncodingException {
        if (tokensFile == null) {
            tokensFile = new File(getDataFolder(), "tokenStorage.yml");
        }
        tokenStorage = YamlConfiguration.loadConfiguration(tokensFile);

        // Look for defaults in the jar
        Reader defConfigStream = new InputStreamReader(this.getResource("tokenStorage.yml"), "UTF8");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(defConfigStream);
            tokenStorage.setDefaults(defConfig);
        }
    }

    /**
     *
     * gets the config file for use.
     *
     * @return tokenStorage as the file Configuration.
     * @throws UnsupportedEncodingException if UTF-8 Encoding is not used.
     */
    public FileConfiguration getStorage() throws UnsupportedEncodingException {
        if (tokenStorage == null) {
            reloadStorage();
        }
        return tokenStorage;
    }

    /**
     * Saves the TokenStorage configuration file.
     */
    public void saveStorage() {
        if (tokenStorage == null || tokensFile == null) {
            return;
        }
        try {
            getStorage().save(tokensFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save config to " + tokensFile, ex);
        }
    }

    /**
     *
     * Saves the defaults of the tokenStorage.yml built into this plugin into
     * the tokenStorage.
     *
     * @throws IOException If UTF-8 Encoding is not used.
     */
    public void saveDefaultStorage() throws IOException {
        if (tokensFile == null) {
            tokensFile = new File(getDataFolder(), "tokenStorage.yml");
        }
        if (!tokensFile.exists()) {
            this.saveResource("tokenStorage.yml", false);
        }
    }

    /**
     *
     * This method opens a secure and safe connection to a MySQL database where
     * plugin information is to be stored.
     *
     * @throws SQLException Error in SQL connections
     * @throws ClassNotFoundException Connection class not found in
     * java.sql.Connection.
     */
    public void openConnection() throws SQLException, ClassNotFoundException {
        if (con != null && !con.isClosed()) {
            return;
        }

        Class.forName("com.mysql.jdbc.Driver");
        con = DriverManager.getConnection("jdbc:mysql://"
                + this.host + ":" + this.port + "/" + this.database,
                this.username, this.password);
    }

    /**
     *
     * Handels player join event. Gives player their daily CBToken.
     *
     * @param event The PlayerJoin Event.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (useSQL) {
            try {
                if (!(p.hasPlayedBefore())) {
                    statement.executeUpdate("INSERT INTO  " + database + "  (PLAYERNAME, AMOUNT) VALUES ('" + p.getUniqueId() + "', 5)");
                    p.sendMessage(MSG_PREFIX + this.getConfig().getString("Messages.First-Join").replace("$player", p.getName()).replace("$amount", "5"));
                } else {
                    statement.executeUpdate("UPDATE " + database + " SET AMOUNT = AMOUNT + 1 WHERE PLAYER = '" + p.getUniqueId() + "'");
                    p.sendMessage(MSG_PREFIX + "You have recieved your daily CB Token!");
                }
            } catch (SQLException ex) {
                Logger.getLogger(ex.toString());
                p.sendMessage(ChatColor.RED + "Error: SQL Connection error.");
            }
        } else {
            if (!(p.hasPlayedBefore())) {
                try {
                    this.getStorage().set(p.getUniqueId().toString(), this.getConfig().getInt("DailyTokens.firstjoin"));
                    this.saveStorage();
                } catch (UnsupportedEncodingException ex) {
                    Logger.getLogger(CBTokens.class.getName()).log(Level.SEVERE, null, ex);
                }
                p.sendMessage(MSG_PREFIX + ChatColor.translateAlternateColorCodes('&', this.getConfig().getString("Messages.First-Join").replace("$player", p.getName()).replace("$amount", this.getConfig().getString("DailyTokens.firstjoin"))));
            } else {
                int tokenAmount;
                int addAmount = 0;

                if (!p.isOp()) {
                    if (p.hasPermission("cbtokens.reward.rank1")) {
                        addAmount = this.getConfig().getInt("DailyTokens.rank1");
                    } else if (p.hasPermission("cbtokens.reward.rank2")) {
                        addAmount = this.getConfig().getInt("DailyTokens.rank2");
                    } else if (p.hasPermission("cbtokens.reward.rank3")) {
                        addAmount = this.getConfig().getInt("DailyTokens.rank3");
                    } else if (p.hasPermission("cbtokens.reward.rank4")) {
                        addAmount = this.getConfig().getInt("DailyTokens.rank4");
                    } else if (p.hasPermission("cbtokens.reward.rank5")) {
                        addAmount = this.getConfig().getInt("DailyTokens.rank5");
                    } else if (p.hasPermission("cbtokens.reward.default")) {
                        addAmount = this.getConfig().getInt("DailyTokens.default");
                    }
                } else {
                    addAmount = this.getConfig().getInt("DailyTokens.operator");
                }

                coolTime = this.getConfig().getInt("DailyTokens.cooldown");
                if (!cooldowns.containsKey(p.getName()) || cooldowns.get(p.getName()) <= 0) {
                    try {
                        tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());
                        this.getStorage().set(p.getUniqueId().toString(), tokenAmount + addAmount);
                        this.saveStorage();
                        Title msg = new Title("Recieved Tokens", "Recieved Daily Tokens (" + addAmount + ")");
                        msg.setStayTime(6);
                        msg.setTitleColor(org.bukkit.ChatColor.GOLD);
                        msg.setSubtitleColor(org.bukkit.ChatColor.GREEN);
                        msg.send(p);
                        cooldowns.put(p.getName(), coolTime);
                    } catch (UnsupportedEncodingException ex) {
                        Logger.getLogger(CBTokens.class.getName()).log(Level.SEVERE, null, ex);
                    }
                } else {
                    String coolTimer = convertTimeFormat(cooldowns.get(p.getName()));
                    p.sendMessage(MSG_PREFIX + "You will receive your daily tokens in " + ChatColor.AQUA + coolTimer + ".");
                }
            }
        }
    }

    /**
     *
     * This method converts a given time into the format HH:MM:SS
     *
     * @param time The total time in seconds left in the cooldown.
     * @return The converted string
     */
    public String convertTimeFormat(int time) {

        int hour = (int) (time / 3600);
        int min = (int) ((time - (hour * 3600)) / 60);
        int sec = (int) (time - ((hour * 3600)) - ((min * 60)));

        return hour + ":" + min + ":" + sec;
    }

    /**
     *
     * This method is a listener for when an Item in inventory gets clicked.
     * This method then executes code from the GUI interface once it has
     * determined that the clicked item is that of a menu item.
     *
     * @param e The Inventory click event.
     */
    @EventHandler
    public void onStoreClick(InventoryClickEvent e) {
        Player p = (Player) e.getWhoClicked(); // The player who clicked the item.
        Inventory inv = e.getInventory(); // The inventory that the item was clicked in.
        ItemStack clicked = e.getCurrentItem(); // The item that was clicked.

        try {
            if (inv.getName().equals(storeMenu.getName())) {
                if (clicked.getType() == Material.GOLD_INGOT) {
                    e.setCancelled(true);
                    createMoneyMenu();
                    p.openInventory(moneyMenu);
                } else if (clicked.getType() == Material.DIAMOND) {
                    e.setCancelled(true);
                    createRankMenu();
                    p.openInventory(rankMenu);
                } else if (clicked.getType() == Material.NETHER_STAR) {
                    e.setCancelled(true);
                    createExpMenu();
                    p.openInventory(expMenu);
                } else if (clicked.getType() == Material.FEATHER) {
                    e.setCancelled(true);
                    createAbilitiesMenu();
                    p.openInventory(abilitiesMenu);
                    p.sendMessage("Abilities Opened");
                } else if (clicked.getType() == Material.ENDER_CHEST) {
                    e.setCancelled(true);
                    p.closeInventory();
                }
            } else if (inv.getName().equals(moneyMenu.getName())) {
                if (clicked.getType() == Material.PAPER) {
                    e.setCancelled(true);
                    redeemTokens(p, 1);
                } else if (clicked.getType() == Material.IRON_INGOT) {
                    e.setCancelled(true);
                    redeemTokens(p, 10);
                } else if (clicked.getType() == Material.GOLD_INGOT) {
                    e.setCancelled(true);
                    redeemTokens(p, 25);
                } else if (clicked.getType() == Material.DIAMOND_ORE) {
                    e.setCancelled(true);
                    redeemTokens(p, 50);
                } else if (clicked.getType() == Material.DIAMOND) {
                    e.setCancelled(true);
                    redeemTokens(p, 75);
                } else if (clicked.getType() == Material.LAPIS_BLOCK) {
                    e.setCancelled(true);
                    redeemTokens(p, 100);
                } else if (clicked.getType() == Material.LAPIS_ORE) {
                    e.setCancelled(true);
                    redeemTokens(p, 200);
                } else if (clicked.getType() == Material.GOLDEN_APPLE) {
                    e.setCancelled(true);
                    redeemTokens(p, 500);
                } else if (clicked.getType() == Material.BEDROCK) {
                    e.setCancelled(true);
                    redeemTokens(p, 1000);
                } else if (clicked.getType() == Material.ENDER_CHEST) {
                    e.setCancelled(true);
                    createOptionMenu();
                    p.openInventory(storeMenu);
                }
            } else if (inv.getName().equals(rankMenu.getName())) {
                if (clicked.getType() == Material.COAL_ORE) {
                    e.setCancelled(true);
                    rankUp(p, this.getConfig().getInt("RankCost.rank1"));
                } else if (clicked.getType() == Material.IRON_ORE) {
                    e.setCancelled(true);
                    rankUp(p, this.getConfig().getInt("RankCost.rank2"));
                } else if (clicked.getType() == Material.GOLD_ORE) {
                    e.setCancelled(true);
                    rankUp(p, this.getConfig().getInt("RankCost.rank3"));
                } else if (clicked.getType() == Material.DIAMOND_BLOCK) {
                    e.setCancelled(true);
                    rankUp(p, this.getConfig().getInt("RankCost.rank4"));
                } else if (clicked.getType() == Material.ENDER_CHEST) {
                    e.setCancelled(true);
                    createOptionMenu();
                    p.openInventory(storeMenu);
                }
            } else if (inv.getName().equals(expMenu.getName())) {
                if (clicked.getType() == Material.PAPER) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost1"));
                } else if (clicked.getType() == Material.GLOWSTONE_DUST) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost2"));
                } else if (clicked.getType() == Material.GLOWSTONE) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost3"));
                } else if (clicked.getType() == Material.BOOK) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost4"));
                } else if (clicked.getType() == Material.BOOK_AND_QUILL) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost5"));
                } else if (clicked.getType() == Material.QUARTZ) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost6"));
                } else if (clicked.getType() == Material.ARROW) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost7"));
                } else if (clicked.getType() == Material.BLAZE_ROD) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost8"));
                } else if (clicked.getType() == Material.NETHER_STAR) {
                    e.setCancelled(true);
                    giveXP(p, this.getConfig().getInt("ExpCost.cost9"));
                } else if (clicked.getType() == Material.ENDER_CHEST) {
                    e.setCancelled(true);
                    createOptionMenu();
                    p.openInventory(storeMenu);
                }
            } else if (inv.getName().equals(abilitiesMenu.getName())) {
                if (clicked.getType() == Material.FEATHER) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability1.name"));
                } else if (clicked.getType() == Material.NAME_TAG) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability2.name"));
                } else if (clicked.getType() == Material.IRON_HELMET) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability3.name"));
                } else if (clicked.getType() == Material.IRON_AXE) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability4.name"));
                } else if (clicked.getType() == Material.RECORD_10) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability5.name"));
                } else if (clicked.getType() == Material.CHEST) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability6.name"));
                } else if (clicked.getType() == Material.COOKED_CHICKEN) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability7.name"));
                } else if (clicked.getType() == Material.COOKED_BEEF) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability8.name"));
                } else if (clicked.getType() == Material.IRON_BOOTS) {
                    e.setCancelled(true);
                    addPermNode(p, this.getConfig().getString("Abilities.ability9.name"));
                } else if (clicked.getType() == Material.ENDER_CHEST) {
                    e.setCancelled(true);
                    createOptionMenu();
                    p.openInventory(storeMenu);
                }
            }

        } catch (SQLException | UnsupportedEncodingException ex) {
            Logger.getLogger(ex.toString());
            p.sendMessage(ChatColor.RED + "Error redeeming tokens.. Contact an Admin.");
        }
    }

    /**
     *
     * This method handles command input and determines what other methods to
     * run based on the commands name.
     *
     * @param sender The entity sending the command
     * @param cmd The command being sent.
     * @param label Any alias given to the command.
     * @param args Any arguments to be passed to through the command.
     * @return True if the command was entered and executed correctly.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player) { // Command is coming from player.
            Player p = (Player) sender;
            if (cmd.getName().equalsIgnoreCase("CBStore")) {
                if (p.hasPermission("cbtokens.gui")) {
                    createOptionMenu();
                    p.openInventory(storeMenu);
                    p.sendMessage(MSG_PREFIX + "Opened store.");
                    return true;
                } else {
                    p.sendMessage(ChatColor.RED + "Insufficient Permissions.");
                }
            } else if (cmd.getName().equalsIgnoreCase("CBTokens")) {
                p.sendMessage(ChatColor.GOLD + " ---------------- [ " + ChatColor.AQUA + ChatColor.BOLD + "Commands" + ChatColor.GOLD + " ] ---------------- ");
                p.sendMessage(createCommandList("CBStore", "Opens the default GUI store menu for the purchase of rewards with Tokens."));
                p.sendMessage(createCommandList("CBBal", "Tells the player how many tokens they currently hold in their account."));
                p.sendMessage(createCommandList("CBGive", "Gives the specified player x amount of Tokens (Console ONLY)"));
                p.sendMessage(createCommandList("CBPay", "Sends X amount of Tokens to the specified player from your account."));
                p.sendMessage(createCommandList("CBCool", "Tells the sender how much time they have left before they can claim their daily tokens."));
                p.sendMessage(createCommandList("CBReload", "Reloads this plugins config.yml file."));
            } else if (cmd.getName().equalsIgnoreCase("CBBal")) {
                if (p.hasPermission("cbtokens.balance")) {
                    try {
                        getTokenBalance(p);
                    } catch (SQLException | UnsupportedEncodingException ex) {
                        Logger.getLogger(ex.toString());
                        p.sendMessage(ChatColor.RED + "Error checking Token balance.");
                    }
                } else {
                    p.sendMessage(ChatColor.RED + "Insufficient Permissions.");
                }
            } else if (cmd.getName().equalsIgnoreCase("CBGive")) {
                this.getLogger().info("Error: Player tried to enter CBGive command. This is a console only command.");
                p.sendMessage(ChatColor.RED + "Error: Player entered Console only command.");
            } else if (cmd.getName().equalsIgnoreCase("CBPay")) {
                if (p.hasPermission("cbtokens.pay")) {
                    try {
                        payTokens(sender, args);
                    } catch (UnsupportedEncodingException ex) {
                        Logger.getLogger(CBTokens.class.getName()).log(Level.SEVERE, null, ex);
                    }
                } else {
                    p.sendMessage(ChatColor.RED + "Insufficient Permissions.");
                }
            } else if (cmd.getName().equalsIgnoreCase("CBCool")) {
                if (p.hasPermission("cbtokens.cool")) {
                    String coolTimer = convertTimeFormat(cooldowns.get(p.getName()));
                    p.sendMessage(MSG_PREFIX + "You will receive your daily tokens in " + ChatColor.AQUA + coolTimer + ".");
                } else {
                    p.sendMessage(ChatColor.RED + "Insufficient Permissions.");
                }
            }
        } else { // Command is coming from console
            if (cmd.getName().equalsIgnoreCase("CBGive")) {
                if (args.length == 2) {
                    if (Bukkit.getPlayer(args[0]) != null) {
                        Player t = Bukkit.getPlayer(args[0]);
                        giveToken(t, Integer.parseInt(args[1]));
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + "That player is not currently online..");
                    }
                } else {
                    return false;
                }
            } else if (cmd.getName().equalsIgnoreCase("CBReload")) {
                this.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "Sucessfully reloaded config.yml");
            }
        }

        return true;
    }

    /**
     *
     * Creates a formatted String for listing a single Command and a short
     * description of that command
     *
     * @param commandName The Name of the command.
     * @param commandDesc The Description of the command.
     */
    private String createCommandList(String commandName, String commandDesc) {
        return ChatColor.GOLD + "/" + commandName + ChatColor.WHITE + " - " + ChatColor.BLUE + commandDesc;
    }

    /**
     *
     * Gets the amount of tokens Player(p) has.
     *
     * @param p The player to be checked.
     * @throws SQLException If an SQL connection or Query error occurs.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void getTokenBalance(Player p) throws SQLException, UnsupportedEncodingException {

        int tokenAmount = 0; // Amount of tokens the player has.

        if (useSQL) {
            tokenAmount = statement.executeUpdate("SELECT AMMOUNT FROM  " + database + "  WHERE PLAYER = " + p.getUniqueId() + ";");
        } else {
            tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());
        }
        p.sendMessage(MSG_PREFIX + "You currently hold " + tokenAmount + " tokens.");
    }

    /**
     * Fills the store with menu options.
     */
    public void createOptionMenu() {

        if (enableMoney) {
            createMenuItem(Material.GOLD_INGOT, storeMenu, 3, ChatColor.AQUA + "Money Shop", ChatColor.GREEN + "Purchase In-Game Currency Here.");
        }

        if (enablePerms) {
            createMenuItem(Material.DIAMOND, storeMenu, 4, ChatColor.AQUA + "Rank Shop", ChatColor.GREEN + "Purchase Ranks here.");
        }

        if (enableExp) {
            createMenuItem(Material.NETHER_STAR, storeMenu, 5, ChatColor.AQUA + "Exp. Shop", ChatColor.GREEN + "Purchase Experience Levels here.");
        }

        if (customPerms) {
            createMenuItem(Material.FEATHER, storeMenu, 6, ChatColor.AQUA + "Abilities Shop", ChatColor.GREEN + "Purchase In-Game Abilities here.");
        }

        // Create Exit Button
        createMenuItem(Material.ENDER_CHEST, storeMenu, 17, ChatColor.GOLD + "Exit", ChatColor.GRAY + "Close the store.");

    }

    /**
     * Create Ability options
     */
    public void createAbilitiesMenu() {
        if ((this.getConfig().getString("Abilities.ability1.name")) != null || !this.getConfig().getString("Abilities.ability1.name").equalsIgnoreCase("")) {
            createMenuItem(Material.FEATHER, abilitiesMenu, 0, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability1.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability1.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability2.name")) != null || !this.getConfig().getString("Abilities.ability2.name").equalsIgnoreCase("")) {
            createMenuItem(Material.NAME_TAG, abilitiesMenu, 1, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability2.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability2.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability3.name")) != null || !this.getConfig().getString("Abilities.ability3.name").equalsIgnoreCase("")) {
            createMenuItem(Material.IRON_HELMET, abilitiesMenu, 2, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability3.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability3.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability4.name")) != null || !this.getConfig().getString("Abilities.ability4.name").equalsIgnoreCase("")) {
            createMenuItem(Material.IRON_AXE, abilitiesMenu, 3, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability4.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability4.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability5.name")) != null || !this.getConfig().getString("Abilities.ability5.name").equalsIgnoreCase("")) {
            createMenuItem(Material.RECORD_10, abilitiesMenu, 4, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability5.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability5.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability6.name")) != null || !this.getConfig().getString("Abilities.ability6.name").equalsIgnoreCase("")) {
            createMenuItem(Material.CHEST, abilitiesMenu, 5, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability6.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability6.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability7.name")) != null || !this.getConfig().getString("Abilities.ability7.name").equalsIgnoreCase("")) {
            createMenuItem(Material.COOKED_CHICKEN, abilitiesMenu, 6, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability7.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability7.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability8.name")) != null || !this.getConfig().getString("Abilities.ability8.name").equalsIgnoreCase("")) {
            createMenuItem(Material.COOKED_BEEF, abilitiesMenu, 7, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability8.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability8.cost") + " Tokens");
        }
        if ((this.getConfig().getString("Abilities.ability9.name")) != null || !this.getConfig().getString("Abilities.ability9.name").equalsIgnoreCase("")) {
            createMenuItem(Material.IRON_BOOTS, abilitiesMenu, 8, ChatColor.GOLD + "Ability: " + this.getConfig().getString("Abilities.ability9.name"), ChatColor.GREEN + "Permission Cost: " + this.getConfig().getInt("Abilities.ability9.cost") + " Tokens");
        }
        // Create Exit Item
        createMenuItem(Material.ENDER_CHEST, abilitiesMenu, 17, ChatColor.GOLD + "Exit", ChatColor.GRAY + "Return to the main menu");
    }

    /**
     * Create Rank options
     */
    public void createRankMenu() {
        // Create store item 1
        createMenuItem(Material.COAL_ORE, rankMenu, 0, ChatColor.AQUA + "Rank: COPPER", ChatColor.GREEN + "This rank costs: " + this.getConfig().getInt("RankCost.rank1") + " Tokens");
        // Create store item 2
        createMenuItem(Material.IRON_ORE, rankMenu, 1, ChatColor.AQUA + "Rank: STEEL", ChatColor.GREEN + "This rank costs: " + this.getConfig().getInt("RankCost.rank2") + " Tokens");
        // Create store item 3
        createMenuItem(Material.GOLD_ORE, rankMenu, 2, ChatColor.AQUA + "Rank: GOLD", ChatColor.GREEN + "This rank costs: " + this.getConfig().getInt("RankCost.rank3") + " Tokens!");
        // Create store item 4
        createMenuItem(Material.DIAMOND_BLOCK, rankMenu, 3, ChatColor.AQUA + "Rank: DIAMOND", ChatColor.GREEN + "This rank costs: " + this.getConfig().getInt("RankCost.rank4") + " Tokens!!");
        // Create Exit Item
        createMenuItem(Material.ENDER_CHEST, rankMenu, 17, ChatColor.GOLD + "Exit", ChatColor.GRAY + "Return to the main menu");
    }

    /**
     * Create Exp Options
     */
    public void createExpMenu() {
        // Create store item 1
        createMenuItem(Material.PAPER, expMenu, 0, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount1") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost1"));
        // Create store item 2
        createMenuItem(Material.GLOWSTONE_DUST, expMenu, 1, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount2") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost2"));
        // Create store item 3
        createMenuItem(Material.GLOWSTONE, expMenu, 2, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount3") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost3"));
        // Create store item 4
        createMenuItem(Material.BOOK, expMenu, 3, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount4") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost4"));
        // Create store item 5
        createMenuItem(Material.BOOK_AND_QUILL, expMenu, 4, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount5") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost5"));
        // Create store item 6
        createMenuItem(Material.QUARTZ, expMenu, 5, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount6") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost6"));
        // Create store item 7
        createMenuItem(Material.ARROW, expMenu, 6, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount7") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost7"));
        // Create store item 8
        createMenuItem(Material.BLAZE_ROD, expMenu, 7, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount8") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost8"));
        // Create store item 9
        createMenuItem(Material.NETHER_STAR, expMenu, 8, ChatColor.GREEN + "Experience: " + this.getConfig().getInt("ExpCost.amount9") + " Levels", ChatColor.AQUA + "Costs: " + this.getConfig().getInt("ExpCost.cost9"));
        // Create Exit Item
        createMenuItem(Material.ENDER_CHEST, expMenu, 17, ChatColor.GOLD + "Exit", ChatColor.GRAY + "Return to the main menu");
    }

    /**
     * Filles the Money menu with purchase options.
     */
    public void createMoneyMenu() {
        // Create store item 1
        createMenuItem(Material.PAPER, moneyMenu, 0, ChatColor.GOLD + "Redeem 1 Token", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.one"));
        // Create store item 2
        createMenuItem(Material.IRON_INGOT, moneyMenu, 1, ChatColor.GOLD + "Redeem 10 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.ten"));
        // Create store item 3
        createMenuItem(Material.GOLD_INGOT, moneyMenu, 2, ChatColor.GOLD + "Redeem 25 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.twenty-five"));
        // Create store item 4
        createMenuItem(Material.DIAMOND_ORE, moneyMenu, 3, ChatColor.GOLD + "Redeem 50 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.fifty"));
        // Create store item 5
        createMenuItem(Material.DIAMOND, moneyMenu, 4, ChatColor.GOLD + "Redeem 75 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.seventy-five"));
        // Create store item 6
        createMenuItem(Material.LAPIS_BLOCK, moneyMenu, 5, ChatColor.GOLD + "Redeem 100 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.one-hundred"));
        // Create store item 7
        createMenuItem(Material.LAPIS_ORE, moneyMenu, 6, ChatColor.GOLD + "Redeem 200 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.two-hundred"));
        // Create store item 8
        createMenuItem(Material.GOLDEN_APPLE, moneyMenu, 7, ChatColor.GOLD + "Redeem 500 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.five-hundred"));
        // Create store item 9
        createMenuItem(Material.BEDROCK, moneyMenu, 8, ChatColor.GOLD + "Redeem 1000 Token's", ChatColor.GREEN + "You will receive: \n" + "$" + this.getConfig().getInt("Token-Worth.one-thousand"));
        // Create Exit Item
        createMenuItem(Material.ENDER_CHEST, moneyMenu, 17, ChatColor.GOLD + "Exit", ChatColor.GRAY + "Return to the main menu");
    }

    /**
     *
     * This method creates and fills one item spot in an inventory specified by
     * the caller.
     *
     * @param material The material to put in the inventory slot.
     * @param inv The Inventory to modify.
     * @param Slot The Slot number(index) to modify.
     * @param name The name of the item to be.
     * @param lore The item description or lore.
     */
    public void createMenuItem(Material material, Inventory inv, int Slot, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        List<String> Lore = new ArrayList();
        Lore.add(lore);
        meta.setLore(Lore);
        item.setItemMeta(meta);

        inv.setItem(Slot, item);
    }

    /**
     *
     * This method gives amount tokens to the given Player (p).
     *
     * @param player The player receiving the token.
     * @param amount The ammount of Tokens to give to the player.
     */
    public void giveToken(Player player, int amount) {
        Player p = player;

        if (useSQL) {
            try {
                statement.executeUpdate("INSERT INTO  " + database + "  (PLAYERNAME, AMOUNT) VALUES ('" + p.getUniqueId() + "', " + amount + ")");
                p.sendMessage(MSG_PREFIX + amount + " tokens added to your account.");
            } catch (SQLException ex) {
                p.sendMessage(ChatColor.RED + "ERROR: SQL Connection error (java.lang.SQLException)");
                Logger.getLogger(ex.toString());
            }
        } else {

            int tokenAmount = 0;

            try {
                tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());
                tokenAmount = tokenAmount + amount;
                this.getStorage().set(p.getUniqueId().toString(), tokenAmount);
                // p.sendMessage(MSG_PREFIX + amount + " tokens added to your account.");
                Title msg = new Title("Recieved Tokens", "Recieved: " + amount + " Tokens!");
                msg.setStayTime(6);
                msg.setTitleColor(org.bukkit.ChatColor.GOLD);
                msg.setSubtitleColor(org.bukkit.ChatColor.GREEN);
                msg.send(player);
            } catch (UnsupportedEncodingException ex) {
                getLogger().info(ex.toString());
                p.sendMessage(ChatColor.RED + "ERROR: Unsupported Encoding.");
            }
        }

    }

    /**
     *
     * Gives currency to the player (p) for redeeming the token item.
     *
     * @param player The player redeeming the tokens.
     * @param amount The amount of Tokens the Player is redeeming.
     * @throws java.sql.SQLException Error SQL Connection was not made or there
     * was an error processing a command.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void redeemTokens(Player player, int amount) throws SQLException, UnsupportedEncodingException {

        int worth = 0;
        int tokenAmount = 0;

        if (useSQL) {
            tokenAmount = statement.executeUpdate("SELECT AMOUNT FROM  " + database + "  WHERE PLAYER = '" + player.getUniqueId() + "';");
        } else {
            tokenAmount = this.getStorage().getInt(player.getUniqueId().toString());
        }

        // Determines the worth of the amount of tokens being redeemed.
        switch (amount) {
            case 1:
                worth = this.getConfig().getInt("Token-Worth.one");
                break;
            case 10:
                worth = this.getConfig().getInt("Token-Worth.ten");
                break;
            case 25:
                worth = this.getConfig().getInt("Token-Worth.twenty-five");
                break;
            case 50:
                worth = this.getConfig().getInt("Token-Worth.fifty");
                break;
            case 75:
                worth = this.getConfig().getInt("Token-Worth.seventy-five");
                break;
            case 100:
                worth = this.getConfig().getInt("Token-Worth.one-hundred");
                break;
            case 200:
                worth = this.getConfig().getInt("Token-Worth.two-hundred");
                break;
            case 500:
                worth = this.getConfig().getInt("Token-Worth.five-hundred");
                break;
            case 1000:
                worth = this.getConfig().getInt("Token-Worth.one-thousand");
                break;
            default:
                player.sendMessage(MSG_PREFIX + "There was an error finding the worth of the tokens you are redeeming..Notify and Administrator.");
        }

        if (tokenAmount >= amount) {
            econ.depositPlayer(player, worth);
            tokenAmount = tokenAmount - amount;
            this.getStorage().set(player.getUniqueId().toString(), tokenAmount);
            player.sendMessage(MSG_PREFIX + "Sucessfully redeemed " + amount + " tokens for $" + worth + " " + econ.currencyNamePlural());
            this.saveStorage();
        } else {
            player.sendMessage(MSG_PREFIX + "You don't have enough tokens to cover that offer.");
        }
    }

    /**
     *
     * This method gives the a set permission node to a player for the use of an
     * ability
     *
     * @param p The Player purchasing the Ability
     * @param amount The amount of Tokens the player is paying.
     */
    public void giveAbility(Player p, int amount) {

    }

    /**
     *
     * This method gives the specified player x amount of XP when for a certain
     * amount of tokens
     *
     * @param player The player receiving the Exp.
     * @param amount The amount of tokens being spent.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void giveXP(Player player, int amount) throws UnsupportedEncodingException {

        int worth = 0;
        int tokenAmount = this.getStorage().getInt(player.getUniqueId().toString());

        if (amount == this.getConfig().getInt("ExpCost.cost1")) {
            worth = this.getConfig().getInt("ExpCost.amount1");
        } else if (amount == this.getConfig().getInt("ExpCost.cost2")) {
            worth = this.getConfig().getInt("ExpCost.amount2");
        } else if (amount == this.getConfig().getInt("ExpCost.cost3")) {
            worth = this.getConfig().getInt("ExpCost.amount3");
        } else if (amount == this.getConfig().getInt("ExpCost.cost4")) {
            worth = this.getConfig().getInt("ExpCost.amount4");
        } else if (amount == this.getConfig().getInt("ExpCost.cost5")) {
            worth = this.getConfig().getInt("ExpCost.amount5");
        } else if (amount == this.getConfig().getInt("ExpCost.cost6")) {
            worth = this.getConfig().getInt("ExpCost.amount6");
        } else if (amount == this.getConfig().getInt("ExpCost.cost7")) {
            worth = this.getConfig().getInt("ExpCost.amount7");
        } else if (amount == this.getConfig().getInt("ExpCost.cost8")) {
            worth = this.getConfig().getInt("ExpCost.amount8");
        } else if (amount == this.getConfig().getInt("ExpCost.cost9")) {
            worth = this.getConfig().getInt("ExpCost.amount9");
        }

        if (tokenAmount >= amount) {
            player.setLevel(player.getLevel() + worth);
            tokenAmount = tokenAmount - amount;
            this.getStorage().set(player.getUniqueId().toString(), tokenAmount);
            player.sendMessage(MSG_PREFIX + "You have been awarded " + worth + " Levels for " + amount + " Token(s)!");
        } else {
            player.sendMessage(MSG_PREFIX + "You do not have enough tokens for that operation.");
            getLogger().log(Level.INFO, "player {0} could not afford a token purchase.", player.getName());
        }

    }

    /**
     *
     * This method rewards the player, p, with a certain rank depending on how
     * many tokens they are redeeming.
     *
     * @param p The player redeeming the rank for tokens.
     * @param amount The amount of tokens being redeemed.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void rankUp(Player p, int amount) throws UnsupportedEncodingException {

        // The amount of tokesn the player has on hand.
        int tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());
        String rankToGive = "";

        if (amount == this.getConfig().getInt("RankCost.rank1")) {
            rankToGive = this.getConfig().getString("RankToGive.rank1");
        } else if (amount == this.getConfig().getInt("RankCost.rank2")) {
            rankToGive = this.getConfig().getString("RankToGive.rank2");
        } else if (amount == this.getConfig().getInt("RankCost.rank3")) {
            rankToGive = this.getConfig().getString("RankToGive.rank3");
        } else if (amount == this.getConfig().getInt("RankCost.rank4")) {
            rankToGive = this.getConfig().getString("RankToGive.rank4");
        }

        if (tokenAmount >= amount) {
            if (perms.hasGroupSupport()) {
                if (!perms.playerInGroup(p, rankToGive)) {
                    if (perms.playerAddGroup(p, rankToGive)) {
                    tokenAmount = tokenAmount - amount;
                    this.getStorage().set(p.getUniqueId().toString(), tokenAmount);
                    p.sendMessage(MSG_PREFIX + ChatColor.GREEN + "Congratulations " + ChatColor.WHITE + rankToGive + " has been added to you for paying " + amount + " Tokens");
                    } else {
                        p.sendMessage(ChatColor.RED + "Error: Could not add you to group, " + rankToGive);
                    }
                } else {
                    p.sendMessage(MSG_PREFIX + "You are already in that group!");
                }
            } else {
                getLogger().info("ERROR: Chosen permissions plugin does not have group support.");
                p.sendMessage(ChatColor.RED + "Internal error! (Permissions plugin does not support groups)");
            }
        } else {
            p.sendMessage(MSG_PREFIX + "You do not have enough tokens for that operation.");
            getLogger().log(Level.INFO, "player {0} could not afford a token purchase.", p.getUniqueId().toString());
        }

    }

    /**
     *
     * This method will send x amount of tokens from Player1 to the player
     * specified in the argument.
     *
     * @param sender The player sending the command.
     * @param args Any arguments to be passed into the command.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void payTokens(CommandSender sender, String[] args) throws UnsupportedEncodingException {

        int tokensToSend = 0;
        Player p = (Player) sender;
        int tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());

        if (args.length < 2) {
            p.sendMessage(MSG_PREFIX + "Invalid command usage. The correct usage is, /cbpay [Player] [Amount]");
        } else {
            Player f = Bukkit.getPlayer(args[0]);
            tokensToSend = Integer.parseInt(args[1]);

            if (tokensToSend <= tokenAmount) {
                if (f != null) {
                    int friendAmount = this.getStorage().getInt(f.getUniqueId().toString());

                    friendAmount = friendAmount + tokensToSend;
                    tokenAmount = tokenAmount - tokensToSend;
                    this.getStorage().set(f.getUniqueId().toString(), friendAmount);
                    this.getStorage().set(p.getUniqueId().toString(), tokenAmount);
                    p.sendMessage(MSG_PREFIX + tokensToSend + " Tokens have been sent to " + f.getName());
                    f.sendMessage(MSG_PREFIX + "You have received " + tokensToSend + " Tokens from " + p.getName());
                } else {
                    p.sendMessage(MSG_PREFIX + "Sorry player, " + args[0] + " is offline.");
                }
            } else {
                p.sendMessage(MSG_PREFIX + "Not enough tokens to cover that payment.");
            }

        }

    }

    /**
     *
     * This method adds the specified Permission node to the player who issued
     * the command.
     *
     * @param p The player purchasing the permission.
     * @param name The name of the permission/ability to be added to the player.
     * @throws java.io.UnsupportedEncodingException If UTF-8 Encoding is not
     * used.
     */
    public void addPermNode(Player p, String name) throws UnsupportedEncodingException {

        String permNode = "";
        int tokenAmount = this.getStorage().getInt(p.getUniqueId().toString());
        int cost = 0;

        if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability1.name"))) {
            permNode = this.getConfig().getString("Abilities.ability1.perm");
            cost = this.getConfig().getInt("Abilities.ability1.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability2.name"))) {
            permNode = this.getConfig().getString("Abilities.ability2.perm");
            cost = this.getConfig().getInt("Abilities.ability2.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability3.name"))) {
            permNode = this.getConfig().getString("Abilities.ability3.perm");
            cost = this.getConfig().getInt("Abilities.ability3.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability4.name"))) {
            permNode = this.getConfig().getString("Abilities.ability4.perm");
            cost = this.getConfig().getInt("Abilities.ability4.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability5.name"))) {
            permNode = this.getConfig().getString("Abilities.ability5.perm");
            cost = this.getConfig().getInt("Abilities.ability5.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability6.name"))) {
            permNode = this.getConfig().getString("Abilities.ability6.perm");
            cost = this.getConfig().getInt("Abilities.ability6.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability7.name"))) {
            permNode = this.getConfig().getString("Abilities.ability7.perm");
            cost = this.getConfig().getInt("Abilities.ability7.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability8.name"))) {
            permNode = this.getConfig().getString("Abilities.ability8.perm");
            cost = this.getConfig().getInt("Abilities.ability8.cost");
        } else if (name.equalsIgnoreCase(this.getConfig().getString("Abilities.ability9.name"))) {
            permNode = this.getConfig().getString("Abilities.ability9.perm");
            cost = this.getConfig().getInt("Abilities.ability9.cost");
        }

        if (tokenAmount < cost) {
            p.sendMessage(MSG_PREFIX + "Sorry, you do not have the funds to cover that purchase.");
        } else {
            if (perms.playerHas(p, permNode)) {
                p.sendMessage(ChatColor.RED + "Could not add Ability: You already have that permission");
            } else {
                if (perms.playerAdd(p, permNode)) {
                    tokenAmount = tokenAmount - cost;
                    this.getStorage().set(p.getUniqueId().toString(), tokenAmount);
                    p.sendMessage(MSG_PREFIX + "Purchased " + name + " ability for " + cost + " Tokens");
                    getLogger().log(Level.INFO, "{0} Permission was Added to {1}", new Object[]{permNode, p.getName()});
                } else {
                    p.sendMessage(ChatColor.RED + "Error: Could not perform operation.");
                }
            }
        }
    }
}

