package pw.chew.jsonrestapi.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import pw.chew.jsonrestapi.JSONRestAPI;
import pw.chew.jsonrestapi.RestServer;

public class ReloadCommand implements CommandExecutor {
    public static FileConfiguration config;
    public final JSONRestAPI plugin;

    public ReloadCommand(FileConfiguration baseConfig, JSONRestAPI plugin) {
        config = baseConfig;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        sender.sendMessage("Reloading the config.");
        plugin.reloadConfig();
        config = plugin.getConfig();
        // Tell the server about the new config.
        RestServer.updateConfig(config);
        sender.sendMessage("Configuration reloaded!");
        return true;
    }
}
