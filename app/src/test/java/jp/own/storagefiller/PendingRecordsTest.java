package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PendingRecordsTest {

    private ChallengeRecord record(String id) {
        return new ChallengeRecord(id, "2026-09-23T01:00:00.000Z", "001", 264.5, "成功", "LINE", "",
                ChallengeRecord.STATUS_PENDING, "");
    }

    @Test
    public void encodeThenDecodeKeepsOrderAndCount() {
        List<ChallengeRecord> list = new ArrayList<>();
        list.add(record("aaaaaaaa-1111-1111-1111-111111111111"));
        list.add(record("bbbbbbbb-2222-2222-2222-222222222222"));
        List<ChallengeRecord> back = PendingRecords.Companion.decode(PendingRecords.Companion.encode(list));
        assertEquals(2, back.size());
        assertEquals("aaaaaaaa-1111-1111-1111-111111111111", back.get(0).getId());
        assertEquals("bbbbbbbb-2222-2222-2222-222222222222", back.get(1).getId());
    }

    @Test
    public void writeThenAllRoundTripsAMultiRecordQueueThroughTheRealFile() throws Exception {
        // PendingRecords(File) はテスト専用の入り口。実機ではContext版のfilesDirを使う。
        File file = File.createTempFile("pending_records", ".json");
        assertTrue(file.delete());
        file.deleteOnExit();
        PendingRecords records = new PendingRecords(file);

        // 2回書く。2回目はwrite()内部で既存ファイルを一時ファイル経由で置き換える経路を通る。
        records.add(record("aaaaaaaa-1111-1111-1111-111111111111"));
        records.add(record("bbbbbbbb-2222-2222-2222-222222222222"));

        List<ChallengeRecord> back = records.all();
        assertEquals(2, back.size());
        assertEquals("aaaaaaaa-1111-1111-1111-111111111111", back.get(0).getId());
        assertEquals("bbbbbbbb-2222-2222-2222-222222222222", back.get(1).getId());
    }

    @Test
    public void twoInstancesOnTheSameFileDoNotLoseRecordsWhenWritingConcurrently() throws Exception {
        // 画面の回転で Activity が作り直されると、同じファイルを指す PendingRecords が2つできる。
        // ロックがインスタンス単位だと読んで・直して・書くが交互に走り、記録が消える。
        File dir = File.createTempFile("pending_dir", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        File file = new File(dir, "pending_records.json");
        final PendingRecords first = new PendingRecords(file);
        final PendingRecords second = new PendingRecords(new File(dir, "pending_records.json"));

        final int perThread = 150;
        final List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread a = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    first.add(record(String.format("aaaaaaaa-0000-0000-0000-%012d", i)));
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        Thread b = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    String id = String.format("bbbbbbbb-0000-0000-0000-%012d", i);
                    second.add(record(id));
                    // 追加と削除を混ぜる。偶数だけ消す。
                    if (i % 2 == 0) second.remove(id);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        a.start();
        b.start();
        start.countDown();
        a.join();
        b.join();

        assertTrue(errors.toString(), errors.isEmpty());
        List<ChallengeRecord> back = new PendingRecords(file).all();
        assertEquals(perThread + perThread / 2, back.size());

        // 一時ファイルが残っていないこと（毎回別名で作り、置き換え後に消えている）
        String[] left = dir.list();
        assertEquals(1, left.length);
        assertEquals("pending_records.json", left[0]);

        assertTrue(file.delete());
        assertTrue(dir.delete());
    }

    @Test
    public void decodeReturnsEmptyForGarbageInsteadOfThrowing() {
        assertEquals(0, PendingRecords.Companion.decode("").size());
        assertEquals(0, PendingRecords.Companion.decode("{").size());
        assertEquals(0, PendingRecords.Companion.decode("null").size());
    }

    @Test
    public void authAndInputErrorsStopAutomaticRetry() {
        assertTrue(PendingRecords.Companion.needsAttention("AUTH"));
        assertTrue(PendingRecords.Companion.needsAttention("DEVICE_NOT_ACTIVE"));
        assertTrue(PendingRecords.Companion.needsAttention("VALIDATION"));
        assertTrue(PendingRecords.Companion.needsAttention("CONFLICT"));
    }

    @Test
    public void serverAndNetworkFailuresStayRetryable() {
        assertFalse(PendingRecords.Companion.needsAttention("SERVER"));
        assertFalse(PendingRecords.Companion.needsAttention(""));
        assertFalse(PendingRecords.Companion.needsAttention("NETWORK"));
    }
}
