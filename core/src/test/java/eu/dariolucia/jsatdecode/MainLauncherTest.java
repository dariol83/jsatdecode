package eu.dariolucia.jsatdecode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainLauncherTest {
    @Test
    void missingOptionalInspectorHasAnActionableDiagnostic() {
        var failure = assertThrows(IllegalStateException.class,
                () -> MainLauncher.main(new String[]{"--inspector", "--help"}));
        assertTrue(failure.getMessage().contains("JSatInspector is not installed"));
        assertTrue(failure.getMessage().contains("jsatdecode-inspector"));
    }
}
