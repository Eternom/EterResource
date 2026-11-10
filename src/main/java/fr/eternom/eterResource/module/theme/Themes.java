package fr.eternom.eterResource.module.theme;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Les thèmes des mondes ressources (config themes) et leur rotation (rotation : un ou plusieurs thèmes par jour de la
 * semaine, au hasard parmi eux). Un thème : les biomes où le camp de base s'installe (le centre du monde), la taille de
 * la bordure, la durée de vie, le bonus de minerais, les bonus de métier, l'icône, et s'il est « événement » (plus
 * d'événements dans le monde). Nom et description : lang/ theme.<id>.
 */
public final class Themes {

    /**
     * Un thème. biomes : où placer le camp (vide : le spawn du monde) ; border : côté de la bordure ; lifetimeHours :
     * durée de vie du monde ; oreMultiplier : minerais en plus (1 : comme d'habitude) ; jobBonus : métier -> chance de
     * récolte en plus ; eventBoost : multiplie la fréquence des événements.
     */
    public record Theme(String id, List<Biome> biomes, int border, double lifetimeHours, double oreMultiplier,
                        Map<String, Double> jobBonus, Material icon, double eventBoost) {

        public Theme {
            biomes = List.copyOf(biomes);
            jobBonus = Map.copyOf(jobBonus);
        }
    }

    public static final Theme DEFAULT = new Theme("classique", List.of(), 6000, 12, 1, Map.of(), Material.GRASS_BLOCK, 1);

    private final Map<String, Theme> themes;
    private final Map<DayOfWeek, List<String>> rotation;

    private Themes(Map<String, Theme> themes, Map<DayOfWeek, List<String>> rotation) {
        this.themes = themes;
        this.rotation = rotation;
    }

    public static Themes load(ConfigurationSection config, Logger logger) {
        Map<String, Theme> themes = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("themes");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection theme = section.getConfigurationSection(id);
                if (theme == null) {
                    continue;
                }
                List<Biome> biomes = new ArrayList<>();
                for (String name : theme.getStringList("biomes")) {
                    Biome biome = biome(name);
                    if (biome == null) {
                        logger.warning("themes." + id + ".biomes : biome inconnu « " + name + " »");
                    } else {
                        biomes.add(biome);
                    }
                }
                Map<String, Double> jobBonus = new LinkedHashMap<>();
                ConfigurationSection jobs = theme.getConfigurationSection("job-bonus");
                if (jobs != null) {
                    jobs.getKeys(false).forEach(job -> jobBonus.put(job.toLowerCase(Locale.ROOT), Math.max(0, jobs.getDouble(job))));
                }
                Material icon = Material.matchMaterial(theme.getString("icon", "GRASS_BLOCK"));
                themes.put(id.toLowerCase(Locale.ROOT), new Theme(id.toLowerCase(Locale.ROOT), biomes,
                        Math.max(0, theme.getInt("border", config.getInt("world.border.normal", 6000))),
                        Math.max(1, theme.getDouble("lifetime-hours", 12)), Math.max(0, theme.getDouble("ore-multiplier", 1)),
                        jobBonus, icon == null || !icon.isItem() ? Material.GRASS_BLOCK : icon,
                        Math.max(0, theme.getDouble("event-boost", 1))));
            }
        }
        Map<DayOfWeek, List<String>> rotation = new EnumMap<>(DayOfWeek.class);
        ConfigurationSection days = config.getConfigurationSection("rotation");
        if (days != null) {
            for (String day : days.getKeys(false)) {
                try {
                    DayOfWeek dayOfWeek = DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT));
                    List<String> ids = days.isList(day) ? days.getStringList(day) : List.of(days.getString(day, ""));
                    List<String> known = ids.stream().map(id -> id.toLowerCase(Locale.ROOT)).filter(themes::containsKey).toList();
                    if (known.size() < ids.size()) {
                        logger.warning("rotation." + day + " : thème inconnu dans " + ids);
                    }
                    rotation.put(dayOfWeek, known);
                } catch (IllegalArgumentException e) {
                    logger.warning("rotation : jour inconnu « " + day + " » (monday...sunday)");
                }
            }
        }
        return new Themes(Map.copyOf(themes), rotation);
    }

    private static Biome biome(String name) {
        try {
            return Registry.BIOME.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Optional<Theme> get(String id) {
        return Optional.ofNullable(themes.get(id == null ? "" : id.toLowerCase(Locale.ROOT)));
    }

    public List<String> ids() {
        return List.copyOf(themes.keySet());
    }

    /** Le thème du jour (heure de Paris) : au hasard parmi ceux de la rotation, sinon parmi tous, sinon classique. */
    public Theme today(ZoneId zone) {
        List<String> candidates = rotation.getOrDefault(LocalDate.now(zone).getDayOfWeek(), List.of());
        if (candidates.isEmpty()) {
            candidates = ids();
        }
        if (candidates.isEmpty()) {
            return DEFAULT;
        }
        return themes.get(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }
}
