package fr.eternom.eterResource.listeners;

import fr.eternom.eterResource.Main;
import fr.eternom.eterResource.module.access.AccessCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        AccessCommand access = new AccessCommand(main, main.getLib(), main.getService(), main.getAccess(), main.getMessages());
        register(main, "ressource", access);
        register(main, "eterresource", access);
    }

    private static void register(Main main, String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
