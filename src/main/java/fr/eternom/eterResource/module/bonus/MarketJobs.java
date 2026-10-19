package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterMarket.api.MarketApi;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

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

    /** Nom affiché du métier (EterMarket), sinon son identifiant. */
    static String name(CommandSender viewer, String job) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterMarket")) {
            return job;
        }
        return Api.name(viewer, job);
    }

    /** Seule classe qui touche à MarketApi : jamais chargée sans EterMarket. */
    private static final class Api {

        static Optional<String> job(UUID player) {
            return MarketApi.get().flatMap(market -> market.job(player));
        }

        static String name(CommandSender viewer, String job) {
            return MarketApi.get().map(market -> market.jobName(viewer, job)).orElse(job);
        }
    }
}
