package com.cim.api.fluids.system;

public enum PipeTier {
    // (Температура, Кислотность, Радиация)
    BRONZE(250, 62, 12),       // железная бочка +25%
    STEEL(375, 187, 31),       // стальная +25%
    LEAD(625, 250, 93),        // свинцовая +25%
    TUNGSTEN(2500, 1000, 500); // верхний предел шкалы

    private final int maxTemperature;
    private final int maxAcidity;
    private final int maxRadiation;

    PipeTier(int maxTemperature, int maxAcidity, int maxRadiation) {
        this.maxTemperature = maxTemperature;
        this.maxAcidity = maxAcidity;
        this.maxRadiation = maxRadiation;
    }

    public int getMaxTemperature() { return maxTemperature; }
    public int getMaxAcidity() { return maxAcidity; }
    public int getMaxRadiation() { return maxRadiation; }
}