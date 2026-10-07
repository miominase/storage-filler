package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ExclusiveTest {

    @Test
    public void runsAndReleasesWhenFree() {
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicInteger ran = new AtomicInteger();
        assertTrue(Exclusive.INSTANCE.tryRun(flag, () -> {
            // 実行中は他から入れない
            assertTrue(flag.get());
            ran.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        }));
        assertEquals(1, ran.get());
        assertFalse(flag.get());
    }

    @Test
    public void doesNotRunWhileBusy() {
        // 破棄の確定と再送が重なったら、破棄は走らせない（送信中に破棄すると記録が届いてしまう）
        AtomicBoolean flag = new AtomicBoolean(true);
        AtomicInteger ran = new AtomicInteger();
        assertFalse(Exclusive.INSTANCE.tryRun(flag, () -> {
            ran.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        }));
        assertEquals(0, ran.get());
        assertTrue(flag.get());
    }

    @Test
    public void releasesEvenWhenTheBlockThrows() {
        AtomicBoolean flag = new AtomicBoolean(false);
        try {
            Exclusive.INSTANCE.tryRun(flag, () -> {
                throw new IllegalStateException("boom");
            });
            fail();
        } catch (IllegalStateException expected) {
            // 例外はそのまま通す
        }
        assertFalse(flag.get());
    }
}
