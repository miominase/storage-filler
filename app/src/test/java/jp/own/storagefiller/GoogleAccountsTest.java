package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class GoogleAccountsTest {

    private static final GoogleAccounts.Companion G = GoogleAccounts.Companion;

    @Test
    public void encodeThenDecodeKeepsNamesAndOrder() {
        List<GoogleAccounts.Entry> list = Arrays.asList(
                new GoogleAccounts.Entry("a@gmail.com"), new GoogleAccounts.Entry("b@gmail.com"));
        List<GoogleAccounts.Entry> back = G.decode(G.encode(list));
        assertEquals(list, back);
    }

    @Test
    public void decodeToleratesEmptyBrokenAndBlank() {
        assertTrue(G.decode(null).isEmpty());
        assertTrue(G.decode("").isEmpty());
        assertTrue(G.decode("{broken").isEmpty());
        assertEquals(1, G.decode("[{\"name\":\"  \"},{\"name\":\" x@gmail.com \"},{}]").size());
        assertEquals("x@gmail.com", G.decode("[{\"name\":\" x@gmail.com \"}]").get(0).getName());
    }

    @Test
    public void labelForEachState() {
        List<GoogleAccounts.Entry> one = Collections.singletonList(new GoogleAccounts.Entry("a@gmail.com"));
        assertEquals("Googleアカウント: 未選択", G.label(Collections.emptyList(), null));
        assertEquals("Googleアカウント: a@gmail.com", G.label(one, null));
        assertEquals("Googleアカウント: a@gmail.com", G.label(one, new HashSet<>(Arrays.asList("a@gmail.com", "b@gmail.com"))));
        assertEquals("Googleアカウント: a@gmail.com（端末から削除されています）", G.label(one, new HashSet<>(Collections.singletonList("b@gmail.com"))));
        assertEquals("Googleアカウント: a@gmail.com（端末から削除されています）", G.label(one, Collections.emptySet()));
    }
}
