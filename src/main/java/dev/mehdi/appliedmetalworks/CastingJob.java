package dev.mehdi.appliedmetalworks;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

/** All material still owned by the interface is persisted here, including partial pours. */
final class CastingJob {
    ResourceLocation recipeId;
    boolean compatibilityRecipe;
    int station;
    int castSlot = -1;
    boolean castFromBank;
    boolean castFromPattern;
    boolean requiresAlloy;
    AEFluidKey fluid;
    appeng.api.stacks.AEKey output;
    boolean returnFluidToME = true;
    int alloyBatchAmount;
    AEItemKey cast;
    boolean consumeCast;
    int castCount;
    int outputPerCast;
    int outputDelivered;
    int amountPerCast;
    int remaining;
    int total;
    int poured;
    int cooling = -1;
    boolean recovering;
    long fluidBuffer;
    final List<GenericStack> pending = new ArrayList<>();
    // Ownership metadata for unmelted items forwarded to the real controller; never copied as held material.
    final appeng.api.stacks.KeyCounter forwardedItems=new appeng.api.stacks.KeyCounter();
    final appeng.api.stacks.KeyCounter coreFluids=new appeng.api.stacks.KeyCounter();
    int meltWork;
    int coreTemperature;
    // Planning is metadata only; successful plans may wait for fuel or shared tank space.
    record AlloyPlan(Object recipes,AEFluidKey target,long needed,appeng.api.stacks.KeyCounter inputs,appeng.api.stacks.KeyCounter result) {}
    transient AlloyPlan alloyPlan;
    transient Object validatedRecipes;
    transient AEItemKey validatedCast;
    transient int validatedCastCount;
    transient long validateAfter;
    transient boolean recipeValid;
    transient long nextOutputAttempt;
    final List<GenericStack> reservations = new ArrayList<>();
    final List<GenericStack> inputReservations = new ArrayList<>();
    /** Metadata only: callers must transfer actual input/fluid/substrate ownership separately. */
    CastingJob stationCopy(int cycles,int slot) {
        var copy=new CastingJob();
        copy.recipeId=recipeId;copy.compatibilityRecipe=compatibilityRecipe;copy.station=station;
        copy.castSlot=slot;copy.cast=cast;copy.consumeCast=consumeCast;
        copy.castFromBank=castFromBank;copy.castFromPattern=consumeCast && castFromPattern;
        copy.castCount=consumeCast?cycles:0;copy.requiresAlloy=requiresAlloy;
        copy.fluid=fluid;copy.output=output;copy.outputPerCast=outputPerCast;copy.amountPerCast=amountPerCast;
        copy.remaining=cycles;copy.total=cycles;copy.returnFluidToME=returnFluidToME;
        copy.alloyBatchAmount=alloyBatchAmount;copy.coreTemperature=coreTemperature;
        return copy;
    }
    long physicalClaim(appeng.api.stacks.AEFluidKey key) {
        if(recovering || remaining==0)return 0;
        return reservations.stream().filter(s->s.what().equals(key)).mapToLong(GenericStack::amount).sum();
    }
    long claim(appeng.api.stacks.AEFluidKey key) {
        if (recovering || remaining == 0) return 0;
        long claim = physicalClaim(key)+inputReservations.stream().filter(s->s.what().equals(key)).mapToLong(GenericStack::amount).sum();
        if (fluid.equals(key)) claim = Math.max(claim, station == 2 ? amountPerCast - outputDelivered - poured
                : (long)amountPerCast * (remaining - (outputDelivered > 0 ? 1 : 0)) - poured);
        return Math.max(0, claim);
    }
    void release(appeng.api.stacks.AEFluidKey key, long amount) {
        for(var bank:List.of(reservations,inputReservations)) for (int i = 0; i < bank.size() && amount > 0; i++) {
            var stack = bank.get(i); if (!stack.what().equals(key)) continue;
            long taken = Math.min(amount, stack.amount()); amount -= taken;
            if (taken == stack.amount()) { bank.remove(i); i--; }
            else bank.set(i, new GenericStack(key, stack.amount()-taken));
        }
    }

    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putBoolean("compatibilityRecipe", compatibilityRecipe);
        tag.putString("recipe", recipeId.toString()); tag.putInt("station", station); tag.putInt("castSlot", castSlot); tag.putBoolean("castFromBank", castFromBank); tag.putBoolean("castFromPattern", castFromPattern);
        tag.put("fluid", GenericStack.writeTag(registries, new GenericStack(fluid, fluidBuffer)));
        tag.put("output", GenericStack.writeTag(registries, new GenericStack(output, outputPerCast)));
        if (cast != null) tag.put("cast", GenericStack.writeTag(registries, new GenericStack(cast, castCount)));
        tag.putBoolean("returnFluidToME", returnFluidToME); tag.putInt("alloyBatchAmount", alloyBatchAmount);
        tag.putBoolean("requiresAlloy", requiresAlloy);
        tag.putBoolean("consumeCast", consumeCast); tag.putInt("amount", amountPerCast);
        tag.putInt("remaining", remaining); tag.putInt("total", total);
        tag.putInt("outputDelivered", outputDelivered);
        tag.putInt("poured", poured); tag.putInt("cooling", cooling);
        tag.putBoolean("recovering", recovering);
        var inputs = new ListTag();
        pending.forEach(stack -> inputs.add(GenericStack.writeTag(registries, stack)));
        tag.put("pending", inputs);
        var forwarded=new ListTag();for(var entry:forwardedItems)if(entry.getLongValue()>0)forwarded.add(GenericStack.writeTag(registries,new GenericStack(entry.getKey(),entry.getLongValue())));
        tag.put("forwardedItems",forwarded);
        var materials=new ListTag();for(var entry:coreFluids)if(entry.getLongValue()>0)materials.add(GenericStack.writeTag(registries,new GenericStack(entry.getKey(),entry.getLongValue())));
        tag.put("coreFluids",materials);tag.putInt("meltWork",meltWork);tag.putInt("coreTemperature",coreTemperature);
        var reserved = new ListTag(); reservations.forEach(s -> reserved.add(GenericStack.writeTag(registries, s)));
        tag.put("reservations", reserved);
        var incoming=new ListTag();inputReservations.forEach(s->incoming.add(GenericStack.writeTag(registries,s)));
        tag.put("inputReservations",incoming);
        return tag;
    }

    static CastingJob load(HolderLookup.Provider registries, CompoundTag tag) {
        var job = new CastingJob();
        job.compatibilityRecipe = tag.getBoolean("compatibilityRecipe");
        job.recipeId = ResourceLocation.parse(tag.getString("recipe"));
        job.station = tag.getInt("station");
        job.castSlot = tag.contains("castSlot") ? tag.getInt("castSlot") : -1;
        job.castFromBank = tag.getBoolean("castFromBank");
        job.castFromPattern = tag.getBoolean("castFromPattern");
        var fluid = GenericStack.readTag(registries, tag.getCompound("fluid"));
        var output = GenericStack.readTag(registries, tag.getCompound("output"));
        if (fluid == null || !(fluid.what() instanceof AEFluidKey fk) || output == null || !(output.what() instanceof AEItemKey || output.what() instanceof AEFluidKey))
            throw new IllegalArgumentException("Missing casting job material types");
        job.fluid = fk; job.fluidBuffer = fluid.amount(); job.output = output.what();
        job.returnFluidToME = !tag.contains("returnFluidToME") || tag.getBoolean("returnFluidToME");
        job.alloyBatchAmount = tag.getInt("alloyBatchAmount");
        job.outputPerCast = Math.toIntExact(output.amount());
        job.outputDelivered = tag.getInt("outputDelivered");
        var cast = GenericStack.readTag(registries, tag.getCompound("cast"));
        if (cast != null && cast.what() instanceof AEItemKey ck) { job.cast = ck; job.castCount = Math.toIntExact(cast.amount()); }
        job.requiresAlloy = tag.getBoolean("requiresAlloy");
        job.consumeCast = tag.getBoolean("consumeCast"); job.amountPerCast = tag.getInt("amount");
        job.remaining = tag.getInt("remaining"); job.total = tag.getInt("total");
        job.poured = tag.getInt("poured"); job.cooling = tag.getInt("cooling");
        job.recovering = tag.getBoolean("recovering");
        for (var input : tag.getList("pending", Tag.TAG_COMPOUND)) {
            var stack = GenericStack.readTag(registries, (CompoundTag) input);
            if (stack != null) job.pending.add(stack);
        }
        for(var input:tag.getList("coreFluids",Tag.TAG_COMPOUND)) {
            var stack=GenericStack.readTag(registries,(CompoundTag)input);
            if(stack!=null && stack.what() instanceof AEFluidKey && stack.amount()>0 && stack.amount()<=CastingPlanner.MAX_FLUID)job.coreFluids.add(stack.what(),stack.amount());
        }
        for(var input:tag.getList("forwardedItems",Tag.TAG_COMPOUND)) {
            var stack=GenericStack.readTag(registries,(CompoundTag)input);
            if(stack==null || !(stack.what() instanceof AEItemKey) || stack.amount()<=0 || stack.amount()>CastingPlanner.MAX_CYCLES)
                throw new IllegalArgumentException("Invalid forwarded item claim");
            job.forwardedItems.add(stack.what(),stack.amount());
        }
        job.meltWork=Math.max(0,tag.getInt("meltWork"));job.coreTemperature=Math.clamp(tag.getInt("coreTemperature"),0,100000);
        for (var input : tag.getList("reservations", Tag.TAG_COMPOUND)) {
            var stack = GenericStack.readTag(registries, (CompoundTag)input);
            if (stack != null && stack.what() instanceof AEFluidKey && stack.amount() > 0 && stack.amount() <= CastingPlanner.MAX_FLUID) job.reservations.add(stack);
        }
        for(var input:tag.getList("inputReservations",Tag.TAG_COMPOUND)) {
            var stack=GenericStack.readTag(registries,(CompoundTag)input);
            if(stack!=null && stack.what() instanceof AEFluidKey && stack.amount()>0)job.inputReservations.add(stack);
        }
        if (job.station < 0 || job.station > 2 || job.amountPerCast <= 0 || job.remaining < 0 || job.poured < 0 || job.outputDelivered < 0 || job.outputDelivered >= job.outputPerCast)
            throw new IllegalArgumentException("Invalid casting job bounds");
        return job;
    }

    List<GenericStack> ownedMaterials() {
        var result = new ArrayList<>(pending);
        for(var entry:coreFluids)if(entry.getLongValue()>0)result.add(new GenericStack(entry.getKey(),entry.getLongValue()));
        if (station != 2 && outputDelivered > 0) result.add(new GenericStack(output, outputPerCast - outputDelivered));
        long fluidOwned = fluidBuffer + poured;
        if (fluidOwned > 0) result.add(new GenericStack(fluid, fluidOwned));
        if (cast != null && castCount > 0) result.add(new GenericStack(cast, castCount));
        return result;
    }
}
