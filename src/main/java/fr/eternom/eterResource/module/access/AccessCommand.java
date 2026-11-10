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
 * /resource : le menu d'accès. /eterresource (staff, console comprise, ex : récompenses d'EterReward) :
 * givekey <joueur> <n>, givetime <joueur> <minutes> (sans plafond), info <joueur>. Le joueur peut être hors ligne
 * (annuaire des joueurs d'EterLib). communitykey <acheteur> <n> : n clés pour chaque joueur connecté sur le réseau.
 * testmode on|off : ce monde ressource n'est plus proposé (seul le staff y entre) ; worlds : le journal des mondes.
 * /resource gift <joueur> <minutes> : offrir du temps ; /resource join <joueur> : rejoindre le monde d'un ami.
 */
public class AccessCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("givekey", "givetime", "communitykey", "info", "testmode", "worlds", "event", "places");
    private static final List<String> PLAYER_ACTIONS = List.of("gift", "join", "bag");
    private fr.eternom.eterResource.module.world.WorldSetup world;
    private fr.eternom.eterResource.module.content.ResourceContent content;

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final AccessService service;
    private final AccessRepository access;
    private final CommunityKeys community;
    private final Messages messages;
    private final BackButton back;

    public AccessCommand(JavaPlugin plugin, EterLib lib, AccessService service, AccessRepository access, CommunityKeys community,
                         Messages messages) {
        this.plugin = plugin;
        this.lib = lib;
        this.service = service;
        this.access = access;
        this.community = community;
        this.messages = messages;
        this.back = lib.backButton(plugin.getConfig().getString("menus.access.back-command", ""));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("resource")) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "command.players-only");
            } else if (args.length >= 3 && args[0].equalsIgnoreCase("gift")) {
                gift(player, args[1], parsePositive(args[2]));
            } else if (args.length >= 1 && args[0].equalsIgnoreCase("bag") && content != null) {
                content.bag().open(player);
            } else if (args.length >= 2 && args[0].equalsIgnoreCase("join")) {
                service.join(player, args[1]);
            } else if (args.length > 0) {
                messages.send(player, "access.usage");
            } else {
                AccessMenu.open(plugin, service, messages, back, player);
            }
            return true;
        }
        String first = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (first.equals("testmode")) {
            testMode(sender, args.length > 1 && args[1].equalsIgnoreCase("on"));
            return true;
        }
        if (first.equals("places")) {
            places(sender, args);
            return true;
        }
        if (first.equals("event")) {
            startEvent(sender, args.length > 1 ? args[1] : "");
            return true;
        }
        if (first.equals("worlds")) {
            worldLog(sender);
            return true;
        }
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        int amount = args.length > 2 ? parsePositive(args[2]) : 0;
        boolean valid = switch (action) {
            case "givekey", "givetime", "communitykey" -> args.length == 3 && amount > 0;
            case "info" -> args.length == 2;
            default -> false;
        };
        if (!valid) {
            messages.send(sender, "admin.usage");
            return true;
        }
        String name = args[1];
        if (action.equals("communitykey")) {
            giveCommunity(sender, name, amount);
            return true;
        }
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
                    case "givetime" -> {
                        access.addTime(player.uuid(), Duration.ofMinutes(amount).toMillis(), Long.MAX_VALUE / 2);
                        access.log(player.uuid(), Duration.ofMinutes(amount).toMillis(), "staff:" + sender.getName());
                    }
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
            if (args.length == 1) {
                return PLAYER_ACTIONS.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            }
            return args.length == 2 ? lib.getOnlineNames().complete(args[1]) : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("testmode")) {
            return List.of("on", "off");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of("info", "tp", "stop"),
                    java.util.Arrays.stream(fr.eternom.eterResource.module.content.WorldEvents.Kind.values())
                    .map(kind -> kind.name().toLowerCase(Locale.ROOT).replace('_', '-'))).toList();
        }
        if (args.length == 1) {
            return ACTIONS.stream().filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            return lib.getOnlineNames().complete(args[1]);
        }
        return List.of();
    }

    /** Sur un monde ressource : la fiche de ce monde (mode test). */
    public void setWorld(fr.eternom.eterResource.module.world.WorldSetup world) {
        this.world = world;
    }

    public void setContent(fr.eternom.eterResource.module.content.ResourceContent content) {
        this.content = content;
    }

    /**
     * /eterresource event <type> : lancer un événement ici ; event info : celui en cours, son lieu exact ; event tp : y aller ;
     * event stop : l'arrêter.
     */
    private void startEvent(CommandSender sender, String name) {
        if (content == null || content.events() == null) {
            messages.send(sender, "admin.not-a-world");
            return;
        }
        var events = content.events();
        switch (name.toLowerCase(Locale.ROOT)) {
            case "", "info" -> {
                if (events.current() == null) {
                    messages.send(sender, "admin.event-none");
                    return;
                }
                org.bukkit.Location at = events.target();
                messages.send(sender, "admin.event-info", "event", events.current().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        "time", lib.formatDuration(sender, Math.max(0, events.endsAt() - System.currentTimeMillis()) / 1000),
                        "where", at == null ? "-" : at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
            }
            case "tp" -> {
                org.bukkit.Location at = events.target();
                if (!(sender instanceof Player player) || at == null) {
                    messages.send(sender, "admin.event-none");
                    return;
                }
                player.teleport(at.clone().add(0.5, 4, 0.5));
            }
            case "stop" -> messages.send(sender, events.stop() ? "admin.event-stopped" : "admin.event-none");
            default -> {
                try {
                    var kind = fr.eternom.eterResource.module.content.WorldEvents.Kind.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
                    messages.send(sender, events.begin(kind) ? "admin.event-started" : "admin.event-busy");
                } catch (IllegalArgumentException e) {
                    messages.send(sender, "admin.event-usage");
                }
            }
        }
    }

    /** /eterresource places [tp <n°>] : les lieux du monde (coordonnées exactes), et y aller. */
    private void places(CommandSender sender, String[] args) {
        if (content == null || content.places() == null) {
            messages.send(sender, "admin.not-a-world");
            return;
        }
        var all = content.places().all();
        if (args.length >= 3 && args[1].equalsIgnoreCase("tp") && sender instanceof Player player) {
            int index = parsePositive(args[2]) - 1;
            if (index < 0 || index >= all.size()) {
                messages.send(sender, "admin.places-unknown");
                return;
            }
            player.teleport(all.get(index).location().clone().add(0.5, 2, 0.5));
            return;
        }
        messages.send(sender, "admin.places-header", "count", String.valueOf(all.size()));
        for (int i = 0; i < all.size(); i++) {
            var place = all.get(i);
            messages.send(sender, "admin.places-line", "n", String.valueOf(i + 1), "type", place.type(),
                    "where", place.location().getBlockX() + " " + place.location().getBlockY() + " " + place.location().getBlockZ(),
                    "found", String.valueOf(place.discoveredBy().size()));
        }
    }

    private void gift(Player player, String name, int minutes) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<NetworkPlayer> target = lib.getPlayers().findFor(player, name);
            Bukkit.getScheduler().runTask(plugin, () -> target.ifPresentOrElse(found -> service.gift(player, found.uuid(), found.name(), minutes),
                    () -> messages.send(player, "player.unknown", "player", name)));
        });
    }

    private void testMode(CommandSender sender, boolean on) {
        if (world == null) {
            messages.send(sender, "admin.not-a-world");
            return;
        }
        world.setTest(on);
        messages.send(sender, on ? "admin.testmode-on" : "admin.testmode-off");
    }

    private void worldLog(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<fr.eternom.eterResource.module.world.WorldDirectory.LogEntry> entries = service.directory().history(10);
            java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("dd/MM HH:mm");
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.send(sender, "admin.worlds-header", "count", String.valueOf(entries.size()));
                for (var entry : entries) {
                    messages.send(sender, "admin.worlds-line", "server", entry.name(), "theme", entry.theme() == null ? "-" : entry.theme(),
                            "opened", date.format(new java.util.Date(entry.openedAt())),
                            "closed", entry.closedAt() > 0 ? date.format(new java.util.Date(entry.closedAt())) : "…",
                            "peak", String.valueOf(entry.peak()), "joins", String.valueOf(entry.joins()));
                }
            });
        });
    }

    /** Clés pour tous les joueurs connectés, puis merci à l'acheteur (son pseudo tel qu'enregistré, s'il est connu). */
    private void giveCommunity(CommandSender sender, String buyerName, int amount) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String buyer;
            int count;
            try {
                buyer = lib.getPlayers().find(buyerName).map(NetworkPlayer::name).orElse(buyerName);
                count = community.give(amount);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "/eterresource communitykey " + buyerName, e);
                reply(sender, "error.generic");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.send(sender, "admin.communitykey", "player", buyer, "amount", String.valueOf(amount),
                        "count", String.valueOf(count));
                community.announce(buyer, amount);
            });
        });
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
