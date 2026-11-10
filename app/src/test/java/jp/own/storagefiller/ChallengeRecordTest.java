package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ChallengeRecordTest {

    private ChallengeRecord sample() {
        return new ChallengeRecord("11111111-2222-3333-4444-555555555555",
                "2026-09-23T01:00:00.000Z", "001", 264.5, "成功", "LINE", "メモ",
                ChallengeRecord.STATUS_PENDING, "前回は通信エラー", "user0001", true);
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
        ChallengeRecord r = ChallengeRecord.Companion.create("001", "user0001", 1.0, "成功", "LINE", "");
        assertTrue(r.getId(), r.getId().matches("[a-zA-Z0-9-]{16,80}"));
        assertEquals(ChallengeRecord.STATUS_PENDING, r.getStatus());
    }

    @Test
    public void fromJson_withoutAccountFields() throws Exception {
        // アップデート前に保存された未送信記録（キーなし）も読める
        JSONObject old = sample().toJson();
        old.remove("accountId");
        old.remove("overUse");
        ChallengeRecord r = ChallengeRecord.Companion.fromJson(old);
        assertEquals("", r.getAccountId());
        assertEquals(false, r.getOverUse());
    }

    @Test
    public void toJson_roundTripsAccountFields() throws Exception {
        ChallengeRecord r = ChallengeRecord.Companion.fromJson(sample().toJson());
        assertEquals("user0001", r.getAccountId());
        assertEquals(true, r.getOverUse());
    }

    @Test
    public void fromJson_oldQueueEntryBecomesAPlainRecord() throws Exception {
        // 親の項目が入る前に保存された未送信（キーなし）も読める
        JSONObject old = sample().toJson();
        for (String k : new String[]{"kind", "parentDeviceId", "checkinReward", "invitePoints", "endParent", "endReason"}) {
            old.remove(k);
        }
        ChallengeRecord r = ChallengeRecord.Companion.fromJson(old);
        assertEquals(ChallengeRecord.KIND_RECORD, r.getKind());
        assertEquals("", r.getParentDeviceId());
        assertEquals(null, r.getCheckinReward());
        assertEquals(null, r.getInvitePoints());
        assertEquals(false, r.getEndParent());
        assertEquals(false, r.isEndParent());
    }

    @Test
    public void toJson_roundTripsParentFields() throws Exception {
        ChallengeRecord before = ChallengeRecord.Companion.create(
                "001", "user0001", 1.0, "成功", "LINE", "", "42", 0, 7, true);
        ChallengeRecord after = ChallengeRecord.Companion.fromJson(before.toJson());
        assertEquals("42", after.getParentDeviceId());
        assertEquals(Integer.valueOf(0), after.getCheckinReward());
        assertEquals(Integer.valueOf(7), after.getInvitePoints());
        assertEquals(true, after.getEndParent());
    }

    @Test
    public void create_dropsInvitePointsForFailure() {
        ChallengeRecord r = ChallengeRecord.Companion.create(
                "001", "user0001", 1.0, "失敗", "LINE", "", "42", 10, 7, true);
        assertEquals(null, r.getInvitePoints());
        assertEquals(Integer.valueOf(10), r.getCheckinReward());
        assertEquals(true, r.getEndParent());
    }

    @Test
    public void createEndParent_roundTripsAndIsSeparateKind() throws Exception {
        ChallengeRecord e = ChallengeRecord.Companion.createEndParent("37", ChallengeRecord.REASON_RESET);
        assertTrue(e.getId(), e.getId().matches("[a-zA-Z0-9-]{16,80}"));
        assertTrue(e.isEndParent());
        ChallengeRecord back = ChallengeRecord.Companion.fromJson(e.toJson());
        assertEquals(ChallengeRecord.KIND_END_PARENT, back.getKind());
        assertEquals("reset", back.getEndReason());
        assertEquals(e.getAt(), back.getAt());
        assertEquals("37", back.getDeviceId());
    }
}
