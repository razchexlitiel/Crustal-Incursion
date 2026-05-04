package com.cim.api.fluids.system;

public enum BarrelTier {
    // capacity, коррозия, нагрев, радиация, утечка (мБ/сек). 0 = не течёт
    CORRUPTED(16000, 0, 0, 0, 900),
    LEAKING(16000, 0, 100, 0, 50),
    IRON(16000, 50, 200, 10, 0),
    STEEL(24000, 150, 300, 25, 0),
    LEAD(20000, 200, 500, 75, 0);

    private final int capacity;
    private final int corrosionResistance;
    private final int heatResistance;
    private final int radiationResistance;
    private final int leakRate; // мБ в секунду

    BarrelTier(int capacity, int corrosion, int heat, int radiation, int leakRate) {
        this.capacity = capacity;
        this.corrosionResistance = corrosion;
        this.heatResistance = heat;
        this.radiationResistance = radiation;
        this.leakRate = leakRate;
    }

    public int getCapacity() { return capacity; }
    public int getCorrosionResistance() { return corrosionResistance; }
    public int getHeatResistance() { return heatResistance; }
    public int getRadiationResistance() { return radiationResistance; }
    public int getLeakRate() { return leakRate; }
    public boolean isLeaking() { return leakRate > 0; }
}