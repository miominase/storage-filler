package jp.own.storagefiller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SetupLinkTest {

    private static final SetupLink L = SetupLink.INSTANCE;

    @Test
    public void acceptsTheCustomSchemeInAnyCase() {
        assertTrue(L.isSetupTarget("storagefiller", "setup", null));
        assertTrue(L.isSetupTarget("StorageFiller", "SETUP", ""));
    }

    @Test
    public void acceptsTheHttpsFormWithSetupPathInAnyCase() {
        assertTrue(L.isSetupTarget("https", "storagefiller.invalid", "/setup"));
        assertTrue(L.isSetupTarget("HTTPS", "StorageFiller.Invalid", "/Setup"));
        assertTrue(L.isSetupTarget("https", "storagefiller.invalid", "/SETUP"));
    }

    @Test
    public void rejectsOtherHostsPathsAndSchemes() {
        assertFalse(L.isSetupTarget("https", "storagefiller.invalid", "/other"));
        assertFalse(L.isSetupTarget("https", "storagefiller.invalid", null));
        assertFalse(L.isSetupTarget("https", "evil.example", "/setup"));
        assertFalse(L.isSetupTarget("http", "storagefiller.invalid", "/setup"));
        assertFalse(L.isSetupTarget(null, "setup", null));
        assertFalse(L.isSetupTarget("storagefiller", null, null));
    }
}
