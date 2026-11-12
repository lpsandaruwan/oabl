package com.softiva.oabl;
import org.junit.Test;
import static org.junit.Assert.*;
public class CarChargePricingTest {
    @Test public void meterEnergyTimesRateAndTotalOverride(){
        EnergyModel.Charge c=new EnergyModel.Charge();c.kwh=8.5;c.rate=50;assertEquals(425,CarChargePricing.actual(c,99),0);
        c.cost=400;assertEquals(400,CarChargePricing.actual(c,99),0);
    }
    @Test public void outsideNeverInheritsHomeTariff(){
        EnergyModel.Charge c=new EnergyModel.Charge();c.place=EnergyModel.Place.PUBLIC;c.kwh=8;
        assertTrue(Double.isNaN(CarChargePricing.rate(c,50)));assertTrue(Double.isNaN(CarChargePricing.actual(c,50)));
    }
    @Test public void capacityBasedCostRemainsAnEstimate(){
        EnergyModel.Charge c=new EnergyModel.Charge();c.rate=50;
        CarEnergyTracker.Event e=new CarEnergyTracker.Event();e.before=40;e.after=60;e.capacity=40;
        assertEquals(400,CarChargePricing.estimate(e,c,0,Double.NaN,40),0);assertTrue(Double.isNaN(CarChargePricing.actual(c,0)));
        // Once complete measured charges teach grid kWh per point, losses are included.
        assertEquals(470,CarChargePricing.estimate(e,c,0,0.47,40),1e-9);
    }
    @Test public void freeChargingIsValid(){
        EnergyModel.Charge c=new EnergyModel.Charge();c.kwh=8;c.rate=0;assertEquals(0,CarChargePricing.actual(c,50),0);
    }
}
