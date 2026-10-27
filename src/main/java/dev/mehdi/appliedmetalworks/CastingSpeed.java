package dev.mehdi.appliedmetalworks;

/** Shared, bounded speed calculation. Timers retain base work units when cards change. */
public final class CastingSpeed {
    private CastingSpeed() {}
    public static int multiplier(int cards) { return 1 << Math.clamp(cards, 0, 4); }
    public static int pourRate(int cards) { return 20 * multiplier(cards); }
    public static double energyPerTick(int cards) { return 2.0 * multiplier(cards); }
    /** Linear core processing speed: four cards give exactly four times the base work. */
    public static int processingRate(int base,int cards) {
        return (int)Math.clamp(((long)base*(4+3*Math.clamp(cards,0,4))+3)/4,1,Integer.MAX_VALUE);
    }
    public static int processingBatchLimit(int cards) { return Math.max(1,Math.clamp(cards,0,4)); }
    public static int coolingWork(int fluidAmount, double modifier) {
        if (!Double.isFinite(modifier) || modifier <= 0) modifier = 1;
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, fluidAmount / modifier));
    }
}
