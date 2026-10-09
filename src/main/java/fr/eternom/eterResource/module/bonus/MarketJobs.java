package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterMarket.api.MarketApi;
import org.bukkit.Bukkit;

import java.util.Optional;
import java.util.UUID;

/**
 * Le métier d'un joueur, demandé à EterMarket (MarketApi). Classe à part : chargée seulement si EterMarket tourne
 * (sinon ses classes n'existent pas sur le serveur). Bloquant (base).
 */
final class MarketJobs {

    private MarketJobs() {
    }

    static Optional<String> jobOf(UUID player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterMarket")) {
            return Optional.empty();
        }
        return Api.job(player);
    }

    /** Seule classe qui touche à MarketApi : jamais chargée sans EterMarket. */
    private static final class Api {

        static Optional<String> job(UUID player) {
            return MarketApi.get().flatMap(market -> market.job(player));
        }
    }
}
