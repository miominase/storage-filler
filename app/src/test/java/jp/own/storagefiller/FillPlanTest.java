package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.TimeZone;

import org.junit.Test;

public class FillPlanTest {

    private static final FillPlan P = FillPlan.INSTANCE;
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * MB;
    private static final long RESERVE = 200L * MB;

    // ---- 埋め量の上限 ----

    @Test
    public void reserveIs200MB() {
        assertEquals(RESERVE, FillPlan.RESERVE_BYTES);
    }

    @Test
    public void usableLeaves200MBAndNeverGoesNegative() {
        assertEquals(10 * GB - RESERVE, P.usableBytes(10 * GB));
        assertEquals(0L, P.usableBytes(RESERVE));
        assertEquals(0L, P.usableBytes(50 * MB));
    }

    @Test
    public void ratioIsTakenFromFreeMinusReserve() {
        // 空き 10.2GB の 70% ではなく、(10.2GB − 200MB) = 10GB の 70%
        assertEquals((long) (10 * GB * 0.7), P.ratioTarget(10 * GB + RESERVE, 0.7));
        assertEquals(0L, P.ratioTarget(100 * MB, 0.7));
    }

    @Test
    public void fixedSizeIsCappedAtFreeMinusReserve() {
        assertEquals(1 * GB, P.capTarget(1 * GB, 10 * GB));
        assertEquals(5 * GB - RESERVE, P.capTarget(50 * GB, 5 * GB));
        assertEquals(0L, P.capTarget(1 * GB, 100 * MB));
    }

    @Test
    public void nextFileSizeTakesTheSmallestOfUnitRemainingAndUsable() {
        assertEquals(512 * MB, P.nextFileSize(512 * MB, 3 * GB, 10 * GB));
        assertEquals(100 * MB, P.nextFileSize(512 * MB, 100 * MB, 10 * GB));
        // 空きが 500MB なら、ファイルは 300MB で止めて 200MB を残す
        assertEquals(300 * MB, P.nextFileSize(512 * MB, 3 * GB, 500 * MB));
        assertEquals(0L, P.nextFileSize(512 * MB, 3 * GB, 150 * MB));
    }

    // ---- ファイル名 ----

    @Test
    public void stampUsesTheGivenTimeZone() {
        // 2026-10-14 01:02:03.045 UTC = 10:02:03.045 JST
        long millis = 1791939723045L;
        assertEquals("20261014_010203_045", P.stamp(millis, TimeZone.getTimeZone("UTC")));
        assertEquals("20261014_100203_045", P.stamp(millis, TimeZone.getTimeZone("Asia/Tokyo")));
    }

    @Test
    public void twoFillsInTheSameSecondGetDifferentNames() {
        // 直接書く APP（と Android 7-9）で同名上書きにならないよう、ミリ秒まで入れる
        TimeZone utc = TimeZone.getTimeZone("UTC");
        String first = P.fileName(P.stamp(1791939723000L, utc), 1, "bin");
        String second = P.fileName(P.stamp(1791939723999L, utc), 1, "bin");
        assertEquals("dummy_20261014_010203_000_001.bin", first);
        assertEquals("dummy_20261014_010203_999_001.bin", second);
    }

    @Test
    public void fileNameHasStampSequenceAndNoSize() {
        assertEquals("dummy_20261014_100203_045_001.jpg", P.fileName("20261014_100203_045", 1, "jpg"));
        assertEquals("dummy_20261014_100203_045_042.bin", P.fileName("20261014_100203_045", 42, "bin"));
        assertEquals("dummy_20261014_100203_045_1000.mp4", P.fileName("20261014_100203_045", 1000, "mp4"));
    }

    // ---- 作成ログ ----

    @Test
    public void encodesAndDecodesMediaStoreEntries() {
        FillPlan.LogEntry e = new FillPlan.LogEntry(
                "Pictures",
                "/storage/emulated/0/Pictures/StorageFillTest/dummy_20261014_100203_045_001.jpg",
                "content://media/external/images/media/123");
        String line = P.encode(e);
        assertEquals("Pictures\t/storage/emulated/0/Pictures/StorageFillTest/dummy_20261014_100203_045_001.jpg"
                + "\tcontent://media/external/images/media/123", line);
        assertEquals(e, P.decode(line));
    }

    @Test
    public void encodesFileEntriesWithAnEmptyUri() {
        FillPlan.LogEntry e = new FillPlan.LogEntry("Apps", "/sdcard/Android/data/x/files/StorageFillTest/a.bin", null);
        String line = P.encode(e);
        assertEquals("Apps\t/sdcard/Android/data/x/files/StorageFillTest/a.bin\t", line);
        assertEquals(e, P.decode(line));
    }

    @Test
    public void readsOldPathOnlyLines() {
        assertEquals(
                new FillPlan.LogEntry(null, "/storage/emulated/0/StorageFillTest/Movies/dummy_000_512MB.mp4", null),
                P.decode("/storage/emulated/0/StorageFillTest/Movies/dummy_000_512MB.mp4"));
    }

    @Test
    public void skipsBlankAndBrokenLines() {
        assertNull(P.decode(""));
        assertNull(P.decode("   "));
        assertNull(P.decode("Pictures\t\t"));
    }

    // ---- 残り時間 ----

    @Test
    public void etaRoundsUpToTheNextSecond() {
        // 残り 1000MB を 100MB/s → 10秒、1001MB → 10.01秒 → 11秒
        assertEquals(Long.valueOf(10), P.etaSeconds(0, 1000 * MB, 100.0));
        assertEquals(Long.valueOf(11), P.etaSeconds(0, 1001 * MB, 100.0));
        assertEquals(Long.valueOf(0), P.etaSeconds(5 * GB, 5 * GB, 100.0));
    }

    @Test
    public void etaIsHiddenWhileSpeedIsUnknown() {
        assertNull(P.etaSeconds(0, GB, 0.0));
        assertNull(P.etaText(0, GB, 0.0));
        assertNull(P.etaText(0, GB, Double.NaN));
    }

    @Test
    public void etaTextShowsMinutesAndSeconds() {
        assertEquals("あと約0分14秒", P.etaText(0, 14 * 100 * MB, 100.0));
        assertEquals("あと約2分5秒", P.etaText(0, 125 * 10 * MB, 10.0));
        assertEquals("あと約85分0秒", P.etaText(0, 85 * 60 * MB, 1.0));
    }
}
