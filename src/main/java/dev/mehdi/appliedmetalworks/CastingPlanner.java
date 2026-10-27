package dev.mehdi.appliedmetalworks;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import cy.jdkdigital.productivemetalworks.recipe.ItemCastingRecipe;
import cy.jdkdigital.productivemetalworks.registry.MetalworksRegistrator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.*;

/** Plans against live PMW recipes before ownership of any AE2 inputs changes. */
final class CastingPlanner {
    static final int MAX_CYCLES = 4096;
    static final long MAX_FLUID = 16_000_000;

    static CastingJob plan(Level level, ItemStack[] stationCasts, IPatternDetails pattern, KeyCounter[] holders, int temperature, KeyCounter controllerMaterials) {
        return plan(level,stationCasts,pattern,holders,temperature,controllerMaterials,false);
    }
    static CastingJob plan(Level level, ItemStack[] stationCasts, IPatternDetails pattern, KeyCounter[] holders, int temperature, KeyCounter controllerMaterials, boolean core) {
        // Processing patterns only. Crafting recipes need a molecular assembler.
        if (!pattern.supportsPushInputsToExternalInventory() || pattern.getOutputs().size() != 1) return null;
        var expected = pattern.getPrimaryOutput();
        if (expected.what() instanceof AEFluidKey fluid) return planAlloy(level, pattern, holders, temperature, controllerMaterials, fluid,core);
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() <= 0) return null;
        var supplied = new KeyCounter();
        for (var holder : holders) supplied.addAll(holder);
        var recipes = CastingRecipes.candidates(level, stationCasts, supplied, controllerMaterials,expectedKey,temperature,core);
        List<net.neoforged.neoforge.fluids.FluidStack> reachable=null;

        for (var candidate : recipes) {
            var holder = candidate.holder();
            var recipe = holder.value();
            int station = candidate.station();
            List<net.neoforged.neoforge.fluids.FluidStack> probes;
            if(candidate.probe()!=null)probes=List.of(candidate.probe());
            else if(recipe.getClass()==cy.jdkdigital.productivemetalworks.recipe.ItemCastingRecipe.class)probes=CastingRecipes.fluids(level,recipe);
            else {
                if(reachable==null)reachable=CastingRecipes.reachableFluids(level,supplied,controllerMaterials,temperature,core);
                probes=reachable;
            }
            for (var fluidStack : probes) {
                if (!CastingRecipes.matchesAnyCast(level, recipe, fluidStack, stationCasts, supplied)) continue;
                var result = recipe.getResultItem(level, fluidStack);
                int amount = recipe.getFluidAmount(level, fluidStack);
                if (result.isEmpty() || !expectedKey.equals(AEItemKey.of(result)) || amount <= 0
                        || expected.amount() % result.getCount() != 0) continue;
                long cyclesLong = expected.amount() / result.getCount();
                if (cyclesLong < 1 || cyclesLong > MAX_CYCLES || cyclesLong * amount > MAX_FLUID) continue;
                int cycles = (int) cyclesLong;
                var inputs = new KeyCounter(); inputs.addAll(supplied);
                AEItemKey castKey = null;
                boolean castFromPattern = false;
                boolean castFromBank = false;
                int reservedCastCount = recipe.consumeCast ? cycles : 1;
                if (!recipe.matches(ItemStack.EMPTY, fluidStack, level)) {
                    // Separate the casting substrate/catalyst before considering any input for melting.
                    for (var entry : inputs) {
                        if (entry.getKey() instanceof AEItemKey item && entry.getLongValue() >= reservedCastCount
                                && recipe.matches(item.toStack(), fluidStack, level)) {
                            castKey = item; castFromPattern = true; break;
                        }
                    }
                    if (castFromPattern) inputs.remove(castKey, reservedCastCount);
                    else {
                        for (var stored : stationCasts) if (!stored.isEmpty() && recipe.matches(stored, fluidStack, level)) {
                            var key=AEItemKey.of(stored);long available=0;
                            for(var slot:stationCasts)if(key.matches(slot))available+=slot.getCount();
                            if(recipe.consumeCast && available<reservedCastCount)continue;
                            castKey = key; castFromBank = recipe.consumeCast; break;
                        }
                        if (castKey == null) continue;
                    }
                } else if (recipe.consumeCast) continue;
                var targetFluid = AEFluidKey.of(fluidStack);
                var budget = new KeyCounter(); budget.addAll(controllerMaterials);
                boolean valid = true;
                long totalItems = 0;
                var job = new CastingJob();
                for (var entry : inputs) {
                    long count = entry.getLongValue();
                    if (count <= 0) continue;
                    if (entry.getKey() instanceof AEFluidKey fluid) {
                        if (count > MAX_FLUID) { valid = false; break; }
                        budget.add(fluid, count);
                        // Even supplied molten fluid enters the controller before the casting station draws it.
                        job.pending.add(new GenericStack(fluid, count));
                    } else if (entry.getKey() instanceof AEItemKey item) {
                        totalItems += count;
                        if (totalItems > MAX_CYCLES) { valid = false; break; }
                        var melt = core?CastingRecipes.meltingForCore(level,item.toStack(),temperature):CastingRecipes.melting(level, item.toStack(), temperature);
                        if (melt == null || melt.value().result.isEmpty()) { valid = false; break; }
                        for (var produced : melt.value().result) {
                            budget.add(AEFluidKey.of(produced), (long) produced.getAmount() * count);
                        }
                        job.pending.add(new GenericStack(item, count));
                    } else { valid = false; break; }
                }
                if (job.pending.size() > 9) valid = false;
                job.requiresAlloy = budget.get(targetFluid) < cyclesLong * amount;
                if (!valid || !canProduce(level, budget, targetFluid, cyclesLong * amount)) continue;
                job.recipeId = holder.id(); job.station = station; job.fluid = targetFluid;
                job.compatibilityRecipe = candidate.compatibility();
                job.output = expectedKey; job.outputPerCast = result.getCount();
                job.amountPerCast = amount; job.remaining = cycles; job.total = cycles;
                job.cast = castKey; job.consumeCast = recipe.consumeCast;
                job.castFromBank = castFromBank; job.castFromPattern = castFromPattern;
                for (int slot = 0; slot < stationCasts.length; slot++) if (!castFromPattern && castKey != null && castKey.matches(stationCasts[slot])) {
                    job.castSlot = slot; break;
                }
                job.castCount = castFromPattern || castFromBank ? reservedCastCount : 0;
                reserve(level, job, budget, controllerMaterials, targetFluid, cyclesLong * amount);
                return job;
            }
        }
        return null;
    }

    private static CastingJob planAlloy(Level level, IPatternDetails pattern, KeyCounter[] holders, int temperature,
            KeyCounter controllerMaterials, AEFluidKey target,boolean core) {
        long amount = pattern.getPrimaryOutput().amount();
        if (amount <= 0 || amount > MAX_FLUID) return null;
        var recipe = CastingRecipes.alloys(level,target).stream()
                .filter(r -> !r.value().result.isEmpty() && target.equals(AEFluidKey.of(r.value().result))
                        && amount % r.value().result.getAmount() == 0)
                .sorted(Comparator.comparing(r -> r.id().toString())).findFirst().orElse(null);
        if (recipe == null && !core) return null;
        var supplied = new KeyCounter(); for (var holder : holders) supplied.addAll(holder);
        var budget = new KeyCounter(); budget.addAll(controllerMaterials);
        var job = new CastingJob(); long items = 0;
        for (var entry : supplied) {
            long count = entry.getLongValue(); if (count <= 0) continue;
            if (entry.getKey() instanceof AEFluidKey fluid) {
                if (count > MAX_FLUID) return null;
                budget.add(fluid, count);
            } else if (entry.getKey() instanceof AEItemKey item) {
                items += count; if (items > MAX_CYCLES) return null;
                var melt = core?CastingRecipes.meltingForCore(level,item.toStack(),temperature):CastingRecipes.melting(level, item.toStack(), temperature);
                if (melt == null || melt.value().result.isEmpty()) return null;
                for (var fluid : melt.value().result) budget.add(AEFluidKey.of(fluid), (long)fluid.getAmount() * count);
            } else return null;
            job.pending.add(new GenericStack(entry.getKey(), count));
        }
        if (job.pending.size() > 9 || !canProduce(level, budget, target, amount)) return null;
        job.recipeId = recipe==null?AppliedMetalworks.id("internal_melting"):recipe.id(); job.station = 2; job.requiresAlloy = budget.get(target)<amount;
        job.fluid = target; job.output = target; job.outputPerCast = (int)amount; job.amountPerCast = (int)amount;
        job.remaining = 1; job.total = 1; job.alloyBatchAmount = recipe==null?0:recipe.value().result.getAmount();
        reserve(level, job, budget, controllerMaterials, target, amount);
        return job;
    }

    private static void reserve(Level level, CastingJob job, KeyCounter budget, KeyCounter controller, AEFluidKey target, long required) {
        var remaining = new KeyCounter(); remaining.addAll(budget);
        if (!produce(level, remaining, target, required, new HashSet<>(), new int[]{4096})) return;
        remaining.remove(target, required);
        for (var entry : controller) {
            long consumed = Math.min(entry.getLongValue(), Math.max(0, budget.get(entry.getKey()) - remaining.get(entry.getKey())));
            if (consumed > 0) job.reservations.add(new GenericStack(entry.getKey(), consumed));
        }
        // Protect the expected melt yields of our own inputs before they arrive in the shared pool.
        // Otherwise another station could take a precursor while this request waits for other metals.
        for(var entry:budget) {
            long introduced=Math.max(0,entry.getLongValue()-controller.get(entry.getKey()));
            if(introduced>0)job.inputReservations.add(new GenericStack(entry.getKey(),introduced));
        }
    }

    static boolean canProduce(Level level, KeyCounter available, AEFluidKey target, long required) {
        var budget = new KeyCounter(); budget.addAll(available);
        return produce(level, budget, target, required, new HashSet<>(), new int[]{4096});
    }
    static boolean produceActual(Level level,KeyCounter available,AEFluidKey target,long required) {
        return produce(level,available,target,required,new HashSet<>(),new int[]{4096});
    }

    private static boolean produce(Level level, KeyCounter available, AEFluidKey target, long required,
            Set<AEFluidKey> path, int[] steps) {
        if (available.get(target) >= required) return true;
        if (required > MAX_FLUID || path.size() >= 32 || --steps[0] < 0 || !path.add(target)) return false;
        try {
            for (var holder : CastingRecipes.alloys(level,target)) {
                var recipe = holder.value();
                if (recipe.result.isEmpty() || recipe.fluids.isEmpty() || !target.equals(AEFluidKey.of(recipe.result))) continue;
                long missing = required - available.get(target);
                long batches = (missing + recipe.result.getAmount() - 1) / recipe.result.getAmount();
                var trial = new KeyCounter(); trial.addAll(available);
                if (!consumeIngredients(level, recipe.fluids, 0, batches, trial, path, steps)) continue;
                trial.add(target, batches * recipe.result.getAmount());
                available.clear(); available.addAll(trial);
                return true;
            }
            return false;
        } finally { path.remove(target); }
    }

    private static boolean consumeIngredients(Level level,
            List<net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient> ingredients, int index,
            long batches, KeyCounter available, Set<AEFluidKey> path, int[] steps) {
        if (index == ingredients.size()) return true;
        if (--steps[0] < 0) return false;
        var ingredient = ingredients.get(index);
        long needed = batches * ingredient.amount();
        if (needed <= 0 || needed > MAX_FLUID) return false;
        var candidates = new LinkedHashSet<AEFluidKey>();
        for (var entry : available) if (entry.getKey() instanceof AEFluidKey fluid
                && ingredient.ingredient().test(fluid.toStack(1))) candidates.add(fluid);
        for (var stack : ingredient.getFluids()) if (!stack.isEmpty()) candidates.add(AEFluidKey.of(stack));
        for (var fluid : candidates) {
            var trial = new KeyCounter(); trial.addAll(available);
            if (!produce(level, trial, fluid, needed, path, steps)) continue;
            trial.remove(fluid, needed);
            if (!consumeIngredients(level, ingredients, index + 1, batches, trial, path, steps)) continue;
            available.clear(); available.addAll(trial); return true;
        }
        return false;
    }
}
