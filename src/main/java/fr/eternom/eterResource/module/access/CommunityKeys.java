package fr.eternom.eterResource.module.access;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Clé communautaire (achat sur la boutique du site, lancé par la console) : des clés pour chaque joueur connecté sur
 * le réseau, et un merci à l'acheteur sur tous les serveurs. Les clés vont en base une seule fois, depuis le serveur
 * qui reçoit la commande ; chaque serveur n'annonce qu'à ses propres joueurs.
 */
public class CommunityKeys {

    private static final String ANNOUNCE = "community";

    private final EterLib lib;
    private final AccessRepository access;
    private final AccessService service;
    private final Messages messages;
    private final NetworkBus bus;

    public CommunityKeys(JavaPlugin plugin, EterLib lib, AccessRepository access, AccessService service, Messages messages) {
        this.lib = lib;
        this.access = access;
        this.service = service;
        this.messages = messages;
        this.bus = lib.network(plugin, "eterresource", messages);
        bus.on(ANNOUNCE, data -> announceHere(data.get("buyer").getAsString(), data.get("amount").getAsInt()));
    }

    /** Bloquant (base) : amount clés pour chaque joueur connecté sur le réseau (sans les invisibles). Renvoie leur nombre. */
    public int give(int amount) {
        List<NetworkPlayer> online = lib.getPlayers().listOnline();
        online.forEach(player -> access.giveKeys(player.uuid(), amount));
        return online.size();
    }

    /** Thread principal : remercie l'acheteur ici, puis sur les autres serveurs. */
    public void announce(String buyer, int amount) {
        announceHere(buyer, amount);
        JsonObject data = new JsonObject();
        data.addProperty("buyer", buyer);
        data.addProperty("amount", amount);
        bus.publish(ANNOUNCE, data);
    }

    private void announceHere(String buyer, int amount) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages.send(player, "admin.community", "player", buyer, "amount", String.valueOf(amount));
            service.timeChanged(player);
        }
    }
}
