package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarChargeReviewPolicyTest {
    private CarEnergyTracker.Event charge(double gain, boolean inferred) {
        CarEnergyTracker.Event e = new CarEnergyTracker.Event();
        e.externalCharge = true; e.kind = "charge"; e.state = "complete"; e.before = 45; e.after = 45 + gain; e.inferred = inferred;
        return e;
    }
    @Test public void inferredPromptStartsAtFivePoints() {
        assertFalse(CarChargeReviewPolicy.eligible(charge(4.9,true),true));
        assertTrue(CarChargeReviewPolicy.eligible(charge(5,true),true));
        assertTrue(CarChargeReviewPolicy.eligible(charge(20,true),true));
    }
    @Test public void smallerIncreasesRemainInManualReview() {
        assertTrue(CarChargeReviewPolicy.eligible(charge(2,true),false));
    }
    @Test public void onePointInferredRiseIsRoundingNotACharge() {
        assertFalse(CarChargeReviewPolicy.eligible(charge(1,true),false));
    }
    @Test public void reportedChargeDoesNotNeedFivePoints() {
        assertTrue(CarChargeReviewPolicy.eligible(charge(1,false),true));
    }
    @Test public void incompleteMissingAndNonChargeEventsDoNotPrompt() {
        CarEnergyTracker.Event e=charge(5,true); e.state="open";
        assertFalse(CarChargeReviewPolicy.eligible(e,true));
        e.state="complete"; e.after=Double.NaN;
        assertFalse(CarChargeReviewPolicy.eligible(e,false));
        e.after=50; e.kind="drive";
        assertFalse(CarChargeReviewPolicy.eligible(e,false));
        assertFalse(CarChargeReviewPolicy.eligible(charge(0,true),false));
    }
}
