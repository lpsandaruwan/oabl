package com.softiva.oabllink;

import org.junit.Test;
import static org.junit.Assert.*;

public class BleFailureThrottleTest {
    @Test public void failuresAcrossTransportsShareOneBudgetAndExactCooldown() {
        BleFailureThrottle budget = new BleFailureThrottle();
        assertTrue(budget.allowed(0));
        budget.failed(100); budget.failed(200); assertTrue(budget.allowed(200));
        budget.failed(300); assertFalse(budget.allowed(30299)); assertTrue(budget.allowed(30300));
    }
    @Test public void successfulAuthenticationResetsFailureCount() {
        BleFailureThrottle budget = new BleFailureThrottle();
        budget.failed(100); budget.failed(200); budget.authenticated();
        budget.failed(300); budget.failed(400); assertTrue(budget.allowed(400));
        budget.failed(500); assertFalse(budget.allowed(500));
    }
    @Test public void anotherTransportCannotClearAnExistingCooldown() {
        BleFailureThrottle budget = new BleFailureThrottle();
        budget.failed(100); budget.failed(200); budget.failed(300);
        budget.authenticated(); // An already-open valid session may finish while another failed.
        assertFalse(budget.allowed(30299)); assertTrue(budget.allowed(30300));
    }
    @Test public void repeatedFailureAfterCooldownStillRequiresSuccessfulAuthenticationToReset() {
        BleFailureThrottle budget = new BleFailureThrottle();
        budget.failed(0); budget.failed(1); budget.failed(2);
        assertTrue(budget.allowed(30002)); budget.failed(30002);
        assertFalse(budget.allowed(60001)); assertTrue(budget.allowed(60002));
    }
}
