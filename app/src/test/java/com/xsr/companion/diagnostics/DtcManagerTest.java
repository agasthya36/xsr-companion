package com.xsr.companion.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DtcManagerTest {

    @Test
    public void knownCodesResolveToActionableInfo() {
        DtcManager.DtcInfo crank = DtcManager.lookup(12);
        assertEquals("Crankshaft Position Sensor", crank.title);
        assertEquals(DtcManager.Severity.CRITICAL, crank.severity);
        assertNotNull(crank.action);

        DtcManager.DtcInfo overheat = DtcManager.lookup(28);
        assertEquals(DtcManager.Severity.CRITICAL, overheat.severity);
    }

    @Test
    public void unknownCodeFallsBackInsteadOfReturningNull() {
        DtcManager.DtcInfo unknown = DtcManager.lookup(9999);
        assertNotNull(unknown);
        assertEquals(DtcManager.Severity.WARNING, unknown.severity);
    }

    @Test
    public void zeroMeansNoFault() {
        DtcManager.DtcInfo none = DtcManager.lookup(0);
        assertNotNull(none);
        assertEquals(DtcManager.Severity.OK, none.severity);
    }
}
