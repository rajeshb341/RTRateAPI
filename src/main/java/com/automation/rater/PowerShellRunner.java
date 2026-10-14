package com.automation.rater;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a PowerShell script and fails with the script's output if it exits with an error. */
@Slf4j
public final class PowerShellRunner {
    private static final long TIMEOUT_MINUTES = 30;

    private PowerShellRunner() {}

    public static void run(Path script, Map<String, String> parameters) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString()));
        parameters.forEach((name, value) -> {
            if (value != null && !value.isEmpty()) {
                command.add("-" + name);
                command.add(value);
            }
        });

        log.info("Running {}", script.getFileName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();

        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
                log.debug("[ps] {}", line);
            }
        }
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(script.getFileName() + " timed out after " + TIMEOUT_MINUTES + " minutes");
        }
        if (process.exitValue() != 0) {
            String tail = String.join(System.lineSeparator(), output.subList(Math.max(0, output.size() - 15), output.size()));
            throw new IOException(script.getFileName() + " failed (exit " + process.exitValue() + "):" + System.lineSeparator() + tail);
        }
        output.stream().filter(l -> !l.isBlank()).reduce((a, b) -> b).ifPresent(l -> log.info("{}", l));
    }
}
