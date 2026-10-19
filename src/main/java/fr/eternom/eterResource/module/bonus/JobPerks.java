package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterResource.module.bonus.JobBonuses.Bonus;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Les bonus de chaque métier (config.yml > jobs), connus sur TOUS les serveurs : le menu /ressource les montre aussi
 * depuis la survie, alors qu'ils ne s'appliquent que sur le monde ressource (JobBonuses).
 */
public class JobPerks {

    private final Map<String, Bonus> byJob;

    public JobPerks(ConfigurationSection jobs, Logger logger) {
        this.byJob = JobBonuses.read(jobs, logger);
    }

    /** Le métier du joueur (EterMarket) ; vide sans métier ou sans EterMarket. Bloquant (base). */
    public Optional<String> jobOf(UUID player) {
        return MarketJobs.jobOf(player);
    }

    /** Les bonus de ce métier, vide s'il n'en a pas. */
    public Optional<Bonus> of(String job) {
        return Optional.ofNullable(byJob.get(job));
    }

    /** Nom affiché du métier dans la langue du lecteur (EterMarket), sinon son identifiant. */
    public String name(CommandSender viewer, String job) {
        return MarketJobs.name(viewer, job);
    }
}
