package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;

/**
 * /ressource : le menu d'accès. /eterresource (staff, console comprise, ex : récompenses d'EterReward) :
 * givekey <joueur> <n>, givetime <joueur> <minutes> (sans plafond), info <joueur>. Le joueur peut être hors ligne
 * (annuaire des joueurs d'EterLib).
 */
public class AccessCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("givekey", "givetime", "info");

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final AccessService service;
    private final AccessRepository access;
    private final Messages messages;
    private final BackButton back;

    public AccessCommand(JavaPlugin plugin, EterLib lib, AccessService service, AccessRepository access, Messages messages) {
        this.plugin = plugin;
        this.lib = lib;
        this.service = service;
        this.access = access;
        this.messages = messages;
        this.back = lib.backButton(plugin.getConfig().getString("menus.access.back-command", ""));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("ressource")) {
            if (sender instanceof Player player) {
                AccessMenu.open(plugin, service, messages, back, player);
            } else {
                messages.send(sender, "command.players-only");
            }
            return true;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        int amount = args.length > 2 ? parsePositive(args[2]) : 0;
        boolean valid = switch (action) {
            case "givekey", "givetime" -> args.length == 3 && amount > 0;
            case "info" -> args.length == 2;
            default -> false;
        };
        if (!valid) {
            messages.send(sender, "admin.usage");
            return true;
        }
        String name = args[1];
        // Annuaire et base : en tâche de fond, réponse sur le thread principal
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String key;
            String[] placeholders;
            try {
                Optional<NetworkPlayer> target = lib.getPlayers().find(name);
                if (target.isEmpty()) {
                    reply(sender, "player.unknown", "player", name);
                    return;
                }
                NetworkPlayer player = target.get();
                switch (action) {
                    case "givekey" -> access.giveKeys(player.uuid(), amount);
                    case "givetime" -> access.addTime(player.uuid(), Duration.ofMinutes(amount).toMillis(), Long.MAX_VALUE / 2);
                    default -> {
                    }
                }
                Access now = access.get(player.uuid());
                key = "admin." + action;
                placeholders = new String[]{"player", player.name(), "amount", String.valueOf(amount),
                        "time", lib.formatDuration(sender, now.remainingMillis() / 1000), "keys", String.valueOf(now.keys())};
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "/eterresource " + action + " " + name, e);
                reply(sender, "error.generic");
                return;
            }
            reply(sender, key, placeholders);
            if (!action.equals("info")) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(name);
                    if (online != null) {
                        messages.send(online, "admin.received-" + action.substring(4), "amount", String.valueOf(amount));
                        service.timeChanged(online);
                    }
                });
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equals("eterresource")) {
            return List.of();
        }
        if (args.length == 1) {
            return ACTIONS.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            return lib.getOnlineNames().complete(args[1], true);
        }
        return List.of();
    }

    private void reply(CommandSender sender, String key, String... placeholders) {
        Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, key, placeholders));
    }

    private static int parsePositive(String text) {
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
