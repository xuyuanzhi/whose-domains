package info.wesite.web.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class ProductionSmokeScriptTest {

    @Test
    void offlineSelfTestExercisesSuccessfulAndFailedSmokeResponses() throws Exception {
        Path scriptPath = Path.of("..", "scripts", "check-production-smoke.ps1").toAbsolutePath();
        String executable = selectPowerShellExecutable(ProductionSmokeScriptTest::canStart);
        assumeTrue(executable != null,
                "Neither pwsh nor powershell.exe is installed; skipping only the process-based smoke self-test");

        Process process = new ProcessBuilder(
                executable,
                "-NoProfile",
                "-File",
                scriptPath.toString(),
                "-SelfTest")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("Production smoke self-test cases passed: 5."), output);
        assertTrue(output.contains("Production smoke offline self-test passed."), output);
    }

    @Test
    void invocationRejectsNonProductionOriginBeforeAnyRequest() throws Exception {
        Path scriptPath = Path.of("..", "scripts", "check-production-smoke.ps1").toAbsolutePath();
        String executable = selectPowerShellExecutable(ProductionSmokeScriptTest::canStart);
        assumeTrue(executable != null,
                "Neither pwsh nor powershell.exe is installed; skipping only the process-based smoke self-test");

        Process process = new ProcessBuilder(
                executable,
                "-NoProfile",
                "-File",
                scriptPath.toString(),
                "-SelfTest",
                "-BaseUrl",
                "https://example.com")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());

        assertNotEquals(0, process.waitFor(), output);
        assertTrue(output.contains("production-only and requires https://whose.domains"), output);
    }

    private static String selectPowerShellExecutable(Predicate<String> canStart) {
        for (String candidate : List.of("pwsh", "powershell.exe")) {
            if (canStart.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canStart(String executable) {
        try {
            Process probe = new ProcessBuilder(executable, "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            probe.waitFor();
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locating PowerShell", e);
        }
    }
}
