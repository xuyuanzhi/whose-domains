package info.wesite.web.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class RetentionRolloutVerifierScriptTest {

    @Test
    void powerShellSelectionPrefersPwsh() {
        List<String> attempts = new ArrayList<>();

        String executable = selectPowerShellExecutable(candidate -> {
            attempts.add(candidate);
            return true;
        });

        assertEquals("pwsh", executable);
        assertEquals(List.of("pwsh"), attempts);
    }

    @Test
    void powerShellSelectionFallsBackToWindowsPowerShell() {
        List<String> attempts = new ArrayList<>();

        String executable = selectPowerShellExecutable(candidate -> {
            attempts.add(candidate);
            return candidate.equals("powershell.exe");
        });

        assertEquals("powershell.exe", executable);
        assertEquals(List.of("pwsh", "powershell.exe"), attempts);
    }

    @Test
    void powerShellSelectionReturnsNullWhenNoHostCanStart() {
        List<String> attempts = new ArrayList<>();

        String executable = selectPowerShellExecutable(candidate -> {
            attempts.add(candidate);
            return false;
        });

        assertNull(executable);
        assertEquals(List.of("pwsh", "powershell.exe"), attempts);
    }

    @Test
    void staticFixtureRunsOfflineAndReportsItsTerminalContract() throws Exception {
        Path repository = repositoryRoot();
        Path script = repository.resolve("scripts/verify-retention-rollout.ps1");
        String executable = selectPowerShellExecutable(RetentionRolloutVerifierScriptTest::canStart);
        assumeTrue(executable != null,
            "Neither pwsh nor powershell.exe is installed; skipping only the retention rollout script test");
        Process process = new ProcessBuilder(List.of(
            executable,
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script.toString(),
            "-Fixture", "Static"))
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "static verifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=Static"), output);
    }

    @Test
    void pathAFixtureProvesRetentionMaturityAndKSuppressionAgainstMySql() throws Exception {
        Path repository = repositoryRoot();
        Path script = repository.resolve("scripts/verify-retention-rollout.ps1");
        String executable = selectPowerShellExecutable(RetentionRolloutVerifierScriptTest::canStart);
        assumeTrue(executable != null,
            "Neither pwsh nor powershell.exe is installed; skipping only the retention rollout script test");
        Process process = new ProcessBuilder(List.of(
            executable,
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script.toString(),
            "-Fixture", "PathA"))
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();

        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "PathA verifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("RETENTION_REPORT_DATA|PASS|PATH=PathA|IMMATURE=EMPTY|CLOSED_DATE=REJECTED|AUDIT=REQUIRED|AUDITED_DAYS=119|GAP=INSUFFICIENT|CLEANUP=INSUFFICIENT|LATE_FACT=OPEN|MATURE=5|LEGACY_NULL=EXCLUDED|OBSERVED_CONTROL=14"), output);
        assertTrue(output.contains("RETENTION_ROLLOUT_VERIFY|PASS|FIXTURE=PathA"), output);
    }

    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("scripts"))
                && Files.isDirectory(candidate.resolve("wesite-web"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("repository root not found");
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
