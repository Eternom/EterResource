package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterJob.api.JobApi;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Le métier d'un joueur et les avantages de son rang, demandés à EterJob (JobApi). Classe à part : ses appels ne
 * touchent EterJob que s'il tourne ici (sinon ses classes n'existent pas sur le serveur). Bloquant (base, cache 30 s
 * côté EterJob).
 */
final class JobLink {

    /** Les avantages d'un joueur : son métier, son rang, ses effets (nom -> amplificateur) et valeurs (voir EterJob > perks). */
    record Perks(String job, String rank, Map<String, Integer> effects, Map<String, Double> values) {
    }

    private JobLink() {
    }

    private static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("EterJob");
    }

    static Optional<String> jobOf(UUID player) {
        return present() ? Api.job(player) : Optional.empty();
    }

    static Optional<Perks> perksOf(UUID player) {
        return present() ? Api.perks(player) : Optional.empty();
    }

    /** Nom affiché du métier (EterJob), sinon son identifiant. */
    static String name(CommandSender viewer, String job) {
        return present() ? Api.name(viewer, job) : job;
    }

    /** Nom affiché d'un rang (EterJob), sinon son identifiant. */
    static String rankName(CommandSender viewer, String rank) {
        return present() ? Api.rankName(viewer, rank) : rank;
    }

    /** Seule classe qui touche à JobApi : jamais chargée sans EterJob. */
    private static final class Api {

        static Optional<String> job(UUID player) {
            return JobApi.get().flatMap(jobs -> jobs.job(player));
        }

        static Optional<Perks> perks(UUID player) {
            return JobApi.get().map(jobs -> jobs.perks(player)).filter(perks -> !perks.job().isEmpty())
                    .map(perks -> new Perks(perks.job(), perks.rank(), perks.effects(), perks.values()));
        }

        static String name(CommandSender viewer, String job) {
            return JobApi.get().map(jobs -> jobs.jobName(viewer, job)).orElse(job);
        }

        static String rankName(CommandSender viewer, String rank) {
            return JobApi.get().map(jobs -> jobs.rankName(viewer, rank)).orElse(rank);
        }
    }
}
