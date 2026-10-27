package dev.mehdi.appliedmetalworks;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import cy.jdkdigital.productivemetalworks.recipe.ItemCastingRecipe;
import cy.jdkdigital.productivemetalworks.registry.MetalworksRegistrator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

/** Includes registered addon subclasses without linking their optional classes. */
final class CastingRecipes {
    private record MeltKey(AEItemKey item,int count,int limit) {}
    private static final Map<net.minecraft.world.item.crafting.RecipeManager,Index> INDEXES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Index {
        final Collection<RecipeHolder<?>> source;
        final List<RecipeHolder<ItemCastingRecipe>> casting=new ArrayList<>();
        final Map<AEItemKey,List<RecipeHolder<ItemCastingRecipe>>> castingByOutput=new HashMap<>();
        final List<RecipeHolder<ItemCastingRecipe>> dynamicCasting=new ArrayList<>();
        final Map<AEItemKey,List<Candidate>> candidatesByOutput=new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<AEItemKey,List<Candidate>> entry) { return size()>256; }
        };
        final Map<appeng.api.stacks.AEFluidKey,List<RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe>>> alloys=new HashMap<>();
        final Map<MeltKey,Optional<RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe>>> melting=new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<MeltKey,Optional<RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe>>> e) { return size()>1024; }
        };
        Index(Collection<RecipeHolder<?>> source) {
            this.source=source;
            for(var holder:source) {
                if(holder.value() instanceof ItemCastingRecipe recipe) {
                    var castHolder=new RecipeHolder<>(holder.id(),recipe);casting.add(castHolder);
                    if(recipe.getClass()==ItemCastingRecipe.class) {
                        if(!recipe.result.isEmpty())castingByOutput.computeIfAbsent(AEItemKey.of(recipe.result),k->new ArrayList<>()).add(castHolder);
                    } else dynamicCasting.add(castHolder);
                }
                if(holder.value() instanceof cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe recipe && !recipe.result.isEmpty())
                    alloys.computeIfAbsent(appeng.api.stacks.AEFluidKey.of(recipe.result),k->new ArrayList<>()).add(new RecipeHolder<>(holder.id(),recipe));
            }
            casting.sort(Comparator.comparing(holder->holder.id().toString()));
        }
    }
    private static Index index(Level level) {
        var manager=level.getRecipeManager();var source=manager.getRecipes();
        synchronized(INDEXES) {
            var index=INDEXES.get(manager);
            // RecipeManager replaces its immutable map on reload, including scripted recipes.
            if(index==null || index.source!=source) { index=new Index(source);INDEXES.put(manager,index); }
            return index;
        }
    }
    static List<RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe>> alloys(Level level,appeng.api.stacks.AEFluidKey output) {
        return index(level).alloys.getOrDefault(output,List.of());
    }
    static Object version(Level level) { return index(level); }
    static List<FluidStack> reachableFluids(Level level,KeyCounter supplied,KeyCounter controller,int temperature,boolean core) {
        var fluids=new LinkedHashMap<appeng.api.stacks.AEFluidKey,FluidStack>();
        for(var counter:List.of(supplied,controller))for(var entry:counter) {
            if(entry.getKey() instanceof appeng.api.stacks.AEFluidKey fluid && entry.getLongValue()>0)fluids.put(fluid,fluid.toStack(1));
            else if(counter==supplied && entry.getKey() instanceof AEItemKey item) {
                var recipe=core?meltingForCore(level,item.toStack(),temperature):melting(level,item.toStack(),temperature);
                if(recipe!=null)for(var fluid:recipe.value().result)if(!fluid.isEmpty())fluids.put(appeng.api.stacks.AEFluidKey.of(fluid),fluid.copyWithAmount(1));
            }
        }
        // Follow loaded alloy ingredient types, preserving fluid components. Amounts are checked by the planner.
        for(int depth=0;depth<32;depth++) {
            var added=new ArrayList<FluidStack>();
            for(var recipes:index(level).alloys.values())for(var holder:recipes) {
                var recipe=holder.value();var key=appeng.api.stacks.AEFluidKey.of(recipe.result);
                if(fluids.containsKey(key) || recipe.fluids.isEmpty())continue;
                if(recipe.fluids.stream().allMatch(i->fluids.values().stream().anyMatch(f->i.ingredient().test(f))))added.add(recipe.result.copyWithAmount(1));
            }
            if(added.isEmpty())break;
            for(var fluid:added)fluids.put(appeng.api.stacks.AEFluidKey.of(fluid),fluid);
        }
        return List.copyOf(fluids.values());
    }
    static RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe> meltingForCore(Level level, ItemStack stack, int limit) {
        var cache=index(level).melting;var key=new MeltKey(AEItemKey.of(stack),stack.getCount(),limit);
        var known=cache.get(key);if(known!=null)return known.orElse(null);
        RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe> selected=null;
        for(var holder:level.getRecipeManager().getAllRecipesFor(MetalworksRegistrator.ITEM_MELTING_TYPE.get())) {
            var recipe=holder.value();
            if(recipe.minTemperature<=limit && recipe.matches(stack,Math.max(0,recipe.minTemperature)))selected=holder;
        }
        cache.put(key,Optional.ofNullable(selected));return selected;
    }
    static RecipeHolder<cy.jdkdigital.productivemetalworks.recipe.ItemMeltingRecipe> melting(Level level, ItemStack stack, int temperature) {
        if (temperature < 0) return null;
        // Use exactly the selection used by the controller, including overlapping addon/tag recipes.
        return cy.jdkdigital.productivemetalworks.util.RecipeHelper.getItemMeltingRecipe(level, stack,
                new cy.jdkdigital.productivemetalworks.common.datamap.FuelMap(temperature, 1f, 1f));
    }
    record Candidate(RecipeHolder<ItemCastingRecipe> holder, int station, boolean compatibility, FluidStack probe) {}

    static ItemCastingRecipe compatibility(Level level, ItemStack cast, FluidStack fluid, int station) {
        var event = new cy.jdkdigital.productivemetalworks.event.CastingRecipeEvent(level, cast.copy(), fluid.copy(), station == 0);
        net.neoforged.fml.ModLoader.postEvent(event);
        return event.getRecipe();
    }

    static Iterable<Candidate> candidates(Level level, ItemStack[] bank, KeyCounter supplied, KeyCounter controller,AEItemKey output,int temperature,boolean core) {
        var index=index(level);
        var result=index.candidatesByOutput.computeIfAbsent(output,key->{
            var holders=new ArrayList<>(index.castingByOutput.getOrDefault(key,List.of()));holders.addAll(index.dynamicCasting);
            holders.sort(Comparator.comparing(h->h.id().toString()));
            var ordered=new ArrayList<Candidate>();
            for(var holder:holders)ordered.add(new Candidate(holder,
                    holder.value().getType()==MetalworksRegistrator.BLOCK_CASTING_TYPE.get()?1:0,false,null));
            // Metadata only: dynamic results, bank selection, fluid reachability and events remain live.
            return List.copyOf(ordered);
        });
        // Most requests resolve registered recipes; dispatch addon events only if those fail.
        return () -> new Iterator<Candidate>() {
            private final Iterator<Candidate> registered = result.iterator();
            private Iterator<Candidate> compat;
            public boolean hasNext() {
                if (registered.hasNext()) return true;
                if (compat == null) compat = compatibilityCandidates(level, bank, supplied, controller,temperature,core).iterator();
                return compat.hasNext();
            }
            public Candidate next() {
                if (registered.hasNext()) return registered.next();
                if (!hasNext()) throw new NoSuchElementException();
                return compat.next();
            }
        };
    }

    private static List<Candidate> compatibilityCandidates(Level level, ItemStack[] bank, KeyCounter supplied, KeyCounter controller,int temperature,boolean core) {
        var result = new ArrayList<Candidate>();
        var casts = new LinkedHashMap<appeng.api.stacks.AEKey, ItemStack>();
        casts.put(null, ItemStack.EMPTY);
        for (var stack : bank) if (!stack.isEmpty()) casts.put(AEItemKey.of(stack), stack);
        for (var entry : supplied) if (entry.getKey() instanceof AEItemKey item) casts.put(item, item.toStack());
        var fluids=reachableFluids(level,supplied,controller,temperature,core);
        for (var cast : casts.values()) for (var fluid : fluids) for (int station = 0; station < 2; station++) {
            var recipe = compatibility(level, cast, fluid, station);
            if (recipe != null && recipe.matches(cast, fluid, level)) result.add(new Candidate(
                    new RecipeHolder<>(AppliedMetalworks.id(station == 0 ? "compat/table" : "compat/basin"), recipe), station, true, fluid));
        }
        return result;
    }
    static List<RecipeHolder<ItemCastingRecipe>> all(Level level) {
        return index(level).casting;
    }
    static List<FluidStack> fluids(Level level, ItemCastingRecipe recipe) {
        if (recipe.getType() == MetalworksRegistrator.ITEM_CASTING_TYPE.get()
                || recipe.getType() == MetalworksRegistrator.BLOCK_CASTING_TYPE.get())
            return Arrays.asList(recipe.fluid.getFluids());
        // Silent Gear has a placeholder fluid ingredient: its virtual methods resolve material-specific inputs/results.
        var fluids = new ArrayList<FluidStack>();
        for (var fluid : BuiltInRegistries.FLUID)
            if (fluid.defaultFluidState().isSource()) fluids.add(new FluidStack(fluid, 1));
        return fluids;
    }
    static boolean matchesAnyCast(Level level, ItemCastingRecipe recipe, FluidStack fluid, ItemStack[] bank, KeyCounter supplied) {
        if (recipe.matches(ItemStack.EMPTY, fluid, level)) return true;
        for (var cast : bank) if (!cast.isEmpty() && recipe.matches(cast, fluid, level)) return true;
        for (var entry : supplied)
            if (entry.getKey() instanceof AEItemKey item && recipe.matches(item.toStack(), fluid, level)) return true;
        return false;
    }
}
