package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarLinkConnectionTest {
    @Test public void noMessageIsDisconnected(){assertFalse(new CarLinkConnection().connected(1000));}
    @Test public void successfulMessageConnectsEvenWithoutBattery(){CarLinkConnection c=new CarLinkConnection();c.message(100);assertTrue(c.connected(100));}
    @Test public void expiryIncludesBoundaryThenDropsStalledTransport(){CarLinkConnection c=new CarLinkConnection();c.message(100);assertTrue(c.connected(30100));assertFalse(c.connected(30101));}
    @Test public void explicitDisconnectDoesNotWaitForBatteryToExpire(){CarLinkConnection c=new CarLinkConnection();c.message(100);c.disconnected();assertFalse(c.connected(101));}
    @Test public void messagesRefreshAndReconnect(){CarLinkConnection c=new CarLinkConnection();c.message(100);c.message(30000);assertTrue(c.connected(50000));c.disconnected();c.message(60000);assertTrue(c.connected(60001));}
    @Test public void clockRollbackDoesNotLookConnected(){CarLinkConnection c=new CarLinkConnection();c.message(100);assertFalse(c.connected(99));}
}
