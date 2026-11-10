package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import kotlin.Pair;

public class SideInsetsTest {

    private static final SideInsets S = SideInsets.INSTANCE;

    @Test
    public void portraitKeepsTheXmlMargin() {
        assertEquals(36, S.menuEndMargin(36, 0, 0, false));
        assertEquals(new Pair<>(0, 0), S.panelPadding(0, 0, 0, 0));
    }

    @Test
    public void landscapeNavBarOnTheRightPushesTheMenuButtonIn() {
        // 横画面＋3ボタンナビで右端に 126px のナビゲーションバー
        assertEquals(36 + 126, S.menuEndMargin(36, 0, 126, false));
        assertEquals(new Pair<>(0, 126), S.panelPadding(0, 0, 0, 126));
    }

    @Test
    public void navBarOrCutoutOnTheLeftDoesNotMoveTheRightAlignedButton() {
        // 逆向きの横画面でナビゲーションバー（または切り欠き）が左に来たとき、右寄せのボタンは動かさない
        assertEquals(36, S.menuEndMargin(36, 126, 0, false));
        assertEquals(new Pair<>(126, 0), S.panelPadding(0, 0, 126, 0));
    }

    @Test
    public void rightToLeftLayoutUsesTheLeftInset() {
        assertEquals(36 + 126, S.menuEndMargin(36, 126, 0, true));
        assertEquals(36, S.menuEndMargin(36, 0, 126, true));
    }

    @Test
    public void panelPaddingKeepsTheOriginalValues() {
        assertEquals(new Pair<>(10 + 80, 20 + 50), S.panelPadding(10, 20, 80, 50));
    }
}
