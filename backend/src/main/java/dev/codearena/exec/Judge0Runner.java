package dev.codearena.exec;

import dev.codearena.exec.Exec.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter for a self-hosted <a href="https://github.com/judge0/judge0">Judge0 CE</a> service, which sandboxes code in
 * containers with CPU, memory, time, process, file-system and network limits (set in Judge0's own configuration).
 * Supports JavaScript (Node) and Python 3 through a generated harness that calls the user's function once per test
 * case and prints one JSON line.
 *
 * Status: written against Judge0's documented REST API (POST /submissions?wait=true) and covered by tests against a
 * stub server. It has NOT been run against a real Judge0 instance in this repository's CI or development environment
 * (no container runtime was available), so verify it against yours before relying on it.
 */
public class Judge0Runner implements CodeRunner {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final int JAVASCRIPT_ID = 63; // Node.js 12 in Judge0 CE; check your instance's /languages and adjust
    static final int PYTHON_ID = 71; // Python 3.8

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Judge0Runner(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("JUDGE0_URL must be set when EXECUTION_ADAPTER=judge0");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public String name() {
        return "judge0";
    }

    @Override
    public List<String> languages() {
        return List.of("javascript", "python");
    }

    @Override
    public RunResult run(RunRequest r) {
        int langId = switch (r.language()) {
            case "javascript" -> JAVASCRIPT_ID;
            case "python" -> PYTHON_ID;
            default -> -1;
        };
        if (langId < 0) return RunResult.failure(RunStatus.COMPILE_ERROR, "Unsupported language: " + r.language());
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("source_code", Harness.build(r));
            body.put("language_id", langId);
            body.put("cpu_time_limit", 5);
            body.put("memory_limit", 256_000);
            var b = HttpRequest.newBuilder(URI.create(baseUrl + "/submissions?base64_encoded=false&wait=true"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (token != null && !token.isBlank()) b.header("X-Auth-Token", token);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return RunResult.failure(RunStatus.INTERNAL_ERROR, "Judge0 answered HTTP " + res.statusCode());
            return parse(MAPPER.readTree(res.body()), r.cases().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunResult.failure(RunStatus.INTERNAL_ERROR, "Interrupted");
        } catch (Exception e) {
            return RunResult.failure(RunStatus.INTERNAL_ERROR, "Could not reach Judge0: " + e.getClass().getSimpleName());
        }
    }

    /** Maps a Judge0 submission result to {@link RunResult}. Status ids: 3 accepted, 5 time limit, 6 compile error, 7-12 runtime errors. */
    static RunResult parse(JsonNode j, int expectedCases) {
        int status = j.path("status").path("id").asInt(-1);
        String stderr = j.path("stderr").asString("");
        String compile = j.path("compile_output").asString("");
        switch (status) {
            case 3 -> {
                List<CaseOutput> outs = new ArrayList<>();
                String stdout = j.path("stdout").asString("").trim();
                String last = stdout.isEmpty() ? "" : stdout.substring(stdout.lastIndexOf('\n') + 1);
                try {
                    JsonNode arr = MAPPER.readTree(last);
                    for (JsonNode o : arr) {
                        outs.add(o.has("error") ? new CaseOutput(null, "RUNTIME_ERROR", o.path("error").asString(""), 0) : new CaseOutput(o.path("got").asString(), null, "", 0));
                    }
                } catch (Exception e) {
                    return RunResult.failure(RunStatus.INTERNAL_ERROR, "Unexpected program output.");
                }
                if (outs.size() != expectedCases) return RunResult.failure(RunStatus.INTERNAL_ERROR, "Unexpected program output.");
                return new RunResult(RunStatus.OK, "", outs);
            }
            case 5 -> {
                return RunResult.failure(RunStatus.TIME_LIMIT, "Time limit exceeded.");
            }
            case 6 -> {
                return RunResult.failure(RunStatus.COMPILE_ERROR, clip(compile.isBlank() ? stderr : compile));
            }
            case 13, 14 -> {
                return RunResult.failure(RunStatus.INTERNAL_ERROR, "Judge0 internal error.");
            }
            default -> {
                boolean memory = stderr.contains("MemoryError") || stderr.contains("heap out of memory") || stderr.contains("Cannot allocate");
                return RunResult.failure(memory ? RunStatus.MEMORY_LIMIT : RunStatus.RUNTIME_ERROR, clip(stderr));
            }
        }
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
