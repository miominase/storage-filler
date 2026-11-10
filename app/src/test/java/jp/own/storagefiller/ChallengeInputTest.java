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
    public void parseCountAcceptsNonNegativeIntegers() {
        assertEquals(Integer.valueOf(0), IN.parseCount("0"));
        assertEquals(Integer.valueOf(30), IN.parseCount(" 30 "));
        assertEquals(Integer.valueOf(7), IN.parseCount("007"));
        assertEquals(Integer.valueOf(2147483647), IN.parseCount("2147483647"));
    }

    @Test
    public void parseCountRejectsEverythingElse() {
        assertNull(IN.parseCount(""));
        assertNull(IN.parseCount("   "));
        assertNull(IN.parseCount("-1"));
        assertNull(IN.parseCount("+5"));
        assertNull(IN.parseCount("1.5"));
        assertNull(IN.parseCount("1,000"));
        assertNull(IN.parseCount("abc"));
        assertNull(IN.parseCount("１２"));
        assertNull(IN.parseCount("2147483648"));
    }

    @Test
    public void deviceIdFormat() {
        assertTrue(IN.isValidDeviceId("37"));
        assertTrue(IN.isValidDeviceId("001"));
        assertTrue(IN.isValidDeviceId("dev_A-1"));
        assertFalse(IN.isValidDeviceId(""));
        assertFalse(IN.isValidDeviceId(" 37"));
        assertFalse(IN.isValidDeviceId("3 7"));
        assertFalse(IN.isValidDeviceId("37\n"));
        assertFalse(IN.isValidDeviceId("端末"));
        assertFalse(IN.isValidDeviceId("a/b"));
        assertFalse(IN.isValidDeviceId("1234567890123456789012345678901234567890x"));
    }

    @Test
    public void summaryDistinguishesKinds() {
        ChallengeRecord rec = ChallengeRecord.Companion.create(
                "001", "u", 1.5, "成功", "LINE", "", "42", null, null, true);
        assertEquals("成功 / LINE / 1.50h / 親:42 / 親を終了", IN.summary(rec));
        ChallengeRecord end = ChallengeRecord.Companion.createEndParent("37", ChallengeRecord.REASON_RESET);
        assertEquals("親の終了（初期化）", IN.summary(end));
        ChallengeRecord old = ChallengeRecord.Companion.create("001", "u", 1.5, "失敗", "Google", "");
        assertEquals("失敗 / Google / 1.50h", IN.summary(old));
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
