package dev.mehdi.appliedmetalworks;

import appeng.api.stacks.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Actual standalone material processing. Each request owns an isolated molten pool. */
public final class StandaloneFoundry implements IFluidHandler {
    private final FoundryBlockEntity host;
    private final KeyCounter stored=new KeyCounter();
    String stage="idle";
    StandaloneFoundry(FoundryBlockEntity host) { this.host=host; }
    public KeyCounter available() { var copy=new KeyCounter();copy.addAll(stored);return copy; }
    void restoreStored(AEFluidKey key,int amount) { if(amount>0) { stored.add(key,amount);host.saveChanges(); } }
    int drainStored(AEFluidKey key,int limit) {
        int got=(int)Math.min(Math.max(0,limit),stored.get(key));
        if(got>0) { stored.remove(key,got);stored.removeZeros();host.saveChanges(); }return got;
    }
    public KeyCounter allFluids() {
        var copy=available();
        for(int i=0;i<host.stationCount();i++) { var job=host.jobAt(i);if(job!=null)copy.addAll(job.coreFluids); }
        return copy;
    }
    public int total() {
        long amount=0;for(var entry:stored)amount+=entry.getLongValue();
        for(int i=0;i<host.stationCount();i++) {
            var request=host.jobAt(i);if(request!=null)for(var entry:request.coreFluids)amount+=entry.getLongValue();
        }
        return (int)Math.min(Integer.MAX_VALUE,amount);
    }
    int room() { return Math.max(0,CoreConfig.FLUID_CAPACITY.get()-total()); }
    public int temperature() { return host.getFuelManager().temperature(); }
    boolean allocate(CastingJob job) {
        var needs=new KeyCounter();for(var stack:job.reservations)needs.add(stack.what(),stack.amount());
        for(var entry:needs)if(stored.get(entry.getKey())<entry.getLongValue())return false;
        for(var entry:needs) { stored.remove(entry.getKey(),entry.getLongValue());job.coreFluids.add(entry.getKey(),entry.getLongValue()); }stored.removeZeros();
        job.reservations.clear();job.inputReservations.clear();host.saveChanges();return true;
    }
    boolean process(CastingJob job) {
        stage="melting";boolean melted=false;int free=-1;
        for(int i=0;i<job.pending.size();i++) {
            var pending=job.pending.get(i);long sent=0;
            if(pending.what() instanceof AEFluidKey fluid) {
                if(free<0)free=room();sent=Math.min(pending.amount(),Math.min(free,1000));
                if(sent>0) { job.coreFluids.add(fluid,sent);free-=Math.toIntExact(sent); }else stage="fluid_full";
            } else if(pending.what() instanceof AEItemKey item && !melted) {
                melted=true;
                var holder=CastingRecipes.meltingForCore(host.getLevel(),item.toStack(),host.getFuelManager().limit());
                if(holder==null || holder.value().result.isEmpty()) { stage="temperature_limit";continue; }
                int required=Math.max(0,holder.value().minTemperature);
                if(!host.getFuelManager().heat(job,required,holder.value().maxTemperature)) { stage=host.getFuelManager().status().equals("ready")?"heating":host.getFuelManager().status();continue; }
                long volume=holder.value().result.stream().mapToLong(FluidStack::getAmount).sum();
                if(volume<=0) { stage="recipe_changed";continue; }
                if(free<0)free=room();if(volume>free) { stage="fluid_full";continue; }
                int rate=CastingSpeed.processingRate(CoreConfig.MELT_RATE.get(),host.getSpeedCards());
                job.meltWork=(int)Math.min(Integer.MAX_VALUE,Math.min(volume+rate-1,(long)job.meltWork+rate));host.saveChanges();
                if(job.meltWork<volume)continue;
                long cost=volume*CoreConfig.FE_PER_MB.get()*Math.max(1,required/1000);
                int lavaCost=(int)Math.min(Integer.MAX_VALUE,Math.max(1,(volume+3)/4));
                int limit=CastingSpeed.processingBatchLimit(host.getSpeedCards());
                while(sent<pending.amount() && sent<limit && job.meltWork>=volume && volume<=free) {
                    if(cost>Integer.MAX_VALUE || !host.getFuelManager().spend((int)cost,lavaCost,required)) { stage=cost>Integer.MAX_VALUE?"energy_capacity":host.getFuelManager().status();break; }
                    for(var fluid:holder.value().result)if(!fluid.isEmpty())job.coreFluids.add(AEFluidKey.of(fluid),fluid.getAmount());
                    free-=Math.toIntExact(volume);
                    sent++;job.meltWork-=Math.toIntExact(volume);
                }
                // Keep fractional work between items, without banking work during a fuel/storage stall.
                job.meltWork=(int)Math.min(job.meltWork,sent>0?volume-1:volume);
            }
            if(sent>0) {
                if(sent==pending.amount()) { job.pending.remove(i);i--; }
                else job.pending.set(i,new GenericStack(pending.what(),pending.amount()-sent));
                host.saveChanges();
            }
        }
        if(job.fluidBuffer>0) {
            if(free<0)free=room();long sent=Math.min(job.fluidBuffer,Math.min(free,1000));job.coreFluids.add(job.fluid,sent);job.fluidBuffer-=sent;
            if(sent>0)host.saveChanges();
        }
        if(job.remaining>0)alloy(job);
        return job.pending.isEmpty() && job.fluidBuffer==0;
    }
    private void alloy(CastingJob job) {
        long remaining=job.outputPerCast-job.outputDelivered;
        long needed=job.station==2?(job.returnFluidToME?Math.min(remaining,Math.max(1000,job.alloyBatchAmount)):remaining):job.amountPerCast;
        if(needed<=0 || job.coreFluids.get(job.fluid)>=needed)return;
        var recipes=host.getLevel().getRecipeManager().getRecipes();
        var plan=job.alloyPlan;
        if(plan==null || plan.recipes()!=recipes || !plan.target().equals(job.fluid) || plan.needed()!=needed || !sameStock(plan.inputs(),job.coreFluids)) {
            var inputs=new KeyCounter();inputs.addAll(job.coreFluids);
            var result=new KeyCounter();result.addAll(job.coreFluids);
            boolean ready=CastingPlanner.produceActual(host.getLevel(),result,job.fluid,needed);
            plan=new CastingJob.AlloyPlan(recipes,job.fluid,needed,inputs,ready?result:null);job.alloyPlan=plan;
        }
        if(plan.result()==null)return;
        var trial=plan.result();
        long change=0;for(var entry:trial)change+=entry.getLongValue();for(var entry:job.coreFluids)change-=entry.getLongValue();
        if(change>0 && change>room()) { stage="fluid_full";return; }
        long made=trial.get(job.fluid)-job.coreFluids.get(job.fluid);
        if(made>0 && !host.getFuelManager().spend((int)Math.min(Integer.MAX_VALUE,made*8),(int)Math.max(1,(made+63)/64),0)) { stage=host.getFuelManager().status();return; }
        job.coreFluids.clear();job.coreFluids.addAll(trial);job.alloyPlan=null;stage="alloying";host.saveChanges();
    }
    int amount(CastingJob job,int limit) { return (int)Math.min(limit,job.coreFluids.get(job.fluid)); }
    private static boolean sameStock(KeyCounter a,KeyCounter b) {
        for(var e:a)if(e.getLongValue()!=b.get(e.getKey()))return false;
        for(var e:b)if(e.getLongValue()!=a.get(e.getKey()))return false;
        return true;
    }
    int drain(CastingJob job,int limit) {
        int got=amount(job,limit);job.coreFluids.remove(job.fluid,got);job.coreFluids.removeZeros();if(got>0)host.saveChanges();return got;
    }
    void retain(CastingJob job) { stored.addAll(job.coreFluids);job.coreFluids.clear();host.saveChanges(); }
    // Expose an empty material tank as well as fuel, even before any fluid is stored.
    @Override public int getTanks() { return 2+entries().size(); }
    private List<GenericStack> entries() {
        var result=new ArrayList<GenericStack>();for(var entry:allFluids())if(entry.getLongValue()>0)result.add(new GenericStack(entry.getKey(),entry.getLongValue()));return result;
    }
    @Override public FluidStack getFluidInTank(int tank) {
        if(tank==0)return host.getFuelManager().lava()==0?FluidStack.EMPTY:new FluidStack(net.minecraft.world.level.material.Fluids.LAVA,host.getFuelManager().lava());
        var entries=entries();return tank>0 && tank<=entries.size()?((AEFluidKey)entries.get(tank-1).what()).toStack((int)entries.get(tank-1).amount()):FluidStack.EMPTY;
    }
    @Override public int getTankCapacity(int tank) { return tank==0?host.getFuelManager().lavaCapacity():CoreConfig.FLUID_CAPACITY.get(); }
    private boolean fuelLava(FluidStack stack) {
        return !stack.isEmpty() && AEFluidKey.of(stack).equals(AEFluidKey.of(new FluidStack(net.minecraft.world.level.material.Fluids.LAVA,1)));
    }
    @Override public boolean isFluidValid(int tank,FluidStack stack) {
        return !stack.isEmpty() && tank>=0 && tank<getTanks() && (tank!=0 || host.getFuelManager().lavaCapacity()>0 && fuelLava(stack));
    }
    @Override public int fill(FluidStack stack,FluidAction action) {
        if(host.hasQuarantinedState())return 0;
        if(stack.isEmpty())return 0;
        if(fuelLava(stack) && host.getFuelManager().lavaCapacity()>0)return host.getFuelManager().fillLava(stack.getAmount(),!action.execute());
        int accepted=Math.min(room(),stack.getAmount());
        if(action.execute() && accepted>0) { stored.add(AEFluidKey.of(stack),accepted);host.saveChanges(); }return accepted;
    }
    @Override public FluidStack drain(FluidStack stack,FluidAction action) {
        if(host.hasQuarantinedState())return FluidStack.EMPTY;
        if(stack.isEmpty())return FluidStack.EMPTY;var key=AEFluidKey.of(stack);int got=(int)Math.min(stack.getAmount(),stored.get(key));
        // Prefer the exact material advertised by drain(int), including recipe-supplied lava.
        // Component-bearing lava is never interchangeable with the plain fuel reserve.
        int fuel=fuelLava(stack)?host.getFuelManager().drainLava(stack.getAmount()-got,true):0;
        if(action.execute()) {
            if(got>0)drainStored(key,got);
            if(fuel>0)host.getFuelManager().drainLava(fuel,false);
        }
        return got+fuel==0?FluidStack.EMPTY:key.toStack(got+fuel);
    }
    @Override public FluidStack drain(int amount,FluidAction action) {
        if(amount<=0 || host.hasQuarantinedState())return FluidStack.EMPTY;
        // Active request pools are never exposed to bucket/pipe extraction.
        for(var entry:stored)if(entry.getLongValue()>0) {
            var key=(AEFluidKey)entry.getKey();return drain(key.toStack(amount),action);
        }
        return drain(new FluidStack(net.minecraft.world.level.material.Fluids.LAVA,amount),action);
    }
    void save(CompoundTag tag,HolderLookup.Provider registries) {
        var fluids=new ListTag();for(var entry:stored)if(entry.getLongValue()>0)fluids.add(GenericStack.writeTag(registries,new GenericStack(entry.getKey(),entry.getLongValue())));
        tag.put("storedMolten",fluids);
    }
    void load(CompoundTag tag,HolderLookup.Provider registries) {
        stored.clear();for(var input:tag.getList("storedMolten",Tag.TAG_COMPOUND)) {
            var stack=GenericStack.readTag(registries,(CompoundTag)input);
            if(stack!=null && stack.what() instanceof AEFluidKey && stack.amount()>0)stored.add(stack.what(),stack.amount());
        }
    }
    void clear() { stored.clear(); }
}
