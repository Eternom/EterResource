package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * À l'arrivée sur un serveur (partout) : les minutes gratuites du jour, le remboursement d'une session coupée par un
 * monde qui a planté, et (hors monde ressource) le récapitulatif de la dernière sortie d'un monde ressource.
 */
public class AccessEvents implements Listener {

    private static final long DELAY_TICKS = 40;

    private final JavaPlugin plugin;
    private final AccessService service;
    private final AccessRepository access;
    private final Messages messages;

    public AccessEvents(JavaPlugin plugin, AccessService service, AccessRepository access, Messages messages) {
        this.plugin = plugin;
        this.service = service;
        this.access = access;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            service.grantDaily(player);
            if (!service.isWorldServer()) {
                service.refundCrash(player);
                showRecap(player);
            }
        }, DELAY_TICKS);
    }

    /** « secondes;blocs;minerais;bûches;créatures;thème », écrit par Sessions à la sortie du monde ressource. */
    private void showRecap(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> access.takeRecap(uuid), recap -> recap.ifPresent(data -> {
            String[] parts = data.split(";");
            if (parts.length < 6) {
                return;
            }
            long seconds;
            try {
                seconds = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                return;
            }
            if (seconds < 30) {
                return;
            }
            messages.send(player, "recap.title", "theme", messages.plain(player, "theme." + parts[5] + ".name"),
                    "time", EterLib.get().formatDuration(player, seconds));
            messages.send(player, "recap.line", "blocks", parts[1], "ores", parts[2], "logs", parts[3], "mobs", parts[4]);
        }), () -> { });
    }
}
