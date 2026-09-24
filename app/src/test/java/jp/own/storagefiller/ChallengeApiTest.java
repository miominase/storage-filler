package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
