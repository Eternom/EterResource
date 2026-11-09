package fr.eternom.eterResource.module.bonus;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les avantages de métier sur le monde ressource. Ils viennent d'EterJob (son métier ET son rang : config d'EterJob >
 * perks, cumulés jusqu'à son rang), relus à l'arrivée puis chaque minute ; sans métier ou sans EterJob : aucun.
 * Les effets sont courts et renouvelés toutes les 5 s, et retirés au départ AVANT qu'EterSync n'enregistre le joueur :
 * ils ne suivent jamais le joueur sur un autre serveur.
 */
public class JobBonuses {

    /**
     * Les avantages d'un joueur : son métier, son rang, ses effets, et ses valeurs (tree-felling, ore-drop-bonus,
     * vein-mining, auto-smelt, crop-multiplier, replant, hoe-area, sapling-replant, apple-chance, treasure-bonus, lure,
     * mob-drop-bonus, xp-multiplier, head-chance).
     */
    public record Bonus(String job, String rank, List<PotionEffect> effects, Map<String, Double> values) {

        public double value(String key) {
            return values.getOrDefault(key, 0.0);
        }

        public boolean has(String key) {
            return value(key) > 0;
        }

        public boolean treeFelling() {
            return has("tree-felling");
        }

        public int cropMultiplier() {
            return Math.max(1, (int) value("crop-multiplier"));
        }

        public double mobDropBonus() {
            return value("mob-drop-bonus");
        }

        public double oreDropBonus() {
            return value("ore-drop-bonus");
        }
    }

    private static final long APPLY_TICKS = 5 * 20;
    private static final long RELOAD_TICKS = 60 * 20;
    /** Durée des effets donnés : plus de 10 s, sinon la vision nocturne clignote avant d'être renouvelée. */
    static final int EFFECT_TICKS = 15 * 20;

    private final JavaPlugin plugin;
    private final fr.eternom.eterLib.helper.message.Messages messages;
    /** Avantages des joueurs connectés qui ont un métier. */
    private final Map<UUID, Bonus> active = new ConcurrentHashMap<>();
    private volatile boolean warned;

    public JobBonuses(JavaPlugin plugin, fr.eternom.eterLib.helper.message.Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::apply), APPLY_TICKS, APPLY_TICKS);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId).toList().forEach(this::load), RELOAD_TICKS, RELOAD_TICKS);
    }

    public Optional<Bonus> of(Player player) {
        return Optional.ofNullable(active.get(player.getUniqueId()));
    }

    /** À l'arrivée : avantages lus en tâche de fond, puis rappelés au joueur (son métier, son rang). */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            load(uuid);
            Bonus bonus = active.get(uuid);
            if (bonus != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        messages.send(player, "bonus.active", "job", JobLink.name(player, bonus.job()),
                                "rank", JobLink.rankName(player, bonus.rank()));
                    }
                }, 40);
            }
        });
    }

    /** Au départ : avantages oubliés et effets retirés (seulement ceux qu'on a donnés). */
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
            Optional<Bonus> bonus = read(uuid);
            if (bonus.isEmpty()) {
                active.remove(uuid);
            } else if (Bukkit.getPlayer(uuid) != null) {
                active.put(uuid, bonus.get());
            }
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Métiers d'EterJob illisibles, pas d'avantages : " + e.getMessage());
            }
        }
    }

    /** Les avantages d'un joueur selon EterJob (métier et rang). Bloquant. */
    static Optional<Bonus> read(UUID uuid) {
        return JobLink.perksOf(uuid).map(perks -> {
            List<PotionEffect> effects = new ArrayList<>();
            perks.effects().forEach((name, amplifier) -> {
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                if (type != null) {
                    // Discret : sans particules, avec l'icône
                    effects.add(new PotionEffect(type, EFFECT_TICKS, Math.max(0, amplifier), true, false, true));
                }
            });
            return new Bonus(perks.job(), perks.rank(), List.copyOf(effects), Map.copyOf(perks.values()));
        });
    }
}
