package fr.eternom.eterResource.module.bonus;

import org.bukkit.command.CommandSender;

import java.util.Optional;
import java.util.UUID;

/**
 * Le métier et les avantages d'un joueur, connus sur TOUS les serveurs : le menu /ressource les montre aussi depuis la
 * survie, alors qu'ils ne s'appliquent que sur le monde ressource (JobBonuses). Ils viennent d'EterJob (rang compris).
 */
public class JobPerks {

    /** Le métier du joueur (EterJob) ; vide sans métier ou sans EterJob. Bloquant (base). */
    public Optional<String> jobOf(UUID player) {
        return JobLink.jobOf(player);
    }

    /** Les avantages du joueur (son métier et son rang), vide sans métier. Bloquant (base). */
    public Optional<JobBonuses.Bonus> of(UUID player) {
        return JobBonuses.read(player);
    }

    /** Nom affiché du métier dans la langue du lecteur (EterJob), sinon son identifiant. */
    public String name(CommandSender viewer, String job) {
        return JobLink.name(viewer, job);
    }

    /** Nom affiché d'un rang (EterJob), sinon son identifiant. */
    public String rankName(CommandSender viewer, String rank) {
        return JobLink.rankName(viewer, rank);
    }
}
