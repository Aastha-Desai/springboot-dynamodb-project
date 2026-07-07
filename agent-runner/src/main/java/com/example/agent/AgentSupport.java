package com.example.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

final class AgentSupport {

    private AgentSupport() {
    }

    static String utcNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String stableId(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    static CommandResult run(Path cwd, List<String> command, boolean check) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (check && exitCode != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            throw new IllegalStateException(String.join(" ", command) + " failed: " + detail.trim());
        }
        return new CommandResult(exitCode, stdout, stderr);
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
