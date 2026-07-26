package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.util.context.ContextParameterMap;

/**
 * 基于客户端配方书(1.21.2+ 的 RecipeDisplay 体系)查找合成配方。
 * 注意:配方书只包含"已解锁"的配方 —— 玩家拿到过相关材料后 vanilla 会自动解锁。
 */
public final class RecipeIndex {

    private RecipeIndex() {}

    public record Found(RecipeDisplayEntry entry, boolean needsTable) {}

    /** 查找产出指定物品的合成配方(工作台/背包合成),找不到返回 null */
    public static Found findCrafting(MinecraftClient client, String itemName) {
        if (client.player == null || client.world == null) return null;
        String target = InventoryHelper.normalizeId(itemName);
        ContextParameterMap ctx = SlotDisplayContexts.createParameters(client.world);
        Found fallback = null;
        for (RecipeResultCollection collection : client.player.getRecipeBook().getOrderedResults()) {
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                RecipeDisplay display = entry.display();
                boolean shaped = display instanceof ShapedCraftingRecipeDisplay;
                boolean shapeless = display instanceof ShapelessCraftingRecipeDisplay;
                if (!shaped && !shapeless) continue;
                ItemStack out;
                try {
                    out = display.result().getFirst(ctx);
                } catch (Throwable t) {
                    continue;
                }
                if (out.isEmpty() || !InventoryHelper.idOf(out).equals(target)) continue;
                boolean table = needsTable(display);
                Found f = new Found(entry, table);
                if (!table) return f; // 优先 2x2 配方,不依赖工作台
                if (fallback == null) fallback = f;
            }
        }
        return fallback;
    }

    private static boolean needsTable(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() > 2 || shaped.height() > 2;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() > 4;
        }
        return true;
    }

    /** 配方单次产出数量(用于估算需要点击几次) */
    public static int resultCount(MinecraftClient client, RecipeDisplayEntry entry) {
        try {
            ContextParameterMap ctx = SlotDisplayContexts.createParameters(client.world);
            return Math.max(1, entry.display().result().getFirst(ctx).getCount());
        } catch (Throwable t) {
            return 1;
        }
    }

    public record Missing(String item, int count) {}

    /**
     * 计算单次合成该配方还缺哪些材料(基于配方书的展示格子,贪心匹配背包)。
     * 算不出来(非常规配方)返回空列表,走"点了没产出"的兜底失败路径。
     */
    public static java.util.List<Missing> computeMissing(MinecraftClient client, Found found) {
        try {
            var player = client.player;
            ContextParameterMap ctx = SlotDisplayContexts.createParameters(client.world);
            RecipeDisplay d = found.entry().display();
            java.util.List<net.minecraft.recipe.display.SlotDisplay> cells;
            if (d instanceof ShapedCraftingRecipeDisplay s) {
                cells = s.ingredients();
            } else if (d instanceof ShapelessCraftingRecipeDisplay s) {
                cells = s.ingredients();
            } else {
                return java.util.List.of();
            }
            // 背包计数(0..35)
            java.util.Map<String, Integer> inv = new java.util.HashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack st = player.getInventory().getStack(i);
                if (!st.isEmpty()) inv.merge(InventoryHelper.idOf(st), st.getCount(), Integer::sum);
            }
            java.util.Map<String, Integer> missing = new java.util.LinkedHashMap<>();
            for (var cell : cells) {
                java.util.List<ItemStack> options = cell.getStacks(ctx);
                if (options.isEmpty()) continue; // 空格子
                String chosen = null;
                for (ItemStack opt : options) {
                    String id = InventoryHelper.idOf(opt);
                    if (inv.getOrDefault(id, 0) > 0) {
                        chosen = id;
                        break;
                    }
                }
                if (chosen != null) {
                    inv.merge(chosen, -1, Integer::sum);
                } else {
                    missing.merge(InventoryHelper.idOf(options.get(0)), 1, Integer::sum);
                }
            }
            java.util.List<Missing> out = new java.util.ArrayList<>();
            missing.forEach((k, v) -> out.add(new Missing(k, v)));
            return out;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }
}
