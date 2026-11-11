package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.world.Camp;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Au camp : le marchand (shop.items : du matériel de base en lots, payé par EterEconomy ; clic = 1 lot, Maj + clic = 4)
 * et le marchand ambulant (merchant : il passe au camp toutes les interval-minutes et reste stay-minutes, avec ses
 * échanges propres au réseau, sans ceux de Minecraft).
 */
public class CampShop {

    /** Un lot à vendre. */
    private record Lot(Material item, int amount, double price) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final Camp camp;
    private final List<Lot> lots = new ArrayList<>();
    private WanderingTrader trader;

    public CampShop(JavaPlugin plugin, Messages messages, Camp camp) {
        this.plugin = plugin;
        this.messages = messages;
        this.camp = camp;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("shop.items")) {
            Material item = raw.get("item") == null ? null : Material.matchMaterial(String.valueOf(raw.get("item")));
            Object amount = raw.get("amount");
            Object price = raw.get("price");
            if (item == null || !item.isItem() || !(amount instanceof Number a) || !(price instanceof Number p)) {
                plugin.getLogger().warning("shop.items : lot illisible " + raw);
                continue;
            }
            lots.add(new Lot(item, Math.max(1, a.intValue()), Math.max(0, p.doubleValue())));
        }
    }

    public void start() {
        ConfigurationSection merchant = plugin.getConfig().getConfigurationSection("merchant");
        if (merchant == null || !merchant.getBoolean("enabled", true)) {
            return;
        }
        long interval = Math.max(5, merchant.getLong("interval-minutes", 45)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimer(plugin, this::visit, interval / 3, interval);
    }

    // ---------- Le marchand du camp ----------

    public void open(Player player) {
        if (lots.isEmpty()) {
            messages.send(player, "shop.empty");
            return;
        }
        player.openInventory(new Stall(player).getInventory());
    }

    private void buy(Player player, Lot lot, int times) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        ItemStack stack = ItemStack.of(lot.item(), Math.min(lot.amount() * times, lot.item().getMaxStackSize() * times));
        double price = Math.round(lot.price() * times * 100) / 100.0;
        Tasks.async(plugin, player, () -> price <= 0 || economy.withdraw(player.getUniqueId(), price, "EterResource · marchand du camp"),
                paid -> {
                    if (!paid) {
                        messages.send(player, "shop.not-enough", "price", Money.format(price));
                        Sounds.refuse(player);
                        return;
                    }
                    player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                    messages.send(player, "shop.bought", "amount", String.valueOf(stack.getAmount()), "price", Money.format(price),
                            "item", lot.item().name().toLowerCase());
                    player.playSound(player, Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
                }, () -> messages.send(player, "error.generic"));
    }

    private final class Stall implements Menu {

        private final Inventory inventory;
        private final Map<Integer, Lot> lotAt = new HashMap<>();
        private final BackButton back = EterLib.get().backButton("close");
        private int backSlot;

        Stall(Player viewer) {
            int rows = Math.min(6, 2 + (lots.size() + 6) / 7);
            inventory = Bukkit.createInventory(this, rows * 9, messages.get(viewer, "shop.title"));
            Frame.draw(inventory, Material.GREEN_STAINED_GLASS_PANE);
            int slot = 10;
            for (Lot lot : lots) {
                if (slot % 9 == 8) {
                    slot += 2;
                }
                if (slot >= rows * 9 - 9) {
                    break;
                }
                lotAt.put(slot, lot);
                inventory.setItem(slot, Items.item(lot.item(), Component.translatable(lot.item().translationKey())
                                .append(Component.text(" ×" + lot.amount())),
                        List.of(messages.get(viewer, "shop.price", "price", Money.format(lot.price())),
                                messages.get(viewer, "shop.click"))));
                slot++;
            }
            backSlot = rows * 9 - 5;
            inventory.setItem(backSlot, back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == backSlot) {
                back.click(player);
                return;
            }
            Lot lot = lotAt.get(slot);
            if (lot != null) {
                Sounds.click(player);
                buy(player, lot, click.isShiftClick() ? 4 : 1);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------- Le marchand ambulant ----------

    /** Le marchand ambulant passe au camp, avec les échanges de merchant.trades, puis repart. */
    private void visit() {
        Location center = camp.center();
        if (center == null || trader != null && trader.isValid() || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        List<MerchantRecipe> recipes = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("merchant.trades")) {
            ItemStack result = stack(raw.get("result"));
            ItemStack cost = stack(raw.get("cost"));
            if (result == null || cost == null) {
                continue;
            }
            Object uses = raw.get("uses");
            MerchantRecipe recipe = new MerchantRecipe(result, uses instanceof Number n ? n.intValue() : 4);
            recipe.addIngredient(cost);
            recipes.add(recipe);
        }
        if (recipes.isEmpty()) {
            return;
        }
        Location at = center.clone().add(4, 0, 0);
        trader = center.getWorld().spawn(at, WanderingTrader.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setDespawnDelay((int) Math.max(1, plugin.getConfig().getLong("merchant.stay-minutes", 10)) * 60 * 20);
            spawned.setRecipes(recipes);
            spawned.setAI(false);
            spawned.setInvulnerable(true);
        });
        Bukkit.getOnlinePlayers().forEach(player -> {
            messages.send(player, "merchant.arrived");
            player.playSound(player, Sound.ENTITY_WANDERING_TRADER_YES, 0.8f, 1f);
        });
    }

    /** « DIAMOND 2 » -> l'objet. */
    private static ItemStack stack(Object raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = String.valueOf(raw).trim().split("\\s+");
        Material material = Material.matchMaterial(parts[0]);
        if (material == null || !material.isItem()) {
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // 1
            }
        }
        return ItemStack.of(material, Math.clamp(amount, 1, material.getMaxStackSize()));
    }
}
