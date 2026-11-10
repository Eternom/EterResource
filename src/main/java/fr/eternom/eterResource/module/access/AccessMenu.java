package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import fr.eternom.eterResource.module.access.AccessService.Offer;
import fr.eternom.eterResource.module.bonus.JobBonuses.Bonus;
import fr.eternom.eterResource.module.world.WorldDirectory.WorldInfo;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Menu /resource, 6 lignes :
 * <pre>
 *  ▣ ▣ ▣ ▣ ☺ ▣ ▣ ▣ ▣     ☺ = joueur : temps, clés, minutes du jour, métier et ses avantages ici
 *  ▣ · · · · · · · ▣
 *  ▣ · A · B · C · K     A B C = offres de temps (heure creuse : remise) · K = utiliser une clé
 *  ▣ · · · · · · · ▣
 *  ▣ m m m m m m m ▣     les mondes ouverts : thème, joueurs, fermeture ; clic pour y aller
 *  « ▣ ☰ ▣ ⇒ ▣ ✉ ▣ ▣     retour · historique · partir (le moins rempli) · offrir du temps
 * </pre>
 */
public class AccessMenu implements Menu {

    private static final int HEAD = 4;
    private static final int[] OFFERS = {20, 22, 24};
    private static final int KEY = 26;
    private static final int[] WORLDS = {37, 38, 39, 40, 41, 42, 43};
    private static final int BACK = 45;
    private static final int HISTORY = 47;
    private static final int GO = 49;
    private static final int GIFT = 51;
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

    private final JavaPlugin plugin;
    private final AccessService service;
    private final Messages messages;
    private final BackButton back;
    private final Player viewer;
    private final Access access;
    /** Métier, rang et avantages du joueur (EterJob), null s'il n'a pas de métier. */
    private final Bonus bonus;
    private final Inventory inventory;
    private final Map<Integer, Offer> offerAt = new HashMap<>();
    private final Map<Integer, String> worldAt = new HashMap<>();

    private AccessMenu(JavaPlugin plugin, AccessService service, Messages messages, BackButton back, Player viewer, Access access,
                       Bonus bonus) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
        this.back = back;
        this.viewer = viewer;
        this.access = access;
        this.bonus = bonus;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "menu.title"));
        render();
    }

    /** Lit le temps, les clés et le métier du joueur, puis ouvre le menu. */
    public static void open(JavaPlugin plugin, AccessService service, Messages messages, BackButton back, Player player) {
        service.load(player, access -> Tasks.async(plugin, player, () -> service.perks().of(player.getUniqueId()),
                bonus -> player.openInventory(new AccessMenu(plugin, service, messages, back, player, access,
                        bonus.orElse(null)).getInventory()),
                () -> messages.send(player, "error.generic")));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> open(plugin, service, messages, back, player);
        if (offerAt.containsKey(slot)) {
            Sounds.click(player);
            confirmBuy(player, offerAt.get(slot), reopen);
            return;
        }
        if (worldAt.containsKey(slot)) {
            Sounds.click(player);
            service.go(player, access, worldAt.get(slot));
            return;
        }
        switch (slot) {
            case KEY -> {
                Sounds.click(player);
                service.useKey(player, access, reopen);
            }
            case GO -> {
                Sounds.click(player);
                service.go(player, access);
            }
            case HISTORY -> {
                Sounds.page(player);
                service.history(player, entries -> player.openInventory(new HistoryMenu(player, entries, reopen).getInventory()));
            }
            case GIFT -> {
                Sounds.click(player);
                player.closeInventory();
                messages.send(player, "gift.help");
            }
            case BACK -> back.click(player);
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void confirmBuy(Player player, Offer offer, Runnable reopen) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "buy.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "buy.body",
                        "time", time(offer.millis()), "price", Money.format(service.priceOf(offer)),
                        "max", time(service.maxMillis())))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "buy.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.buy(player, offer, reopen), reopen);
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        List<Component> head = new ArrayList<>(List.of(
                messages.get(viewer, "menu.head.time", "time", time(access.remainingMillis()), "max", time(service.maxMillis())),
                messages.get(viewer, "menu.head.keys", "keys", String.valueOf(access.keys())),
                messages.get(viewer, "menu.head.daily", "minutes", String.valueOf(service.dailyMinutes(viewer))),
                Component.empty()));
        head.addAll(perks());
        inventory.setItem(HEAD, Items.head(viewer.getPlayerProfile(), messages.get(viewer, "menu.head.name", "player", viewer.getName()), head));
        boolean offPeak = service.offPeak();
        List<Offer> offers = service.offers();
        for (int i = 0; i < OFFERS.length && i < offers.size(); i++) {
            Offer offer = offers.get(i);
            offerAt.put(OFFERS[i], offer);
            double perMinute = offer.price() / offer.minutes();
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, offPeak ? "menu.buy.price-off-peak" : "menu.buy.price", "price", Money.format(service.priceOf(offer)),
                    "old", Money.format(offer.price())));
            lore.add(messages.get(viewer, "menu.buy.per-minute", "price", Money.format(Math.round(perMinute * 100) / 100.0)));
            if (offPeak) {
                lore.add(messages.get(viewer, "menu.buy.off-peak", "percent", String.valueOf(Math.round(service.offPeakDiscount() * 100))));
            }
            lore.add(messages.get(viewer, "menu.buy.click"));
            inventory.setItem(OFFERS[i], Items.item(i == 0 ? Material.GOLD_NUGGET : i == 1 ? Material.GOLD_INGOT : Material.GOLD_BLOCK,
                    messages.get(viewer, "menu.buy.name", "time", time(offer.millis())), lore, offPeak));
        }
        inventory.setItem(KEY, Items.item(Material.TRIPWIRE_HOOK, messages.get(viewer, "menu.key.name", "time", time(service.slotMillis())),
                List.of(messages.get(viewer, "menu.key.count", "keys", String.valueOf(access.keys()))), access.keys() > 0));
        renderWorlds();
        inventory.setItem(BACK, back.item(viewer));
        inventory.setItem(HISTORY, Items.item(Material.BOOK, messages.get(viewer, "menu.history.name"),
                List.of(messages.get(viewer, "menu.history.lore"))));
        if (service.isWorldServer()) {
            inventory.setItem(GO, Items.item(Material.GRASS_BLOCK, messages.get(viewer, "menu.go.name"),
                    List.of(messages.get(viewer, "menu.go.here")), true));
        } else {
            inventory.setItem(GO, Items.item(Material.ENDER_PEARL, messages.get(viewer, "menu.go.name"),
                    List.of(messages.get(viewer, "menu.go.worlds", "count", String.valueOf(service.openWorlds())),
                            messages.get(viewer, access.remainingMillis() > 0 ? "menu.go.click" : "menu.go.no-time"))));
        }
        inventory.setItem(GIFT, Items.item(Material.CAKE, messages.get(viewer, "menu.gift.name"), List.of(messages.get(viewer, "menu.gift.lore"))));
    }

    /** Les mondes ouverts : thème, joueurs, fermeture, TPS ; clic pour y aller. */
    private void renderWorlds() {
        List<WorldInfo> worlds = service.worlds();
        if (worlds.isEmpty()) {
            inventory.setItem(WORLDS[3], Items.item(Material.BARRIER, messages.get(viewer, "menu.worlds.none"),
                    List.of(messages.get(viewer, "menu.worlds.none-lore"))));
            return;
        }
        Function<String, Material> icon = theme -> {
            String raw = messages.raw(viewer, "theme." + theme + ".icon");
            Material material = raw == null ? null : Material.matchMaterial(raw);
            return material == null ? Material.GRASS_BLOCK : material;
        };
        long now = System.currentTimeMillis();
        for (int i = 0; i < WORLDS.length && i < worlds.size(); i++) {
            WorldInfo world = worlds.get(i);
            worldAt.put(WORLDS[i], world.name());
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, "menu.worlds.theme", "theme", messages.plain(viewer, "theme." + world.theme() + ".name")));
            lore.add(messages.get(viewer, "menu.worlds.players", "count", String.valueOf(Math.max(service.playersOn(world.name()), world.players()))));
            if (world.closeAt() > 0) {
                lore.add(messages.get(viewer, "menu.worlds.closes", "time", time(world.closeAt() - now)));
            }
            if (world.tps() < 15) {
                lore.add(messages.get(viewer, "menu.worlds.laggy"));
            }
            lore.add(messages.get(viewer, service.isWorldServer() ? "menu.go.here" : "menu.worlds.click"));
            inventory.setItem(WORLDS[i], Items.item(icon.apply(world.theme()),
                    messages.get(viewer, "menu.worlds.name", "server", EterLib.get().getServerDisplayName(world.name())), lore));
        }
    }

    /** Le métier et ce qu'il apporte sur le monde ressource ; sinon, comment en avoir. */
    private List<Component> perks() {
        if (bonus == null) {
            return List.of(messages.get(viewer, "menu.head.no-job"));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get(viewer, "menu.head.job-rank", "job", service.perks().name(viewer, bonus.job()),
                "rank", service.perks().rankName(viewer, bonus.rank())));
        if (bonus.effects().isEmpty() && bonus.values().values().stream().noneMatch(value -> value > 0)) {
            lines.add(messages.get(viewer, "menu.head.no-perk"));
            return lines;
        }
        for (PotionEffect effect : bonus.effects()) {
            int level = effect.getAmplifier() + 1;
            Component name = Component.translatable(effect.getType())
                    .append(Component.text(" " + (level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level))));
            lines.add(messages.get(viewer, "menu.head.perk.effect", Placeholder.component("effect", name)));
        }
        bonus.values().forEach((key, value) -> {
            if (value > 0 && messages.raw(viewer, "menu.head.perk." + key) != null) {
                lines.add(messages.get(viewer, "menu.head.perk." + key, "percent", String.valueOf(Math.round(value * 100)),
                        "value", value == Math.rint(value) ? String.valueOf((long) (double) value) : String.valueOf(value)));
            }
        });
        lines.add(messages.get(viewer, "menu.head.perk.where"));
        return lines;
    }

    private String time(long millis) {
        return service.formatMillis(viewer, millis);
    }

    /** L'historique du temps (21 dernières lignes, 90 jours). */
    private final class HistoryMenu implements Menu {

        private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm");
        private final Inventory history;
        private final Runnable back;

        HistoryMenu(Player viewer, List<AccessRepository.Entry> entries, Runnable back) {
            this.back = back;
            this.history = Bukkit.createInventory(this, 36, messages.get(viewer, "menu.history.title"));
            Frame.draw(history, Material.ORANGE_STAINED_GLASS_PANE);
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
            for (int i = 0; i < slots.length && i < entries.size(); i++) {
                AccessRepository.Entry entry = entries.get(i);
                String reason = entry.reason();
                String detail = reason.contains(":") ? reason.substring(reason.indexOf(':') + 1) : "";
                String key = "history." + (reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason);
                Component label = messages.raw(viewer, key) != null ? messages.get(viewer, key, "detail", detail) : Component.text(reason);
                boolean gain = entry.deltaMillis() > 0;
                history.setItem(slots[i], Items.item(gain ? Material.LIME_DYE : Material.RED_DYE, label, List.of(
                        messages.get(viewer, gain ? "menu.history.gain" : "menu.history.loss", "time", time(Math.abs(entry.deltaMillis()))),
                        messages.get(viewer, "menu.history.date", "date", DATE.format(new Date(entry.at()))))));
            }
            if (entries.isEmpty()) {
                history.setItem(13, Items.item(Material.PAPER, messages.get(viewer, "menu.history.empty"), List.of()));
            }
            history.setItem(31, Items.item(Material.ARROW, messages.get(viewer, "menu.history.back"), List.of()));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == 31) {
                Sounds.page(player);
                back.run();
            }
        }

        @Override
        public Inventory getInventory() {
            return history;
        }
    }
}
