package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bonus de métier sur le monde ressource (config.yml > jobs). Le métier vient d'EterMarket (table
 * etermarket_job_members, relue à l'arrivée puis chaque minute) ; sans métier ou sans EterMarket : aucun bonus.
 * Les effets sont courts et renouvelés toutes les 5 s, et retirés au départ AVANT qu'EterSync n'enregistre le joueur :
 * ils ne suivent jamais le joueur sur un autre serveur.
 */
public class JobBonuses {

    /** Bonus d'un métier. cropMultiplier : 1 = normal ; mobDropBonus, oreDropBonus : 0.5 = +50 %. */
    public record Bonus(List<PotionEffect> effects, boolean treeFelling, int cropMultiplier, double mobDropBonus,
                        double oreDropBonus) {
    }

    private static final long APPLY_TICKS = 5 * 20;
    private static final long RELOAD_TICKS = 60 * 20;
    /** Durée des effets donnés : plus de 10 s, sinon la vision nocturne clignote avant d'être renouvelée. */
    static final int EFFECT_TICKS = 15 * 20;

    private final JavaPlugin plugin;
    private final Database market;
    private final Map<String, Bonus> byJob;
    /** Bonus des joueurs connectés qui ont un métier. */
    private final Map<UUID, Bonus> active = new ConcurrentHashMap<>();
    private volatile boolean warned;

    public JobBonuses(JavaPlugin plugin, Database market) {
        this.plugin = plugin;
        this.market = market;
        this.byJob = read(plugin.getConfig().getConfigurationSection("jobs"));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::apply), APPLY_TICKS, APPLY_TICKS);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId).toList().forEach(this::load), RELOAD_TICKS, RELOAD_TICKS);
    }

    public Optional<Bonus> of(Player player) {
        return Optional.ofNullable(active.get(player.getUniqueId()));
    }

    /** À l'arrivée : métier lu en tâche de fond. */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> load(uuid));
    }

    /** Au départ : bonus oubliés et effets retirés (seulement ceux qu'on a donnés). */
    public void quit(Player player) {
        Bonus bonus = active.remove(player.getUniqueId());
        if (bonus == null) {
            return;
        }
        for (PotionEffect given : bonus.effects()) {
            PotionEffect current = player.getPotionEffect(given.getType());
            if (current != null && current.isAmbient() && current.getAmplifier() == given.getAmplifier()
                    && current.getDuration() <= EFFECT_TICKS) {
                player.removePotionEffect(given.getType());
            }
        }
    }

    private void apply(Player player) {
        Bonus bonus = active.get(player.getUniqueId());
        if (bonus != null) {
            bonus.effects().forEach(player::addPotionEffect);
        }
    }

    /** Bloquant (base). */
    private void load(UUID uuid) {
        try {
            Bonus bonus = market.getFirst("job_members", Map.of("uuid", uuid))
                    .map(row -> byJob.get(row.getString("job")))
                    .orElse(null);
            if (bonus == null) {
                active.remove(uuid);
            } else if (Bukkit.getPlayer(uuid) != null) {
                active.put(uuid, bonus);
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Métiers d'EterMarket illisibles, pas de bonus : " + e.getMessage());
            }
        }
    }

    private Map<String, Bonus> read(ConfigurationSection jobs) {
        Map<String, Bonus> bonuses = new HashMap<>();
        if (jobs == null) {
            return bonuses;
        }
        for (String job : jobs.getKeys(false)) {
            List<PotionEffect> effects = new ArrayList<>();
            for (String entry : jobs.getStringList(job + ".effects")) {
                String[] parts = entry.split(":");
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
                int level = parts.length > 1 ? parseLevel(parts[1]) : 1;
                if (type == null || level < 1) {
                    plugin.getLogger().warning("jobs." + job + ".effects : « " + entry + " » ignoré (effet:niveau, ex : haste:1)");
                    continue;
                }
                // Discret : sans particules, avec l'icône
                effects.add(new PotionEffect(type, EFFECT_TICKS, level - 1, true, false, true));
            }
            bonuses.put(job, new Bonus(List.copyOf(effects), jobs.getBoolean(job + ".tree-felling", false),
                    Math.max(1, jobs.getInt(job + ".crop-multiplier", 1)), Math.max(0, jobs.getDouble(job + ".mob-drop-bonus", 0)),
                    Math.max(0, jobs.getDouble(job + ".ore-drop-bonus", 0))));
        }
        return bonuses;
    }

    private static int parseLevel(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
