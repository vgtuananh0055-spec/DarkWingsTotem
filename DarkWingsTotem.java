package vn.darkwings;

import io.lumine.mythic.lib.api.item.NBTItem;
import org.bukkit.ChatColor;
import org.bukkit.EntityEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Biến bất kỳ item MMOItems nào thành "totem": khi mặc/cầm đúng ô quy định và dính đòn chí mạng,
 * item cứu người chơi (không bị mất), sau đó phải chờ hồi chiêu.
 * Mỗi item có cooldown, ô trang bị, hiệu ứng và dòng lore riêng, cấu hình trong config.yml.
 *
 * @author KaiTouu
 */
public class DarkWingsTotem extends JavaPlugin implements Listener {

    private record Totem(String id, EquipmentSlot slot, long cooldownMs, double health,
                         List<PotionEffect> effects, String message, String readyMessage,
                         String loreMarker, String loreReady, String loreCooling) {
    }

    private final Map<String, Totem> totems = new HashMap<>();
    private final Set<EquipmentSlot> usedSlots = EnumSet.noneOf(EquipmentSlot.class);
    /** người chơi -> (ID item -> thời điểm hồi xong) */
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        loadSettings();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "Đã tải lại config DarkWingsTotem (" + totems.size() + " item).");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "Dùng: /" + label + " reload");
        return true;
    }

    private void loadSettings() {
        reloadConfig();
        totems.clear();
        usedSlots.clear();

        ConfigurationSection items = getConfig().getConfigurationSection("items");
        if (items == null) {
            getLogger().warning("config.yml không có mục 'items' (có thể là config bản cũ). "
                    + "Hãy xóa config.yml rồi restart để tạo lại.");
        } else {
            for (String id : items.getKeys(false)) {
                ConfigurationSection c = items.getConfigurationSection(id);
                if (c == null) continue;

                EquipmentSlot slot = parseSlot(c.getString("slot", "CHEST"), id);
                Totem totem = new Totem(
                        id.toUpperCase(Locale.ROOT),
                        slot,
                        c.getLong("cooldown-seconds", 120) * 1000L,
                        c.getDouble("health", 1.0),
                        parseEffects(c.getStringList("effects"), id),
                        c.getString("message", "&5Bạn được cứu khỏi cái chết! &7Hồi chiêu {cooldown}."),
                        c.getString("ready-message", "&dĐã sẵn sàng!"),
                        c.getString("lore-marker", "Hồi chiêu"),
                        c.getString("lore-ready", "&8» &5Hồi chiêu: &f{cooldown}"),
                        c.getString("lore-cooling", "&8» &5Hồi chiêu: &c{time} &7(đang hồi)"));
                totems.put(totem.id(), totem);
                usedSlots.add(slot);
            }
        }

        long period = Math.max(1L, getConfig().getLong("lore-update-seconds", 1)) * 20L;
        if (task != null) task.cancel();
        task = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, period);

        getLogger().info("Đã tải " + totems.size() + " item totem.");
    }

    private EquipmentSlot parseSlot(String raw, String id) {
        switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD":
                return EquipmentSlot.HEAD;
            case "CHEST":
                return EquipmentSlot.CHEST;
            case "LEGS":
                return EquipmentSlot.LEGS;
            case "FEET":
                return EquipmentSlot.FEET;
            case "HAND":
                return EquipmentSlot.HAND;
            case "OFF_HAND":
                return EquipmentSlot.OFF_HAND;
            default:
                getLogger().warning("[" + id + "] slot '" + raw + "' không hợp lệ, dùng CHEST. "
                        + "Hợp lệ: HEAD, CHEST, LEGS, FEET, HAND, OFF_HAND.");
                return EquipmentSlot.CHEST;
        }
    }

    /** Mỗi dòng dạng "tên_hiệu_ứng:giây:cấp", ví dụ "regeneration:45:2". */
    private List<PotionEffect> parseEffects(List<String> raw, String id) {
        List<PotionEffect> list = new ArrayList<>();
        for (String line : raw) {
            String[] parts = line.split(":");
            PotionEffectType type = PotionEffectType.getByKey(
                    NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
            if (type == null) {
                getLogger().warning("[" + id + "] hiệu ứng không tồn tại: " + line);
                continue;
            }
            try {
                int seconds = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 10;
                int level = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
                list.add(new PotionEffect(type, seconds * 20, Math.max(0, level - 1)));
            } catch (NumberFormatException ex) {
                getLogger().warning("[" + id + "] sai định dạng hiệu ứng: " + line);
            }
        }
        return list;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // Chỉ xử lý khi đòn này làm người chơi chết
        if (p.getHealth() - e.getFinalDamage() > 0) return;

        long now = System.currentTimeMillis();
        PlayerInventory inv = p.getInventory();
        for (EquipmentSlot slot : usedSlots) {
            Totem t = totemIn(inv.getItem(slot), slot);
            if (t == null) continue;

            Map<String, Long> cds = readyAt.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
            Long ready = cds.get(t.id());
            if (ready != null && now < ready) continue; // item này đang hồi, thử item khác

            cds.put(t.id(), now + t.cooldownMs());
            e.setCancelled(true);
            revive(p, t);
            return;
        }
    }

    private void revive(Player p, Totem t) {
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        try {
            p.setHealth(t.health());
        } catch (IllegalArgumentException ex) {
            p.setHealth(1.0);
        }
        for (PotionEffect effect : t.effects()) {
            p.addPotionEffect(effect);
        }

        EntityEffect totem = totemEffect();
        if (totem != null) p.playEffect(totem);
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);

        p.sendMessage(color(t.message().replace("{cooldown}", formatTime(t.cooldownMs()))));
    }

    /** Chạy định kỳ: cập nhật dòng hồi chiêu trong lore của các item totem đang mặc/cầm. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : getServer().getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            Map<String, Long> cds = readyAt.get(p.getUniqueId());

            for (EquipmentSlot slot : usedSlots) {
                ItemStack item = inv.getItem(slot);
                Totem t = totemIn(item, slot);
                if (t == null) continue;

                Long ready = cds == null ? null : cds.get(t.id());
                boolean cooling = ready != null && now < ready;

                // Vừa hồi xong
                if (ready != null && !cooling) {
                    cds.remove(t.id());
                    p.sendMessage(color(t.readyMessage()));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                }

                String line = cooling
                        ? t.loreCooling().replace("{time}", formatTime(ready - now))
                        : t.loreReady().replace("{cooldown}", formatTime(t.cooldownMs()));
                if (updateLore(item, t.loreMarker(), line)) {
                    inv.setItem(slot, item);
                }
            }
        }
    }

    /** Thay dòng lore có chứa marker. Trả về true nếu có thay đổi. */
    private boolean updateLore(ItemStack item, String marker, String newLine) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        List<String> lore = meta.getLore();
        if (lore == null) return false;

        String colored = color(newLine);
        for (int i = 0; i < lore.size(); i++) {
            String plain = ChatColor.stripColor(lore.get(i));
            if (plain == null || !plain.contains(marker)) continue;

            // Chữ không đổi thì khỏi ghi lại item
            if (plain.equals(ChatColor.stripColor(colored))) return false;

            lore.set(i, colored);
            meta.setLore(lore);
            item.setItemMeta(meta);
            return true;
        }
        return false;
    }

    /** Trả về cấu hình totem nếu item này là item totem và đang nằm đúng ô quy định. */
    private Totem totemIn(ItemStack item, EquipmentSlot slot) {
        if (item == null || item.getType().isAir()) return null;
        String id = NBTItem.get(item).getString("MMOITEMS_ITEM_ID");
        if (id == null || id.isEmpty()) return null;
        Totem t = totems.get(id.toUpperCase(Locale.ROOT));
        return (t != null && t.slot() == slot) ? t : null;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 105000 ms -> "1:45" */
    private static String formatTime(long ms) {
        long s = (ms + 999) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static EntityEffect totemEffect() {
        for (String name : new String[]{"PROTECTED_FROM_DEATH", "TOTEM_RESURRECT"}) {
            try {
                return EntityEffect.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}
