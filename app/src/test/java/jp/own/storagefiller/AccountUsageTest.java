package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountUsageTest {

    private static final AccountUsage U = AccountUsage.INSTANCE;

    private ChallengeRecord rec(String status, String lastError, String accountId, boolean overUse) {
        return new ChallengeRecord("id-1", "2026-10-16T01:00:00.000Z", "37", 1.0, "成功", "LINE", "",
                status, lastError, accountId, overUse);
    }

    @Test
    public void leftLabel_showsBothCounts() {
        assertEquals("アカウント: user0001　LINE 残り 1・Google 残り 0",
                U.leftLabel("user0001", 1, 0));
    }

    @Test
    public void leftLabel_unknownWhenEitherCountMissing() {
        assertEquals("アカウント: user0001　残り: 確認できません", U.leftLabel("user0001", null, 0));
        assertEquals("アカウント: user0001　残り: 確認できません", U.leftLabel("user0001", 1, null));
    }

    @Test
    public void leftLabel_withoutAccountAsksToRebind() {
        assertEquals("アカウント: なし（記録の前に紐づけし直してください）", U.leftLabel("", 2, 1));
    }

    @Test
    public void usedUpMessage_namesAccountAndService() {
        assertEquals("user0001 は LINE を使い切っています", U.usedUpMessage("user0001", "LINE"));
    }

    @Test
    public void switchedTo_changesAccountAndDropsOverUse() {
        ChallengeRecord r = U.switchedTo(rec(ChallengeRecord.STATUS_ATTENTION, "x", "old1", true), "new1");
        assertEquals("new1", r.getAccountId());
        assertFalse(r.getOverUse());
        assertEquals("id-1", r.getId());
    }

    @Test
    public void isUsedUp_onlyForAttentionWithTheUsedUpMessage() {
        String msg = U.usedUpMessage("user0001", "LINE");
        assertTrue(U.isUsedUp(rec(ChallengeRecord.STATUS_ATTENTION, msg, "user0001", false)));
        // 送信待ち・別の理由の要確認・アカウントなしには出さない
        assertFalse(U.isUsedUp(rec(ChallengeRecord.STATUS_PENDING, msg, "user0001", false)));
        assertFalse(U.isUsedUp(rec(ChallengeRecord.STATUS_ATTENTION, "パスワードが違います", "user0001", false)));
        assertFalse(U.isUsedUp(rec(ChallengeRecord.STATUS_ATTENTION, msg, "", false)));
    }
}
