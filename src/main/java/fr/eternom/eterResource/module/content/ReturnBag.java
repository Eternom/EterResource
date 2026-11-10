package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le sac de retour : un petit coffre personnel, commun au réseau (eterresource_bags). Sur un monde ressource il s'ouvre au
 * camp (poste « sac ») pour y mettre ce qu'on veut ramener ; ailleurs (survie, lobby) avec /resource bag pour le vider.
 * Taille : bag.rows (1 ligne par défaut), plus avec la permission eterresource.bag.<lignes> (3 au plus). Enregistré à la
 * fermeture ; jamais ouvert deux fois en même temps.
 */
public class ReturnBag implements Listener {

    private static final String PERMISSION = "eterresource.bag.";

    private final JavaPlugin plugin;
    private final ContentStore store;
    private final Messages messages;
    private final int defaultRows;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    /** Le coffre ouvert (pour le reconnaître à la fermeture). */
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public ReturnBag(JavaPlugin plugin, ContentStore store, Messages messages) {
        this.plugin = plugin;
        this.store = store;
        this.messages = messages;
        this.defaultRows = Math.clamp(plugin.getConfig().getInt("bag.rows", 1), 1, 3);
    }

    public int rows(Player player) {
        int rows = defaultRows;
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            if (permission.getValue() && permission.getPermission().startsWith(PERMISSION)) {
                try {
                    rows = Math.max(rows, Integer.parseInt(permission.getPermission().substring(PERMISSION.length())));
                } catch (NumberFormatException ignored) {
                    // autre permission : ignorée
                }
            }
        }
        return Math.clamp(rows, 1, 3);
    }

    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        if (!opening.add(uuid)) {
            return;
        }
        int size = rows(player) * 9;
        Tasks.async(plugin, player, () -> store.bag(uuid, 27), items -> {
            Holder holder = new Holder(uuid);
            Inventory inventory = Bukkit.createInventory(holder, size, messages.get(player, "bag.title"));
            holder.inventory = inventory;
            inventory.setContents(Arrays.copyOf(items, size));
            // Ce qui dépasse (sac devenu plus petit) reste gardé en base, hors de vue
            player.openInventory(inventory);
            player.playSound(player, Sound.ITEM_BUNDLE_INSERT, 0.8f, 1f);
            messages.actionBar(player, "bag.opened");
        }, () -> {
            opening.remove(uuid);
            messages.send(player, "error.generic");
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        ItemStack[] shown = event.getInventory().getContents().clone();
        UUID uuid = holder.owner;
        Tasks.async(plugin, () -> {
            try {
                ItemStack[] stored = store.bag(uuid, 27);
                System.arraycopy(shown, 0, stored, 0, Math.min(shown.length, stored.length));
                store.saveBag(uuid, stored);
            } finally {
                opening.remove(uuid);
            }
        }, "Sac de retour non enregistré pour " + uuid);
        if (event.getPlayer() instanceof Player player) {
            player.playSound(player, Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // La fermeture (onClose) arrive avant le départ ; au cas où, le verrou est levé
        Bukkit.getScheduler().runTaskLater(plugin, () -> opening.remove(event.getPlayer().getUniqueId()), 40);
    }
}
