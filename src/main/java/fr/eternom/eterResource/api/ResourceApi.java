package fr.eternom.eterResource.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterResource offre aux autres plugins : le temps de monde ressource d'un joueur et ses clés (récompenses,
 * boutique, quêtes...). Personne d'autre ne lit eterresource_access : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterResource:&lt;tag&gt;") ; plugin.yml : softdepend: [EterResource]
 * </pre>
 */
public interface ResourceApi {

    /** Temps restant (millisecondes) et clés d'un joueur. */
    record Access(long remainingMillis, int keys) {
    }

    /** L'API d'EterResource si le plugin tourne sur ce serveur. */
    static Optional<ResourceApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(ResourceApi.class));
    }

    /** Ce serveur est un monde ressource (et non un lobby ou une survie). */
    boolean isResourceServer();

    /** Bloquant (base) : hors du thread principal. */
    Access access(UUID player);

    /** Ajoute du temps, sans plafond (récompense). Bloquant (base). */
    void addTime(UUID player, long millis);

    /** Donne des clés (une clé = un créneau). Bloquant (base). */
    void giveKeys(UUID player, int keys);

    /** Le menu /ressource. Thread principal. */
    void openMenu(Player player);
}
