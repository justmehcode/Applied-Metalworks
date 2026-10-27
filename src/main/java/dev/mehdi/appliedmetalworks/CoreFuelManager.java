package dev.mehdi.appliedmetalworks;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;

/** Standalone heating resources; FE is never converted into AE network power. */
public final class CoreFuelManager {
    private static boolean fluxResolved;
    private static AEKey fluxFe;
    private static net.minecraft.world.item.Item inductionCard;
    // Resolve the optional integration once, without enumerating the ME inventory.
    private static AEKey fluxFe() {
        if(!fluxResolved) {
            fluxResolved=true;
            if(net.neoforged.fml.ModList.get().isLoaded("appflux"))try {
                var type=Class.forName("com.glodblock.github.appflux.common.me.key.type.EnergyType");
                var fe=type.getField("FE").get(null);
                var key=(AEKey)Class.forName("com.glodblock.github.appflux.common.me.key.FluxKey").getMethod("of",type).invoke(null,fe);
                if(isFluxFE(key)) {
                    fluxFe=key;
                    inductionCard=(net.minecraft.world.item.Item)Class.forName("com.glodblock.github.appflux.common.AFSingletons").getField("INDUCTION_CARD").get(null);
                }
            } catch(ReflectiveOperationException | LinkageError incompatible) {
                org.slf4j.LoggerFactory.getLogger(CoreFuelManager.class).warn("AppliedFlux FE integration unavailable; direct FE input remains available",incompatible);
            }
        }
        return fluxFe;
    }
    public enum Mode { AUTO, ENERGY, LAVA }
    private final FoundryBlockEntity host;
    private Mode mode=Mode.AUTO;
    private int lava;
    private long legacyFe,legacyLava;
    private String status="idle";
    private long lastTick=Long.MIN_VALUE;
    private long lastHeatTick=Long.MIN_VALUE;
    private long nextFePoll=Long.MIN_VALUE,nextLavaPoll=Long.MIN_VALUE;
    private int feMissDelay=1,lavaMissDelay=1;
    private long nextProviderPoll=Long.MIN_VALUE;
    private int providerMissDelay=1;
    private int temperature;
    public int temperature() { return temperature; }
    public boolean keepHot() { return host.isCore(); }
    public final Buffer energy=new Buffer();
    public final class Buffer extends EnergyStorage {
        Buffer() { super(0,32768,32768); }
        void resize(int value) { capacity=value;energy=Math.min(energy,capacity); }
        void restore(int value) { energy=Math.clamp(value,0,capacity); }
        boolean spend(int amount) { if(energy<amount)return false;energy-=amount;host.saveChanges();return true; }
        @Override public int receiveEnergy(int amount,boolean simulate) {
            if(host.hasQuarantinedState())return 0;
            int got=super.receiveEnergy(amount,simulate);if(got>0 && !simulate)host.saveChanges();return got;
        }
        @Override public int extractEnergy(int amount,boolean simulate) {
            int got=super.extractEnergy(amount,simulate);if(got>0 && !simulate)host.saveChanges();return got;
        }
    }
    CoreFuelManager(FoundryBlockEntity host) { this.host=host; }
    public Mode mode() { return mode; }
    public boolean hasSource() { return mode != Mode.AUTO; }
    public String status() { return status; }
    public int lava() { return lava; }
    public long legacyFe() { return legacyFe; }
    public long legacyLava() { return legacyLava; }
    void restoreLegacy() {
        int fe=(int)Math.min(legacyFe,energy.getMaxEnergyStored()-energy.getEnergyStored());
        if(fe>0) { energy.restore(energy.getEnergyStored()+fe);legacyFe-=fe;host.saveChanges(); }
        int fuel=(int)Math.min(legacyLava,lavaCapacity()-lava);
        if(fuel>0) { lava+=fuel;legacyLava-=fuel;host.saveChanges(); }
    }
    public int lavaCapacity() { return mode==Mode.LAVA?host.getCapacitors().getStackInSlot(0).getCount()*CoreConfig.LAVA_PER_TANK.get():0; }
    public int limit() { return mode==Mode.LAVA?CoreConfig.LAVA_TEMPERATURE.get():mode==Mode.ENERGY?CoreConfig.FE_TEMPERATURE.get():0; }
    public void refreshCapacity() {
        var previous=mode;int oldCapacity=energy.getMaxEnergyStored();
        var stack=host.getCapacitors()==null?net.minecraft.world.item.ItemStack.EMPTY:host.getCapacitors().getStackInSlot(0);
        int count=Math.min(64,stack.getCount());
        mode=FoundryBlockEntity.isCapacitor(stack)?Mode.ENERGY:FoundryBlockEntity.isFuelTank(stack)?Mode.LAVA:Mode.AUTO;
        energy.resize(mode==Mode.ENERGY?count*CoreConfig.CAPACITOR_FE.get():0);
        lava=Math.min(lava,lavaCapacity());
        if(previous!=mode || oldCapacity!=energy.getMaxEnergyStored()) { nextFePoll=Long.MIN_VALUE;nextLavaPoll=Long.MIN_VALUE;nextProviderPoll=Long.MIN_VALUE;feMissDelay=1;lavaMissDelay=1;providerMissDelay=1; }
        restoreLegacy();
    }
    public static boolean isFluxFE(AEKey key) {
        return key.getType().getId().equals(ResourceLocation.parse("appflux:flux")) && key.getId().equals(ResourceLocation.parse("appflux:fe"));
    }
    public int fillLava(int amount,boolean simulate) {
        int accepted=Math.min(Math.max(0,amount),lavaCapacity()-lava);
        if(!simulate && accepted>0) { lava+=accepted;host.saveChanges(); }return accepted;
    }
    void tickControllerEnergy() {
        var level=host.getLevel();
        if(host.isCore() || host.hasQuarantinedState() || level==null || level.isClientSide())return;
        if(lastTick==level.getGameTime())return;lastTick=level.getGameTime();
        int received=pullProviderEnergy(host.getEnergyInput());
        if(host.getMainNode().isActive())pullStoredEnergy(host.getEnergyInput(),32768-received);
    }
    private int pullProviderEnergy(net.neoforged.neoforge.energy.IEnergyStorage target) {
        long now=host.getLevel().getGameTime();if(now<nextProviderPoll)return 0;
        var key=fluxFe();if(key==null || inductionCard==null || target.receiveEnergy(32768,true)<=0)return 0;
        int received=0;
        for(var direction:net.minecraft.core.Direction.values()) {
            var pos=host.getBlockPos().relative(direction);var face=direction.getOpposite();
            if(!host.getLevel().hasChunkAt(pos))continue;
            Object neighbor=host.getLevel().getBlockEntity(pos);
            if(neighbor instanceof appeng.api.parts.IPartHost parts)neighbor=parts.getPart(face);
            // Induction hosts include Interfaces and addon providers with their own logic API.
            // Card energy connections are independent of a provider's pattern-output direction.
            appeng.api.upgrades.IUpgradeableObject upgrades=neighbor instanceof appeng.api.upgrades.IUpgradeableObject object?object:null;
            if(upgrades==null && neighbor instanceof appeng.helpers.patternprovider.PatternProviderLogicHost provider
                    && provider.getLogic() instanceof appeng.api.upgrades.IUpgradeableObject object)upgrades=object;
            if(upgrades==null || !upgrades.isUpgradedWith(inductionCard))continue;
            var source=neighbor instanceof appeng.api.networking.security.IActionHost actionHost?actionHost:null;
            var node=source==null?null:source.getActionableNode();
            if(source!=null && (node==null || !node.isActive()))continue;
            int wanted=target.receiveEnergy(32768-received,true);if(wanted<=0)break;
            // Drain provider-buffer FE first, then its actual FE network storage. AppliedFlux's
            // normal sender skips same-grid targets, so this pull must not depend on that ticker.
            var capability=host.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pos,face);
            if(capability!=null && capability.canExtract()) {
                int taken=capability.extractEnergy(wanted,false);
                received+=target.receiveEnergy(taken,false);wanted-=taken;
            }
            if(wanted>0 && node!=null) {
                int taken=(int)node.getGrid().getStorageService().getInventory().extract(key,wanted,Actionable.MODULATE,IActionSource.ofMachine(source));
                received+=target.receiveEnergy(taken,false);
            }
            if(received>=32768)break;
        }
        providerMissDelay=received>0?1:Math.min(20,providerMissDelay*2);nextProviderPoll=now+providerMissDelay;
        return received;
    }
    private void pullStoredEnergy(net.neoforged.neoforge.energy.IEnergyStorage target,int budget) {
        long now=host.getLevel().getGameTime();if(now<nextFePoll)return;
        var key=fluxFe();if(key==null)return;
        int wanted=target.receiveEnergy(budget,true);if(wanted<=0)return;
        var grid=host.getMainNode().getGrid();if(grid==null)return;
        int taken=(int)grid.getStorageService().getInventory().extract(key,wanted,Actionable.MODULATE,IActionSource.ofMachine(host));
        // Both target views are stable for this synchronous server-thread transfer.
        if(taken>0)target.receiveEnergy(taken,false);
        if(taken>0)feMissDelay=1;else feMissDelay=Math.min(20,feMissDelay*2);
        nextFePoll=now+feMissDelay;
    }
    public void tick() {
        var level=host.getLevel();
        if(!host.isCore() || host.hasQuarantinedState() || level==null || level.isClientSide())return;
        if(lastTick==level.getGameTime())return;lastTick=level.getGameTime();refreshCapacity();
        int received=mode==Mode.ENERGY?pullProviderEnergy(energy):0;
        // The local heater does not depend on an ME channel or an available network.
        // Only network resource transfers require an active grid connection.
        var grid=host.getMainNode().getGrid();
        if(host.getMainNode().isActive() && grid!=null) {
            var inventory=grid.getStorageService().getInventory();var source=IActionSource.ofMachine(host);
            if(mode==Mode.ENERGY)pullStoredEnergy(energy,32768-received);
            if(mode==Mode.LAVA) {
                int missing=Math.min(1000,lavaCapacity()-lava);
                if(missing>0 && level.getGameTime()>=nextLavaPoll) {
                    int taken=(int)inventory.extract(AEFluidKey.of(new FluidStack(Fluids.LAVA,1)),missing,Actionable.MODULATE,source);fillLava(taken,false);
                    if(taken>0)lavaMissDelay=1;else lavaMissDelay=Math.min(20,lavaMissDelay*2);
                    nextLavaPoll=level.getGameTime()+lavaMissDelay;
                }
            }
        }
        if(hasSource())heatToward(limit());
        else status="missing_source";
    }
    int drainLava(int amount,boolean simulate) {
        int taken=Math.min(Math.max(0,amount),lava);
        if(!simulate && taken>0) { lava-=taken;host.saveChanges(); }
        return taken;
    }
    public boolean spend(int fe,int lavaCost,int temperature) {
        if(mode==Mode.AUTO) { status="missing_source";return false; }
        if(mode==Mode.ENERGY && temperature<=CoreConfig.FE_TEMPERATURE.get() && energy.spend(fe)) { status="ready";return true; }
        if(mode==Mode.LAVA && temperature<=CoreConfig.LAVA_TEMPERATURE.get() && lava>=lavaCost) {
            lava-=lavaCost;host.saveChanges();status="ready";return true;
        }
        status=temperature>limit()?"temperature_limit":(mode==Mode.LAVA?"missing_lava":"missing_energy");
        if(mode==Mode.AUTO && temperature<=CoreConfig.LAVA_TEMPERATURE.get())status="missing_fuel";
        if(mode!=Mode.LAVA && fe>energy.getMaxEnergyStored() && (mode==Mode.ENERGY || temperature>CoreConfig.LAVA_TEMPERATURE.get()))status="energy_capacity";
        return false;
    }
    boolean heat(CastingJob job,int required) {
        return heat(job,required,0);
    }
    boolean heat(CastingJob job,int required,int maximum) {
        if(!hasSource() || required>limit()) { status=hasSource()?"temperature_limit":"missing_source";return false; }
        boolean ready=heatToward(required);
        int materialTemperature=maximum>0?Math.min(temperature,maximum):temperature;
        if(job.coreTemperature!=materialTemperature) { job.coreTemperature=materialTemperature;host.saveChanges(); }
        return ready;
    }
    private boolean heatToward(int required) {
        if(temperature>=required) { status="ready";return true; }
        var level=host.getLevel();long now=level==null?0:level.getGameTime();
        if(lastHeatTick==now)return false;
        lastHeatTick=now;
        int rate=CastingSpeed.processingRate(100,host.getSpeedCards());
        if(temperature<required) {
            int advance=Math.min(rate,required-temperature),steps=(advance+99)/100;
            int affordable=mode==Mode.ENERGY?energy.getEnergyStored()/64:lava;
            if(affordable>0 && affordable<steps) { steps=affordable;advance=Math.min(advance,steps*100); }
            if(!spend(64*steps,steps,required))return false;
            temperature+=advance;status=temperature>=required?"ready":"heating";host.saveChanges();return false;
        }
        return true;
    }
    void save(CompoundTag tag) { tag.putString("fuelMode",mode.name());tag.putInt("coreEnergy",energy.getEnergyStored());tag.putInt("coreLava",lava);tag.putLong("legacyFeReserve",legacyFe);tag.putLong("legacyLavaReserve",legacyLava);tag.putInt("retainedTemperature",temperature);tag.putBoolean("keepHot",true); }
    void load(CompoundTag tag) {
        legacyFe=0;legacyLava=0;
        refreshCapacity();energy.restore(tag.getInt("coreEnergy"));
        long oldFe=Math.max(0,tag.getLong("coreEnergy")),oldLava=Math.max(0,tag.contains("coreLava")?tag.getLong("coreLava"):tag.getLong("heldLava"));
        energy.restore((int)Math.min(Integer.MAX_VALUE,oldFe));lava=(int)Math.min(oldLava,lavaCapacity());
        legacyFe=Math.max(0,tag.getLong("legacyFeReserve"))+Math.max(0,oldFe-energy.getEnergyStored());
        legacyLava=Math.max(0,tag.getLong("legacyLavaReserve"))+Math.max(0,oldLava-lava);
        temperature=tag.contains("retainedTemperature")?Math.clamp(tag.getInt("retainedTemperature"),0,100000):host.activeJobs().stream().mapToInt(j->j.coreTemperature).max().orElse(0);
        // Old saved heat-mode flags no longer disable automatic heating.
        restoreLegacy();lastTick=Long.MIN_VALUE;lastHeatTick=Long.MIN_VALUE;nextFePoll=Long.MIN_VALUE;nextLavaPoll=Long.MIN_VALUE;nextProviderPoll=Long.MIN_VALUE;feMissDelay=1;lavaMissDelay=1;providerMissDelay=1;
    }
    void clear() { energy.restore(0);lava=0;legacyFe=0;legacyLava=0;temperature=0;lastHeatTick=Long.MIN_VALUE; }
}
