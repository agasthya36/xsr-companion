package com.xsr.companion.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServiceItemTest {

    private MaintenanceManager.ServiceItem item() {
        return new MaintenanceManager.ServiceItem(
                "chain_lube", "Chain", "Lube it", 500, 1000.0);
    }

    @Test
    public void remainingIsNegativeWhenOverdue() {
        assertEquals(500.0, item().getRemainingKm(1000.0), 1e-9);
        assertEquals(250.0, item().getRemainingKm(1250.0), 1e-9);
        assertEquals(-100.0, item().getRemainingKm(1600.0), 1e-9);
    }

    @Test
    public void progressIsCappedAtOneHundred() {
        assertEquals(0, item().getProgressPercent(1000.0));
        assertEquals(50, item().getProgressPercent(1250.0));
        assertEquals(100, item().getProgressPercent(9000.0));
    }

    @Test
    public void overdueAndDueSoonRespectTenPercentWindow() {
        MaintenanceManager.ServiceItem item = item();

        assertFalse(item.isOverdue(1000.0));
        assertFalse(item.isDueSoon(1000.0));

        assertTrue(item.isDueSoon(1450.0));
        assertFalse(item.isDueSoon(1500.0));
        assertTrue(item.isOverdue(1500.0));

        assertTrue(item.isOverdue(1501.0));
        assertFalse(item.isDueSoon(1501.0));
    }

    @Test
    public void baselineNeverReportsNegativeElapsed() {
        assertEquals(0.0, item().getElapsedKm(900.0), 1e-9);
    }
}
