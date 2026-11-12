package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** Spend split by where (home/public) and how (AC/DC) you charged; a charge counts in both views. */
public class EnergyChargeSpendTest {
    private static final double E = 1e-9;

    private static EnergyModel.Charge charge(double kwh, double cost, EnergyModel.Current current, EnergyModel.Place place) {
        EnergyModel.Charge charge = new EnergyModel.Charge();
        charge.before = 20;
        charge.after = 70;
        charge.kwh = kwh;
        charge.cost = cost;
        charge.current = current;
        charge.place = place;
        return charge;
    }

    @Test public void spendIsSplitByPlaceAndByCurrent() {
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(
                charge(22, 1320, EnergyModel.Current.AC, EnergyModel.Place.HOME),
                charge(28, 2800, EnergyModel.Current.DC, EnergyModel.Place.PUBLIC),
                charge(10, 600, EnergyModel.Current.AC, EnergyModel.Place.HOME)), Double.NaN);

        // By place: two home charges, one public.
        assertEquals(2, all.home.charges);
        assertEquals(1920, all.home.money, E);
        assertEquals(32, all.home.kwh, E);
        assertEquals(1, all.onPublic.charges);
        assertEquals(2800, all.onPublic.money, E);

        // By current: two AC (the two home ones), one DC.
        assertEquals(2, all.ac.charges);
        assertEquals(1920, all.ac.money, E);
        assertEquals(1, all.dc.charges);
        assertEquals(2800, all.dc.money, E);

        // Each split sums to the total priced spend.
        assertEquals(all.money, all.home.money + all.onPublic.money, E);
        assertEquals(all.money, all.ac.money + all.dc.money, E);
        assertEquals(4720, all.money, E);
    }

    @Test public void anUnpricedChargeIsLeftOutAndAnUntaggedOneIsNotTagged() {
        EnergyModel.Charge noPrice = charge(20, Double.NaN, EnergyModel.Current.AC, EnergyModel.Place.HOME);
        EnergyModel.Charge noType = new EnergyModel.Charge();
        noType.before = 20; noType.after = 70; noType.kwh = 15; noType.cost = 900; // UNKNOWN place and current
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(noPrice, noType), Double.NaN);
        assertFalse("no price → not in any spend split", all.home.any());
        assertEquals(0, all.onPublic.charges + all.ac.charges + all.dc.charges);
        assertEquals("an untagged priced charge is 'not tagged', never guessed", 900, all.placeNotTagged.money, E);
        assertEquals(900, all.currentNotTagged.money, E);
        assertEquals("it's still in the overall total", 900, all.money, E);
    }

    @Test public void eachViewIncludingNotTaggedSumsToTheTotal() {
        EnergyModel.Charge publicNoCurrent = charge(20, 2000, EnergyModel.Current.UNKNOWN, EnergyModel.Place.PUBLIC);
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(
                charge(22, 1320, EnergyModel.Current.AC, EnergyModel.Place.HOME),
                charge(28, 2800, EnergyModel.Current.DC, EnergyModel.Place.PUBLIC),
                publicNoCurrent), Double.NaN);
        assertEquals(6120, all.money, E);
        assertEquals(all.money, all.home.money + all.onPublic.money + all.placeNotTagged.money, E);
        assertEquals(all.money, all.ac.money + all.dc.money + all.currentNotTagged.money, E);
        assertEquals("the public charge without AC/DC is the only untagged one", 2000, all.currentNotTagged.money, E);
        assertFalse(all.placeNotTagged.any());
    }

    @Test public void theHomeRateFillsInSpendForUnpricedHomeCharges() {
        EnergyModel.Charge home = new EnergyModel.Charge();
        home.before = 30; home.after = 80; home.kwh = 20; home.place = EnergyModel.Place.HOME; home.current = EnergyModel.Current.AC;
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(home), 50); // 50 LKR/kWh home rate
        assertTrue(all.home.any());
        assertEquals(1000, all.home.money, E); // 20 kWh × 50
        assertEquals(1000, all.ac.money, E);
    }
}
