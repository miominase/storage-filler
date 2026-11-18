package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/** 共有リンクの取り出し・本文・保存。通信はせず、URLもダミーだけを使う。 */
public class SharedLinkTest {

    @Test
    public void extractsUrlFromTextWithTrailingPunctuation() {
        SharedLink.Parsed p = SharedLink.INSTANCE.parse("受け取って！ https://example.invalid/pay/abc123。", null);
        assertEquals("https://example.invalid/pay/abc123", p.getUrl());
        assertEquals("受け取って！ https://example.invalid/pay/abc123。", p.getText());
    }

    @Test
    public void stopsAtJapaneseRightAfterUrl() {
        SharedLink.Parsed p = SharedLink.INSTANCE.parse("こちら→https://example.invalid/pay/abc?x=1&y=2こちらから受け取ってね", null);
        assertEquals("https://example.invalid/pay/abc?x=1&y=2", p.getUrl());
        SharedLink.Parsed q = SharedLink.INSTANCE.parse("（https://example.invalid/pay/abc）", null);
        assertEquals("https://example.invalid/pay/abc", q.getUrl());
    }

    @Test
    public void takesFirstUrlOnly() {
        SharedLink.Parsed p = SharedLink.INSTANCE.parse("http://a.invalid/x https://b.invalid/y", null);
        assertEquals("http://a.invalid/x", p.getUrl());
    }

    @Test
    public void returnsNullWithoutHttpUrl() {
        assertNull(SharedLink.INSTANCE.parse(null, null));
        assertNull(SharedLink.INSTANCE.parse("   ", null));
        assertNull(SharedLink.INSTANCE.parse("ただの文章です", null));
        assertNull(SharedLink.INSTANCE.parse("javascript:alert(1) file:///etc/passwd", null));
        assertNull(SharedLink.INSTANCE.parse("https://", null));
    }

    @Test
    public void rejectsTooLongUrlAndTruncatesText() {
        StringBuilder longUrl = new StringBuilder("https://example.invalid/");
        for (int i = 0; i < SharedLink.MAX_URL; i++) longUrl.append('a');
        assertNull(SharedLink.INSTANCE.parse(longUrl.toString(), null));

        StringBuilder longText = new StringBuilder("https://example.invalid/ok ");
        for (int i = 0; i < 2000; i++) longText.append('あ');
        SharedLink.Parsed p = SharedLink.INSTANCE.parse(longText.toString(), null);
        assertEquals("https://example.invalid/ok", p.getUrl());
        assertEquals(SharedLink.MAX_TEXT, p.getText().length());
    }

    @Test
    public void subjectIsPrependedOnlyWhenNotAlreadyInText() {
        SharedLink.Parsed p = SharedLink.INSTANCE.parse("https://example.invalid/z", "件名");
        assertEquals("件名\nhttps://example.invalid/z", p.getText());
        SharedLink.Parsed q = SharedLink.INSTANCE.parse("件名 https://example.invalid/z", "件名");
        assertEquals("件名 https://example.invalid/z", q.getText());
    }

    @Test
    public void linkBodyHasTopLevelKeysAndNoRecordWrapper() throws Exception {
        ChallengeRecord r = ChallengeRecord.Companion.createLink("001", "https://example.invalid/p", "本文");
        assertTrue(r.isLink());
        assertFalse(r.isEndParent());
        JSONObject body = ChallengeApi.INSTANCE.linkBody("pw", r);
        assertEquals("addLink", body.getString("action"));
        assertEquals("pw", body.getString("password"));
        assertEquals(r.getId(), body.getString("id"));
        assertEquals("001", body.getString("deviceId"));
        assertEquals(r.getAt(), body.getString("at"));
        assertEquals("https://example.invalid/p", body.getString("url"));
        assertEquals("本文", body.getString("text"));
        assertFalse(body.has("record"));
    }

    @Test
    public void linkBodyOmitsEmptyText() throws Exception {
        ChallengeRecord r = ChallengeRecord.Companion.createLink("001", "https://example.invalid/p", "");
        assertFalse(ChallengeApi.INSTANCE.linkBody("pw", r).has("text"));
    }

    @Test
    public void linkSurvivesQueueRoundTripWithSameIdAndKind() throws Exception {
        File file = File.createTempFile("pending_records", ".json");
        assertTrue(file.delete());
        file.deleteOnExit();
        PendingRecords records = new PendingRecords(file);
        ChallengeRecord r = ChallengeRecord.Companion.createLink("001", "https://example.invalid/p", "本文");
        records.add(r);
        List<ChallengeRecord> back = records.all();
        assertEquals(1, back.size());
        ChallengeRecord b = back.get(0);
        assertEquals(r.getId(), b.getId());
        assertEquals(r.getAt(), b.getAt());
        assertTrue(b.isLink());
        assertEquals("https://example.invalid/p", b.getSharedUrl());
        assertEquals("本文", b.getSharedText());
        // 再送でも同じ本文になる
        assertEquals(
                ChallengeApi.INSTANCE.linkBody("pw", r).toString(),
                ChallengeApi.INSTANCE.linkBody("pw", b).toString());
    }

    @Test
    public void oldQueueEntriesStillReadAsNormalRecords() throws Exception {
        ChallengeRecord r = ChallengeRecord.Companion.fromJson(new JSONObject(
                "{\"id\":\"x\",\"at\":\"t\",\"deviceId\":\"001\",\"hours\":1,\"result\":\"成功\",\"service\":\"LINE\"}"));
        assertFalse(r.isLink());
        assertEquals("", r.getSharedUrl());
    }

    @Test
    public void summaryOfLinkShowsUrl() {
        ChallengeRecord r = ChallengeRecord.Companion.createLink("001", "https://example.invalid/p", "");
        assertEquals("共有リンク / https://example.invalid/p", ChallengeInput.INSTANCE.summary(r));
    }
}
