package dev.codearena.exec;

import dev.codearena.exec.Exec.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Local, self-contained runner for JavaScript. Each submission runs in its own short-lived JVM process
 * ({@link dev.codearena.worker.JsWorker}) that hosts a GraalJS context with no I/O, network, threads or host access.
 *
 * Limits: heap (-Xmx), JavaScript statements per test case, wall-clock time for the whole process (the process is
 * killed), output size, an empty working directory and an empty environment. This is a solid guard for a demo or an
 * internal tool, but a Java process is not a hardened container: for untrusted public traffic use {@link Judge0Runner}
 * (or another container/VM based service) behind the same {@link CodeRunner} interface.
 */
public class LocalJsRunner implements CodeRunner {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_STDOUT_BYTES = 4 << 20;

    private final long wallTimeMs;
    private final int heapMb;
    private final long statementLimit;

    public LocalJsRunner(long wallTimeMs, int heapMb, long statementLimit) {
        this.wallTimeMs = wallTimeMs;
        this.heapMb = heapMb;
        this.statementLimit = statementLimit;
    }

    @Override
    public String name() {
        return "local-js";
    }

    @Override
    public List<String> languages() {
        return List.of("javascript");
    }

    @Override
    public RunResult run(RunRequest r) {
        if (!"javascript".equals(r.language())) return RunResult.failure(RunStatus.COMPILE_ERROR, "The local runner supports only JavaScript.");
        Path dir = null;
        Process p = null;
        try {
            dir = Files.createTempDirectory("codearena-run-");
            ObjectNode req = MAPPER.createObjectNode();
            req.put("code", r.code());
            req.put("functionName", r.functionName());
            req.put("statementLimit", statementLimit);
            var cases = req.putArray("cases");
            for (Case c : r.cases()) cases.addObject().set("args", c.args());

            ProcessBuilder pb = new ProcessBuilder(command()).directory(dir.toFile()).redirectErrorStream(false);
            pb.environment().clear(); // the child sees none of the server's secrets
            p = pb.start();
            Process proc = p;
            p.getOutputStream().write(MAPPER.writeValueAsBytes(req));
            p.getOutputStream().close();
            // drain stderr so a chatty process cannot block, but ignore its content
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream e = proc.getErrorStream()) {
                    e.transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (out.size() + n > MAX_STDOUT_BYTES) {
                            proc.destroyForcibly(); // runaway output
                            return;
                        }
                        out.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                }
            });
            if (!p.waitFor(wallTimeMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return RunResult.failure(RunStatus.TIME_LIMIT, "Time limit exceeded.");
            }
            reader.join(1000);
            drain.join(200);
            String body = out.toString(StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                return RunResult.failure(p.exitValue() == 0 ? RunStatus.INTERNAL_ERROR : RunStatus.MEMORY_LIMIT,
                        p.exitValue() == 0 ? "The runner produced no result." : "The program ran out of memory or crashed.");
            }
            return parse(MAPPER.readTree(body));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(LocalJsRunner.class).warn("local runner failed", e);
            return RunResult.failure(RunStatus.INTERNAL_ERROR, "The runner failed: " + e.getClass().getSimpleName());
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            deleteQuietly(dir);
        }
    }

    private List<String> command() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Xmx" + heapMb + "m", "-Xss1m", "-XX:+ExitOnOutOfMemoryError", "-XX:TieredStopAtLevel=1",
                "-Xshare:auto", "--enable-native-access=ALL-UNNAMED", "-Dpolyglot.engine.WarnInterpreterOnly=false"));
        if (!cp.contains(java.io.File.pathSeparator) && cp.endsWith(".jar")) {
            // running from the Spring Boot fat jar: start the worker through the jar's launcher
            cmd.addAll(List.of("-Dloader.main=dev.codearena.worker.JsWorker", "-cp", cp, "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else {
            cmd.addAll(List.of("-cp", cp, "dev.codearena.worker.JsWorker"));
        }
        return cmd;
    }

    private static RunResult parse(JsonNode n) {
        RunStatus status = RunStatus.valueOf(n.path("status").asString("INTERNAL_ERROR"));
        List<CaseOutput> outs = new ArrayList<>();
        for (JsonNode o : n.path("outputs")) {
            outs.add(new CaseOutput(o.has("got") ? o.path("got").asString() : null, o.has("error") ? o.path("error").asString() : null, o.path("message").asString(""), o.path("ms").asLong(0)));
        }
        return new RunResult(status, n.path("message").asString(""), outs);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
