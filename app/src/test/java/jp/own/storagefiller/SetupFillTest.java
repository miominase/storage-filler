package jp.own.storagefiller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.google.mlkit.common.MlKitException;

import org.junit.Test;

public class SetupFillTest {

    private static final SetupFill F = SetupFill.INSTANCE;
    private static final String URL = "https://script.google.com/macros/s/AKfake/exec";

    @Test
    public void fillsBothWhenUrlAndPasswordArePresent() {
        assertEquals(new SetupFill.Plan.Fill(URL, "dummypass"), F.plan(URL, "dummypass"));
    }

    @Test
    public void urlOnlyClearsThePasswordField() {
        // 入力途中の本物のパスワードを残したまま宛先だけ差し替えられないように
        assertEquals(new SetupFill.Plan.Fill(URL, ""), F.plan(URL, null));
        assertEquals(new SetupFill.Plan.Fill(URL, ""), F.plan(URL, ""));
    }

    @Test
    public void passwordOnlyLeavesTheUrlFieldAlone() {
        assertEquals(new SetupFill.Plan.Fill(null, "dummypass"), F.plan(null, "dummypass"));
        assertEquals(new SetupFill.Plan.Fill(null, "dummypass"), F.plan("", "dummypass"));
    }

    @Test
    public void fillNeverPrintsThePassword() {
        String s = F.plan(URL, "dummypass").toString();
        assertFalse(s.contains("dummypass"));
        assertFalse(s.contains("AKfake"));
    }

    @Test
    public void nothingFoundIsEmpty() {
        assertSame(SetupFill.Plan.Empty.INSTANCE, F.plan(null, null));
        assertSame(SetupFill.Plan.Empty.INSTANCE, F.plan("", ""));
    }

    @Test
    public void foreignUrlIsRejectedEvenWithAPassword() {
        assertSame(SetupFill.Plan.InvalidUrl.INSTANCE, F.plan("https://evil.example/macros/s/x/exec", "dummypass"));
        assertSame(SetupFill.Plan.InvalidUrl.INSTANCE,
                F.plan("https://script.google.com@evil.example/macros/s/x/exec", "dummypass"));
        assertSame(SetupFill.Plan.InvalidUrl.INSTANCE, F.plan("http://script.google.com/macros/s/x/exec", null));
    }

    @Test
    public void cancelAndDoubleTapAreIgnored() {
        assertEquals(SetupFill.ScanFailure.CANCELLED,
                F.classifyScanError(MlKitException.CODE_SCANNER_CANCELLED));
        assertEquals(SetupFill.ScanFailure.IN_PROGRESS,
                F.classifyScanError(MlKitException.CODE_SCANNER_TASK_IN_PROGRESS));
    }

    @Test
    public void missingModuleOrUnknownFailureAsksForTheModule() {
        assertEquals(SetupFill.ScanFailure.MODULE_MISSING,
                F.classifyScanError(MlKitException.CODE_SCANNER_UNAVAILABLE));
        assertEquals(SetupFill.ScanFailure.MODULE_MISSING, F.classifyScanError(MlKitException.UNAVAILABLE));
        assertEquals(SetupFill.ScanFailure.MODULE_MISSING, F.classifyScanError(null));
    }

    @Test
    public void otherFailuresGetTheirOwnGuidance() {
        assertEquals(SetupFill.ScanFailure.PLAY_SERVICES_OLD,
                F.classifyScanError(MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD));
        assertEquals(SetupFill.ScanFailure.CAMERA_DENIED,
                F.classifyScanError(MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED));
        assertEquals(SetupFill.ScanFailure.OTHER,
                F.classifyScanError(MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR));
        assertEquals(SetupFill.ScanFailure.OTHER,
                F.classifyScanError(MlKitException.CODE_SCANNER_APP_NAME_UNAVAILABLE));
    }
}
