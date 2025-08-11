package com.krisped;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ItemResolver
{
    private static final int MAX_ITEM_ID = 50000;

    // CRITICAL: Include both noted and regular coins
    private static final Set<Integer> FORCED_STACKABLES;

    static {
        Set<Integer> stackables = new HashSet<>();
        stackables.add(995);   // Coins (regular)
        stackables.add(617);   // Coins (noted) - CRITICAL FIX!
        stackables.add(6529);  // Tokkul
        stackables.add(8778);  // Barbarian herblore
        stackables.add(554);   // Fire rune
        stackables.add(555);   // Water rune
        stackables.add(556);   // Air rune
        stackables.add(557);   // Earth rune
        stackables.add(558);   // Mind rune
        FORCED_STACKABLES = Collections.unmodifiableSet(stackables);
        log.info("Forced stackables initialized: {}", stackables);
    }

    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final Map<String, Integer> nameToId = new ConcurrentHashMap<>();
    private volatile boolean indexBuilt = false;

    // Cache reflection methods for performance
    private static final Map<Class<?>, Method> GET_NAME_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_ID_METHODS = new ConcurrentHashMap<>();

    public ItemResolver(ItemManager itemManager, ClientThread clientThread)
    {
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        log.info("ItemResolver initialized");
    }

    public Integer resolve(String name)
    {
        if (name == null) return null;
        String qLower = name.trim().toLowerCase(Locale.ROOT);
        if (qLower.isEmpty()) return null;

        // SPECIAL CASE: Force "coins" to resolve to regular coins (995), not noted (617)
        if (qLower.equals("coins") || qLower.equals("coin")) {
            log.info("Special case: forcing '{}' to resolve to regular coins (995)", name);
            return 995;
        }

        // 1) Search: exact match (case-insensitive)
        try
        {
            for (Object itObj : itemManager.search(name))
            {
                String itName = getNameViaReflection(itObj);
                Integer itId = getIdViaReflection(itObj);
                if (itName == null || itId == null) continue;
                if (itName.equalsIgnoreCase(name))
                {
                    int canonId = itemManager.canonicalize(itId);
                    log.debug("Resolved '{}' to item {} (canon: {})", name, itId, canonId);
                    return canonId;
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Search failed for: {}", name, e);
        }

        // 2) Index (exact match)
        buildIndexIfNeeded();
        Integer id = nameToId.get(qLower);
        if (id != null) {
            log.debug("Resolved '{}' from index to item {}", name, id);
            return id;
        }

        // 3) Parentheses prefix
        if (looksLikeParenPrefix(qLower))
        {
            Integer byParen = pickVariantByParenPrefix(qLower);
            if (byParen != null) {
                log.debug("Resolved '{}' via parentheses to item {}", name, byParen);
                return byParen;
            }
        }

        log.debug("Could not resolve item name: '{}'", name);
        return null;
    }

    public String getNameOnClientThread(int itemId)
    {
        try {
            String name = itemManager.getItemComposition(itemManager.canonicalize(itemId)).getName();
            log.trace("Got name for item {}: '{}'", itemId, name);
            return name;
        }
        catch (Exception e) {
            log.debug("Failed to get name for item {}", itemId, e);
            return null;
        }
    }

    public boolean isStackableOnClientThread(int itemId)
    {
        try
        {
            int canon = itemManager.canonicalize(itemId);

            // Check forced stackables FIRST and LOG it
            if (FORCED_STACKABLES.contains(canon)) {
                log.info("Item {} (canon: {}) is FORCED stackable", itemId, canon);
                return true;
            }

            boolean stackable = itemManager.getItemComposition(canon).isStackable();
            log.debug("Item {} (canon: {}) stackable check: {}", itemId, canon, stackable);
            return stackable;
        }
        catch (Exception e) {
            log.warn("Failed to check stackable for item {} - defaulting to false", itemId, e);
            return false;
        }
    }

    private void buildIndexIfNeeded()
    {
        if (indexBuilt) return;

        clientThread.invoke(() ->
        {
            if (indexBuilt) return;

            log.info("Building item index with {} items...", MAX_ITEM_ID);
            int indexed = 0;

            for (int id = 0; id <= MAX_ITEM_ID; id++)
            {
                try
                {
                    String n = itemManager.getItemComposition(id).getName();
                    if (n == null || "null".equalsIgnoreCase(n)) continue;
                    int canon = itemManager.canonicalize(id);

                    // SPECIAL: Don't index noted coins as "Coins", force regular coins
                    if ("Coins".equalsIgnoreCase(n) && canon == 617) {
                        log.debug("Skipping noted coins (617) in index, regular coins (995) will be preferred");
                        continue;
                    }

                    nameToId.putIfAbsent(n.toLowerCase(Locale.ROOT), canon);
                    indexed++;
                }
                catch (Exception ignored) {}
            }

            indexBuilt = true;
            log.info("Item index built with {} items", indexed);
        });
    }

    private boolean looksLikeParenPrefix(String qLower)
    {
        return qLower.endsWith("(") || (qLower.contains("(") && !qLower.contains(")"));
    }

    private Integer pickVariantByParenPrefix(String qLower)
    {
        int bestDose = -1;
        int bestLenDiff = Integer.MAX_VALUE;
        Integer bestId = null;

        for (Map.Entry<String, Integer> e : nameToId.entrySet())
        {
            String nm = e.getKey();
            if (!nm.startsWith(qLower)) continue;

            int open = nm.indexOf('(');
            int close = nm.indexOf(')', open + 1);
            if (open < 0 || close < 0) continue;

            String inside = nm.substring(open + 1, close).trim();
            int dose;
            try {
                dose = Integer.parseInt(inside);
            } catch (Exception ex) {
                dose = -1;
            }

            int lenDiff = Math.abs(nm.length() - qLower.length());

            if (dose > bestDose || (dose == bestDose && lenDiff < bestLenDiff))
            {
                bestDose = dose;
                bestLenDiff = lenDiff;
                bestId = e.getValue();
            }
        }
        return bestId;
    }

    private String getNameViaReflection(Object itObj)
    {
        try
        {
            Class<?> clazz = itObj.getClass();
            Method method = GET_NAME_METHODS.computeIfAbsent(clazz, c -> {
                try {
                    return c.getMethod("getName");
                } catch (Exception e) {
                    return null;
                }
            });

            if (method == null) return null;
            Object result = method.invoke(itObj);
            return (result instanceof String) ? (String) result : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private Integer getIdViaReflection(Object itObj)
    {
        try
        {
            Class<?> clazz = itObj.getClass();
            Method method = GET_ID_METHODS.computeIfAbsent(clazz, c -> {
                try {
                    return c.getMethod("getId");
                } catch (Exception e) {
                    return null;
                }
            });

            if (method == null) return null;
            Object result = method.invoke(itObj);
            if (result instanceof Integer) return (Integer) result;
            if (result instanceof Number) return ((Number) result).intValue();
            return null;
        }
        catch (Exception e) {
            return null;
        }
    }
}