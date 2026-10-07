package vn.darkwings;

import io.lumine.mythic.lib.api.item.NBTItem;
import org.bukkit.ChatColor;
import org.bukkit.EntityEffect;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cánh Hắc Ám: khi mặc ở ô giáp thân, cứu người chơi khỏi đòn chí mạng
 * giống Totem of Undying nhưng không mất item, chỉ cần chờ hồi chiêu.
 */
public class DarkWingsTotem extends JavaPlugin implements Listener {

    private final Map<UUID, Long> readyAt = new HashMap<>();
    private String itemId;
    private long cooldownMs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemId = getConfig().getString("item-id", "CANH_HAC_AM");
        cooldownMs = getConfig().getLong("cooldown-seconds", 120) * 1000L;
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // Chỉ xử lý khi đòn này làm người chơi chết
        if (p.getHealth() - e.getFinalDamage() > 0) return;

        // Phải đang mặc Cánh Hắc Ám ở ô giáp thân
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType().isAir()) return;
        if (!itemId.equals(NBTItem.get(chest).getString("MMOITEMS_ITEM_ID"))) return;

        // Đang hồi chiêu thì chết bình thường
        long now = System.currentTimeMillis();
        Long ready = readyAt.get(p.getUniqueId());
        if (ready != null && now < ready) return;
        readyAt.put(p.getUniqueId(), now + cooldownMs);

        // Hủy đòn chí mạng, hiệu ứng y như Totem vanilla
        e.setCancelled(true);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.setHealth(1.0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));

        // Hiệu ứng totem (tên enum khác nhau giữa các phiên bản)
        EntityEffect totem = totemEffect();
        if (totem != null) p.playEffect(totem);
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);

        p.sendMessage(ChatColor.DARK_PURPLE + "Cánh Hắc Ám đã cứu bạn khỏi cái chết! "
                + ChatColor.GRAY + "Hồi chiêu " + (cooldownMs / 1000) + " giây.");
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
