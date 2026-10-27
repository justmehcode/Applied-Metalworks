package dev.mehdi.appliedmetalworks;
import net.neoforged.neoforge.common.ModConfigSpec;
public final class CoreConfig {
    private static final ModConfigSpec.Builder B=new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue CAPACITOR_FE=B.defineInRange("fePerCapacitor",40000,1,1000000);
    public static final ModConfigSpec.IntValue FE_TEMPERATURE=B.defineInRange("energyTemperatureLimit",6000,1,100000);
    public static final ModConfigSpec.IntValue LAVA_TEMPERATURE=B.defineInRange("lavaTemperatureLimit",1000,1,100000);
    public static final ModConfigSpec.IntValue FLUID_CAPACITY=B.defineInRange("moltenCapacity",64000,1000,16000000);
    public static final ModConfigSpec.IntValue LAVA_PER_TANK=B.defineInRange("lavaPerFuelTank",4000,1000,1000000);
    public static final ModConfigSpec.IntValue FE_PER_MB=B.defineInRange("fePerMeltedMillibucket",32,1,10000);
    public static final ModConfigSpec.IntValue MELT_RATE=B.defineInRange("meltingMillibucketsPerTick",16,1,10000);
    public static final ModConfigSpec SPEC=B.build();
    private CoreConfig() {}
}
