package com.xsr.companion.protocol;

public class BikeTelemetry {
    public int speedKmh = 0;
    public int engineRpm = 0;
    public double throttlePct = 0.0;
    public int coolantTemp = 0;
    public int airTemp = 0;
    public double atmosphericPressure = 0.0;
    public double odometerKm = 0.0;
    public boolean warningLamp = false;
    public int dtcCount = 0;
    public int dtcCode = 0;
    public double fuelInjection = 0.0;
    public long ecuTotalTimeSec = 0;
    public int ignitionCycles = 0;

    // Fuel & Mileage Calculations
    public double instantMileageKmL = 0.0;
    public double fuelConsumptionRateLh = 0.0;
    public double tripFuelConsumedL = 0.0;
    public double tripAvgMileageKmL = 0.0;
    public double remainingFuelL = 10.0;

    public long timestamp = 0;

    public BikeTelemetry() {
        this.timestamp = System.currentTimeMillis();
    }

    public void copyFrom(BikeTelemetry other) {
        if (other == null) return;
        this.speedKmh = other.speedKmh;
        this.engineRpm = other.engineRpm;
        this.throttlePct = other.throttlePct;
        this.coolantTemp = other.coolantTemp;
        this.airTemp = other.airTemp;
        this.atmosphericPressure = other.atmosphericPressure;
        this.odometerKm = other.odometerKm;
        this.warningLamp = other.warningLamp;
        this.dtcCount = other.dtcCount;
        this.dtcCode = other.dtcCode;
        this.fuelInjection = other.fuelInjection;
        this.ecuTotalTimeSec = other.ecuTotalTimeSec;
        this.ignitionCycles = other.ignitionCycles;
        this.instantMileageKmL = other.instantMileageKmL;
        this.fuelConsumptionRateLh = other.fuelConsumptionRateLh;
        this.tripFuelConsumedL = other.tripFuelConsumedL;
        this.tripAvgMileageKmL = other.tripAvgMileageKmL;
        this.remainingFuelL = other.remainingFuelL;
        this.timestamp = other.timestamp;
    }
}
