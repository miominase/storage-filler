package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ChallengeRecordTest {

    private ChallengeRecord sample() {
        return new ChallengeRecord("11111111-2222-3333-4444-555555555555",
                "2026-09-23T01:00:00.000Z", "001", 264.5, "成功", "LINE", "メモ",
                ChallengeRecord.STATUS_PENDING, "前回は通信エラー");
    }

    @Test
    public void jsonRoundTripKeepsEveryField() throws Exception {
        ChallengeRecord before = sample();
        ChallengeRecord after = ChallengeRecord.Companion.fromJson(before.toJson());
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getAt(), after.getAt());
        assertEquals(before.getDeviceId(), after.getDeviceId());
        assertEquals(before.getHours(), after.getHours(), 0.0001);
        assertEquals(before.getResult(), after.getResult());
        assertEquals(before.getService(), after.getService());
        assertEquals(before.getMemo(), after.getMemo());
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getLastError(), after.getLastError());
    }

    @Test
    public void toJsonUsesTheFieldNamesTheApiExpects() throws Exception {
        JSONObject json = sample().toJson();
        assertEquals("11111111-2222-3333-4444-555555555555", json.getString("id"));
        assertEquals("2026-09-23T01:00:00.000Z", json.getString("at"));
        assertEquals("001", json.getString("deviceId"));
        assertEquals(264.5, json.getDouble("hours"), 0.0001);
        assertEquals("成功", json.getString("result"));
        assertEquals("LINE", json.getString("service"));
    }

    @Test
    public void isoNowFormatsUtcWithMilliseconds() {
        // 1700000000123ms = 2023-11-14T22:13:20.123Z。JVMの既定タイムゾーンが
        // UTC以外（このテスト環境ではAsia/Tokyo）でも結果が動かないことを確認する。
        String iso = ChallengeRecord.Companion.isoNow(1700000000123L);
        assertEquals("2023-11-14T22:13:20.123Z", iso);
    }

    @Test
    public void createMakesAnIdTheApiAccepts() {
        ChallengeRecord r = ChallengeRecord.Companion.create("001", 1.0, "成功", "LINE", "");
        assertTrue(r.getId(), r.getId().matches("[a-zA-Z0-9-]{16,80}"));
        assertEquals(ChallengeRecord.STATUS_PENDING, r.getStatus());
    }
}
