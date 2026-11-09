package com.gree1d.reappzuku.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Source contract: the kill-mode and kill-interval preferences are read in many
 * places, and a single divergent literal default already caused two real bugs
 * (a whitelist-mode flip after restore, and a 15 ms service loop after schedule
 * deactivation). Every read must go through the shared constants.
 */
public class KillPreferenceDefaultsSourcePolicyTest {

    private static final Pattern KILL_MODE_READ = Pattern.compile(
            "(?:getInt|optInt)\\((?:KEY_BACKUP_PREFIX \\+ )?"
                    + "(?:KEY_KILL_MODE|P_KILL_MODE|\"killMode\"),\\s*([^)]+)\\)");
    private static final Pattern KILL_INTERVAL_READ = Pattern.compile(
            "(?:getInt|optInt)\\((?:KEY_BACKUP_PREFIX \\+ )?"
                    + "(?:KEY_KILL_INTERVAL|P_KILL_INTERVAL|\"killInterval\"),\\s*([^)]+)\\)");

    @Test
    public void everyKillModeReadUsesTheSharedDefault() throws Exception {
        for (SourceFile file : mainSources()) {
            Matcher matcher = KILL_MODE_READ.matcher(file.content);
            while (matcher.find()) {
                String defaultArg = matcher.group(1).trim();
                assertTrue(file.path + " reads KEY_KILL_MODE with literal default '"
                                + defaultArg + "'; use AppConstants.DEFAULT_KILL_MODE",
                        defaultArg.endsWith("DEFAULT_KILL_MODE"));
            }
        }
    }

    @Test
    public void everyKillIntervalReadUsesTheSharedMillisecondDefault() throws Exception {
        for (SourceFile file : mainSources()) {
            Matcher matcher = KILL_INTERVAL_READ.matcher(file.content);
            while (matcher.find()) {
                String defaultArg = matcher.group(1).trim();
                assertTrue(file.path + " reads KEY_KILL_INTERVAL with default '" + defaultArg
                                + "'; use AppConstants.DEFAULT_KILL_INTERVAL_MS",
                        defaultArg.endsWith("DEFAULT_KILL_INTERVAL_MS"));
            }
        }
    }

    @Test
    public void workerSchedulingConvertsMillisecondsThroughKillIntervalPolicy() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/gree1d/reappzuku/service/AutoKillWorker.java");
        assertEquals(1, count(source, "KillIntervalPolicy.workerIntervalMinutes("));
    }

    @Test
    public void presetApplicationClampsTheIntervalItWritesIntoTheLiveKey() throws Exception {
        // A preset slot persisted by an older build can still hold the minute-scale
        // fallback; applying it must not re-poison the live millisecond key.
        String presetManager = readRepositoryFile(
                "app/src/main/java/com/gree1d/reappzuku/manager/PresetManager.java");
        assertEquals(1, count(presetManager, "KillIntervalPolicy.clampServiceIntervalMs("));

        String presetModel = readRepositoryFile(
                "app/src/main/java/com/gree1d/reappzuku/utils/PresetModel.java");
        assertEquals(1, count(presetModel, "KillIntervalPolicy.clampServiceIntervalMs("));
    }

    @Test
    public void serviceLoopClampsThePersistedInterval() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/gree1d/reappzuku/service/ShappkyService.java");
        assertEquals(1, count(source, "KillIntervalPolicy.clampServiceIntervalMs("));
    }

    private static final class SourceFile {
        final String path;
        final String content;

        SourceFile(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }

    private static List<SourceFile> mainSources() throws IOException {
        Path root = locateRepositoryRoot().resolve("app/src/main/java");
        List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path) && path.toString().endsWith(".java")) {
                    sources.add(new SourceFile(
                            root.relativize(path).toString(), Files.readString(path)));
                }
            }
        }
        assertTrue("expected main sources under " + root, !sources.isEmpty());
        return sources;
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Path locateRepositoryRoot() throws IOException {
        for (Path candidate : Arrays.asList(
                Paths.get(""), Paths.get(".."), Paths.get("..", ".."))) {
            if (Files.isDirectory(candidate.resolve("app/src/main/java"))) {
                return candidate;
            }
        }
        throw new IOException("Could not locate repository root from "
                + Paths.get("").toAbsolutePath());
    }

    private static String readRepositoryFile(String relative) throws IOException {
        return Files.readString(locateRepositoryRoot().resolve(relative));
    }
}
