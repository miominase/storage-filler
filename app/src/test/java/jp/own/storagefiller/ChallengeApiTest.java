package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChallengeApiTest {

    @Test
    public void parseReadsSuccessAndDuplicate() {
        ChallengeApi.Result ok = ChallengeApi.INSTANCE.parse("{\"ok\":true,\"id\":\"x\",\"duplicate\":false}");
        assertTrue(ok.getOk());
        assertFalse(ok.getDuplicate());

        ChallengeApi.Result dup = ChallengeApi.INSTANCE.parse("{\"ok\":true,\"id\":\"x\",\"duplicate\":true}");
        assertTrue(dup.getOk());
        assertTrue(dup.getDuplicate());
    }

    @Test
    public void parseReadsVerifyDeviceName() {
        ChallengeApi.Result r = ChallengeApi.INSTANCE.parse("{\"ok\":true,\"deviceId\":\"001\",\"deviceName\":\"端末1\"}");
        assertTrue(r.getOk());
        assertEquals("端末1", r.getDeviceName());
    }

    @Test
    public void parseReadsErrorCodes() {
        for (String code : new String[] { "AUTH", "DEVICE_NOT_ACTIVE", "VALIDATION", "CONFLICT", "SERVER" }) {
            ChallengeApi.Result r = ChallengeApi.INSTANCE.parse(
                    "{\"ok\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"だめ\"}}");
            assertFalse(r.getOk());
            assertEquals(code, r.getCode());
            assertEquals("だめ", r.getMessage());
        }
    }

    @Test
    public void parseTreatsNonJsonAsServerError() {
        // Apps Script はログイン画面のHTMLを返すことがある。JSONでなければサーバー側の問題として扱う。
        ChallengeApi.Result r = ChallengeApi.INSTANCE.parse("<!DOCTYPE html><html>...");
        assertFalse(r.getOk());
        assertEquals("SERVER", r.getCode());
    }

    @Test
    public void parse_readsAccountRemaining() {
        ChallengeApi.Result r = ChallengeApi.INSTANCE.parse("{\"ok\":true,\"account\":{\"id\":\"a1\",\"lineLeft\":1,\"googleLeft\":0}}");
        assertEquals("a1", r.getAccountId());
        assertEquals(Integer.valueOf(1), r.getLineLeft());
        assertEquals(Integer.valueOf(0), r.getGoogleLeft());
    }

    @Test
    public void parse_usedUpKeepsAccount() {
        ChallengeApi.Result r = ChallengeApi.INSTANCE.parse("{\"ok\":false,\"error\":{\"code\":\"ACCOUNT_USED_UP\",\"message\":\"x\"},\"account\":{\"id\":\"a1\",\"lineLeft\":0,\"googleLeft\":1}}");
        assertFalse(r.getOk());
        assertEquals("ACCOUNT_USED_UP", r.getCode());
        assertEquals(Integer.valueOf(0), r.getLineLeft());
    }

    @Test
    public void parse_withoutAccount() {
        ChallengeApi.Result r = ChallengeApi.INSTANCE.parse("{\"ok\":true,\"deviceName\":\"端末1\"}");
        assertNull(r.getLineLeft());
        assertNull(r.getGoogleLeft());
        assertEquals("", r.getAccountId());
    }

    private ChallengeRecord rec(String accountId, boolean overUse) {
        return new ChallengeRecord("11111111-2222-3333-4444-555555555555",
                "2026-09-23T01:00:00.000Z", "001", 264.5, "成功", "LINE", "メモ",
                ChallengeRecord.STATUS_PENDING, "", accountId, overUse);
    }

    @Test
    public void verifyBodyOmitsEmptyAccountId() throws Exception {
        assertFalse(ChallengeApi.INSTANCE.verifyBody("pw", "001", "").has("accountId"));
        assertEquals("user1", ChallengeApi.INSTANCE.verifyBody("pw", "001", "user1").getString("accountId"));
    }

    @Test
    public void recordBodyOmitsEmptyAccountIdAndFalseOverUse() throws Exception {
        org.json.JSONObject plain = ChallengeApi.INSTANCE.recordBody("pw", rec("", false)).getJSONObject("record");
        assertFalse(plain.has("accountId"));
        assertFalse(plain.has("overUse"));
        org.json.JSONObject full = ChallengeApi.INSTANCE.recordBody("pw", rec("user1", true)).getJSONObject("record");
        assertEquals("user1", full.getString("accountId"));
        assertTrue(full.getBoolean("overUse"));
    }
}
