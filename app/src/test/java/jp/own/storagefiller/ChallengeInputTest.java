package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

public class ChallengeInputTest {

    private static final ChallengeInput IN = ChallengeInput.INSTANCE;

    @Test
    public void formatHoursAlwaysUsesADotEvenOnCommaLocales() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("37.52", IN.formatHours(37.5234));
            Locale.setDefault(new Locale("fr", "FR"));
            assertEquals("0.00", IN.formatHours(0.0));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void parseHoursAcceptsDotAndComma() {
        assertEquals(37.52, IN.parseHours("37.52"), 1e-9);
        assertEquals(37.52, IN.parseHours("37,52"), 1e-9);
        assertEquals(12.0, IN.parseHours(" 12 "), 1e-9);
        assertEquals(0.0, IN.parseHours("0"), 1e-9);
    }

    @Test
    public void parseHoursRejectsNegativeNonFiniteAndGarbage() {
        assertNull(IN.parseHours(""));
        assertNull(IN.parseHours("abc"));
        assertNull(IN.parseHours("-1"));
        assertNull(IN.parseHours("NaN"));
        assertNull(IN.parseHours("Infinity"));
        assertNull(IN.parseHours("1,2,3"));
    }

    @Test
    public void apiUrlAcceptsOnlyAppsScriptWebApps() {
        assertTrue(IN.isValidApiUrl("https://script.google.com/macros/s/AKfycbxxxx/exec"));
        assertTrue(IN.isValidApiUrl("  https://script.google.com/macros/s/AKfycbxxxx/exec  "));
        assertTrue(IN.isValidApiUrl("HTTPS://Script.Google.com/macros/s/AKfycbxxxx/exec"));
    }

    @Test
    public void apiUrlRejectsOtherHostsSchemesAndPaths() {
        assertFalse(IN.isValidApiUrl("http://script.google.com/macros/s/AKfycbxxxx/exec"));
        assertFalse(IN.isValidApiUrl("https://evil.example/macros/s/AKfycbxxxx/exec"));
        assertFalse(IN.isValidApiUrl("https://script.google.com.evil.example/macros/s/x/exec"));
        assertFalse(IN.isValidApiUrl("https://script.google.com@evil.example/macros/s/x/exec"));
        assertFalse(IN.isValidApiUrl("https://user@script.google.com/macros/s/x/exec"));
        assertFalse(IN.isValidApiUrl("https://script.google.com:8443/macros/s/x/exec"));
        assertFalse(IN.isValidApiUrl("https://script.google.com/"));
        assertFalse(IN.isValidApiUrl("https://script.google.com/macros/x/s/"));
        assertFalse(IN.isValidApiUrl("storagefiller://setup"));
        assertFalse(IN.isValidApiUrl("not a url"));
        assertFalse(IN.isValidApiUrl(""));
    }
}
