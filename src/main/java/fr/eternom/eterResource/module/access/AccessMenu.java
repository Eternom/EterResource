package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Menu /ressource, 3 lignes : le joueur (temps restant, clés), acheter un créneau, utiliser une clé, partir.
 * Ouvert avec le temps et les clés lus juste avant ; rouvert à jour après un achat ou une clé.
 */
public class AccessMenu implements Menu {

    private static final int HEAD = 10;
    private static final int BUY = 12;
    private static final int KEY = 14;
    private static final int GO = 16;
    private static final int BACK = 22;

    private final JavaPlugin plugin;
    private final AccessService service;
    private final Messages messages;
    private final BackButton back;
    private final Player viewer;
    private final Access access;
    private final Inventory inventory;

    private AccessMenu(JavaPlugin plugin, AccessService service, Messages messages, BackButton back, Player viewer, Access access) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
        this.back = back;
        this.viewer = viewer;
        this.access = access;
        this.inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "menu.title"));
        render();
    }

    /** Lit le temps et les clés du joueur, puis ouvre le menu. */
    public static void open(JavaPlugin plugin, AccessService service, Messages messages, BackButton back, Player player) {
        service.load(player, access -> player.openInventory(
                new AccessMenu(plugin, service, messages, back, player, access).getInventory()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> open(plugin, service, messages, back, player);
        switch (slot) {
            case BUY -> {
                Sounds.click(player);
                confirmBuy(player, reopen);
            }
            case KEY -> {
                Sounds.click(player);
                service.useKey(player, access, reopen);
            }
            case GO -> {
                Sounds.click(player);
                service.go(player, access);
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

    private void confirmBuy(Player player, Runnable reopen) {
        if (service.slotPrice() <= 0) {
            messages.send(player, "access.buy-disabled");
            return;
        }
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "buy.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "buy.body",
                        "time", time(service.slotMillis()), "price", Money.format(service.slotPrice()),
                        "max", time(service.maxMillis())))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "buy.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.buy(player, reopen), reopen);
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(HEAD, Items.head(viewer.getPlayerProfile(), messages.get(viewer, "menu.head.name", "player", viewer.getName()),
                List.of(messages.get(viewer, "menu.head.time", "time", time(access.remainingMillis()), "max", time(service.maxMillis())),
                        messages.get(viewer, "menu.head.keys", "keys", String.valueOf(access.keys())))));
        inventory.setItem(BUY, Items.item(Material.GOLD_INGOT, messages.get(viewer, "menu.buy.name", "time", time(service.slotMillis())),
                List.of(service.slotPrice() > 0
                        ? messages.get(viewer, "menu.buy.price", "price", Money.format(service.slotPrice()))
                        : messages.get(viewer, "menu.buy.disabled"))));
        inventory.setItem(KEY, Items.item(Material.TRIPWIRE_HOOK, messages.get(viewer, "menu.key.name", "time", time(service.slotMillis())),
                List.of(messages.get(viewer, "menu.key.count", "keys", String.valueOf(access.keys()))), access.keys() > 0));
        if (service.isWorldServer()) {
            inventory.setItem(GO, Items.item(Material.GRASS_BLOCK, messages.get(viewer, "menu.go.name"),
                    List.of(messages.get(viewer, "menu.go.here")), true));
        } else {
            inventory.setItem(GO, Items.item(Material.GRASS_BLOCK, messages.get(viewer, "menu.go.name"),
                    List.of(messages.get(viewer, "menu.go.worlds", "count", String.valueOf(service.openWorlds())),
                            messages.get(viewer, access.remainingMillis() > 0 ? "menu.go.click" : "menu.go.no-time"))));
        }
        inventory.setItem(BACK, back.item(viewer));
    }

    private String time(long millis) {
        return service.formatMillis(viewer, millis);
    }
}
