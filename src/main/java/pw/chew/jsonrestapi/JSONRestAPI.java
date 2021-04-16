package pw.chew.jsonrestapi;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pw.chew.jsonrestapi.commands.ReloadCommand;

public final class JSONRestAPI extends JavaPlugin {
    // Files
    private static FileConfiguration config;

    @Override
    public void onEnable() {
        // Get and save config
        config = this.getConfig();
        config.addDefault("port", 6548);
        config.addDefault("authkey", "CHANGE_ME_PLEASE");
        config.options().copyDefaults(true);
        saveDefaultConfig();

        // Plugin startup logic
        RestServer server = new RestServer(config, this.getLogger());
        server.setDaemon(true);
        server.start();

        this.getCommand("jrareload").setExecutor(new ReloadCommand(config, this));

        this.getLogger().info("Listening on port " + config.getInt("port"));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
