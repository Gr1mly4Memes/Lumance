/*
 * Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package gr1mly4memes.lumance.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Fluid;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockType;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/**
 * Discovers content that only exists because a mod added it, so plugins can reason about a modded
 * world without hard-coding a mod list.
 *
 * <h2>Why this exists</h2>
 * Mohist and Youer solve this by injecting new constants into Bukkit's Java <em>enums</em> at runtime
 * (their {@code NeoForgeInjectBukkit}). Lumance deliberately does not copy that, and mostly does not
 * need to: this Bukkit already moved most content off enums onto interfaces backed by the live NMS
 * registries. {@link BlockType}, {@link ItemType}, {@link Biome}, {@link Sound},
 * {@link PotionEffectType}, {@link Enchantment}, {@link Fluid} and {@link DamageType} are all
 * registry-backed, and {@code CraftRegistry} builds them from
 * {@code registryHolder.lookupOrThrow(Registries.X)} - which contains every modded entry. Modded
 * blocks and items are therefore already reachable today, with no enum magic:
 *
 * <pre>{@code
 * BlockType block = Bukkit.getRegistry(BlockType.class)
 *         .get(NamespacedKey.of("terralith", "azure_ore"));
 * }</pre>
 *
 * <p>What is <em>not</em> covered is the handful of registries still backed by real Java enums. Those
 * genuinely cannot list modded content, and rather than pretend otherwise they are named by
 * {@link #enumBackedRegistries()} so a plugin can state its limitation instead of silently dropping
 * modded content on the floor.
 *
 * <h2>Portability</h2>
 * Nothing outside the stable {@code org.bukkit} API is touched - no NMS types, no Bukkit or Spigot
 * internals - so this survives Minecraft version bumps. Results are cached and invalidated through
 * {@link #refresh()}, which matters because registries fill in as mods finish loading.
 *
 * <p>All methods are safe to call from any thread, but the underlying Bukkit registries are most
 * reliable once mod loading has settled; see {@link #refresh()}.
 */
public final class ModdedContent {

    private ModdedContent() {
    }

    /**
     * Cached scans, keyed on the registry's <em>element type</em> (BlockType, ItemType, ...) so each
     * category gets its own entry. See {@link #scan} for why the registry's own class cannot be
     * used as the key.
     */
    private static final Map<Class<?>, Set<NamespacedKey>> CACHE = new ConcurrentHashMap<>();

    /**
     * The registries that are still backed by Java enums, and therefore can never expose modded
     * content.
     *
     * <p>These are exactly the ones Mohist has to synthesise constants for. A plugin that needs to
     * name a modded mob, particle, potion, statistic or material must fall back to comparing
     * {@link NamespacedKey}s - and for {@link Material} and {@link EntityType} it should prefer
     * {@link #block(NamespacedKey)} and {@link #item(NamespacedKey)} anyway.
     *
     * @return an unmodifiable set of the affected content holder classes
     */
    public static Set<Class<?>> enumBackedRegistries() {
        return Set.of(Material.class, EntityType.class, Particle.class, PotionType.class, Statistic.class);
    }

    /**
     * Whether the given content class is one of the enum-backed registries from
     * {@link #enumBackedRegistries()}.
     *
     * @param contentClass a Bukkit content interface or enum, e.g. {@code Material.class}
     * @return true when modded entries cannot be represented for this class
     */
    public static boolean isEnumBacked(Class<?> contentClass) {
        return contentClass != null && enumBackedRegistries().contains(contentClass);
    }

    /**
     * Discards every cached scan.
     *
     * <p>Registries populate as mods load, so a scan taken during plugin enable can legitimately come
     * back empty. Plugins that need a guaranteed-fresh view - typically on
     * {@code ServerLoadEvent} - should call this once first.
     */
    public static void refresh() {
        CACHE.clear();
    }

    /**
     * @param key a namespaced key
     * @return true when the key belongs to a mod rather than to vanilla
     */
    public static boolean isModded(NamespacedKey key) {
        return key != null && !NamespacedKey.MINECRAFT.equals(key.getNamespace());
    }

    /**
     * Generic scan: every modded key currently present in a registry.
     *
     * @param registry the registry to scan
     * @return an unmodifiable set of modded keys, empty when there are none
     */
    public static Set<NamespacedKey> of(Registry<? extends Keyed> registry) {
        return scan(registry);
    }

    /** @return modded block keys - that is, blocks with no {@link Material} constant to name them. */
    public static Set<NamespacedKey> blocks() {
        return scan(registry(BlockType.class));
    }

    /** @return modded item keys. */
    public static Set<NamespacedKey> items() {
        return scan(registry(ItemType.class));
    }

    /** @return modded biome keys. */
    public static Set<NamespacedKey> biomes() {
        return scan(registry(Biome.class));
    }

    /** @return modded sound keys. */
    public static Set<NamespacedKey> sounds() {
        return scan(registry(Sound.class));
    }

    /** @return modded potion effect keys. */
    public static Set<NamespacedKey> potionEffects() {
        return scan(registry(PotionEffectType.class));
    }

    /** @return modded enchantment keys. */
    public static Set<NamespacedKey> enchantments() {
        return scan(registry(Enchantment.class));
    }

    /** @return modded damage type keys. */
    public static Set<NamespacedKey> damageTypes() {
        return scan(registry(DamageType.class));
    }

    /** @return modded fluid keys. */
    public static Set<NamespacedKey> fluids() {
        return scan(registry(Fluid.class));
    }

    /**
     * Every registry-backed modded key this server knows about, across all categories above.
     *
     * @return an unmodifiable union set
     */
    public static Set<NamespacedKey> all() {
        Set<NamespacedKey> union = new LinkedHashSet<>();
        union.addAll(blocks());
        union.addAll(items());
        union.addAll(biomes());
        union.addAll(sounds());
        union.addAll(potionEffects());
        union.addAll(enchantments());
        union.addAll(damageTypes());
        union.addAll(fluids());
        return Collections.unmodifiableSet(union);
    }

    /**
     * Mod ids that own at least one key in {@link #all()}, in encounter order.
     *
     * <p>Handy for diagnostics - "which installed mods actually added content to this world?" -
     * without needing Fabric's loader API at all.
     *
     * @return an unmodifiable list of mod ids
     */
    public static List<String> contributingMods() {
        Set<String> mods = new LinkedHashSet<>();
        for (NamespacedKey key : all()) {
            mods.add(key.getNamespace());
        }
        mods.remove(NamespacedKey.MINECRAFT);
        return Collections.unmodifiableList(new ArrayList<>(mods));
    }

    /**
     * Resolves a block without going through the {@link Material} enum, which cannot represent
     * modded blocks.
     *
     * @param key the block key
     * @return the block type, or empty when the key is not a registered block
     */
    public static Optional<BlockType> block(NamespacedKey key) {
        Registry<BlockType> blocks = key == null ? null : registry(BlockType.class);
        return blocks == null ? Optional.empty() : Optional.ofNullable(blocks.get(key));
    }

    /**
     * Resolves an item the same way as {@link #block(NamespacedKey)}.
     *
     * @param key the item key
     * @return the item type, or empty when the key is not a registered item
     */
    public static Optional<ItemType> item(NamespacedKey key) {
        Registry<ItemType> items = key == null ? null : registry(ItemType.class);
        return items == null ? Optional.empty() : Optional.ofNullable(items.get(key));
    }

    /**
     * Looks a registry up defensively.
     *
     * <p>{@code Bukkit.getRegistry} itself can fail - CraftBukkit builds these eagerly from the
     * live registry access, so a call made before the server has finished bootstrapping throws.
     * Callers treat null as "nothing to report", which is why this swallows rather than propagates.
     */
    private static <T extends Keyed> Registry<T> registry(Class<T> holder) {
        try {
            return Bukkit.getRegistry(holder);
        } catch (RuntimeException | LinkageError notReady) {
            return null;
        }
    }

    private static Set<NamespacedKey> scan(Registry<? extends Keyed> registry) {
        if (registry == null) {
            return Collections.emptySet();
        }
        // Keyed on the registry's element type, not on the registry's own class: CraftBukkit backs
        // every one of these with the same CraftRegistry implementation, so keying on getClass()
        // would hand blocks() the item scan and silently report the same set for every category.
        // Class<?> is used because the element type is not always statically nameable here (the
        // Registry interface is generic, but callers pass the holder class they asked for).
        Class<?> element = elementTypeOf(registry);
        if (element == null) {
            return collect(registry);
        }
        return CACHE.computeIfAbsent(element, ignored -> collect(registry));
    }

    /**
     * Best-effort element type of a Bukkit registry.
     *
     * <p>Walks the generic supertype chain looking for {@code Registry<T>}. CraftRegistry extends
     * {@code Registry<T>} directly, so this resolves in one hop; anything unrecognised returns null
     * and simply skips the cache rather than serving a wrong entry.
     */
    private static Class<?> elementTypeOf(Registry<?> registry) {
        for (Class<?> type = registry.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Type itf : type.getGenericInterfaces()) {
                if (itf instanceof java.lang.reflect.ParameterizedType parameterized
                        && parameterized.getRawType() == Registry.class
                        && parameterized.getActualTypeArguments().length == 1
                        && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                    return element;
                }
            }
            java.lang.reflect.Type parent = type.getGenericSuperclass();
            if (parent instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length == 1
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    private static Set<NamespacedKey> collect(Registry<? extends Keyed> registry) {
        Set<NamespacedKey> found = new LinkedHashSet<>();
        try {
            for (Keyed keyed : registry) {
                if (keyed != null && isModded(keyed.getKey())) {
                    found.add(keyed.getKey());
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // A registry that is not iterable yet yields nothing rather than failing the caller.
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(found);
    }
}