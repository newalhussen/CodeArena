package dev.codearena;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.codearena.exec.Exec.*;
import dev.codearena.exec.Judge0Runner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Judge0 adapter against a stub HTTP server (not a real Judge0 instance; see the class docs of Judge0Runner). */
class Judge0RunnerTest {
    static final JsonMapper M = JsonMapper.builder().build();

    private static HttpServer stub(String response, AtomicReference<String> seen) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/submissions", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + "|token=" + ex.getRequestHeaders().getFirst("X-Auth-Token"));
            byte[] b = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    private static RunRequest request(String lang) {
        return new RunRequest(lang, "function solve(a){return a;}", "solve", List.of(new Case(M.readTree("[1]")), new Case(M.readTree("[\"x\\\"y\"]"))));
    }

    @Test
    void sendsAHarnessAndParsesTheCaseOutputs() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer s = stub("{\"status\":{\"id\":3},\"stdout\":\"debug line\\n[{\\\"got\\\":\\\"1\\\"},{\\\"error\\\":\\\"boom\\\"}]\\n\"}", seen);
        try {
            var runner = new Judge0Runner("http://127.0.0.1:" + s.getAddress().getPort(), "secret");
            RunResult r = runner.run(request("javascript"));
            assertThat(r.status()).isEqualTo(RunStatus.OK);
            assertThat(r.outputs().get(0).got()).isEqualTo("1");
            assertThat(r.outputs().get(1).error()).isEqualTo("RUNTIME_ERROR");
            assertThat(seen.get()).contains("\"language_id\":63").contains("function solve(a)").contains("JSON.parse(").contains("token=secret");
            // the test data is embedded as an escaped JSON string, so a quote in the data cannot end the literal
            assertThat(seen.get()).contains("const __cases=JSON.parse(");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void buildsAPythonHarness() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer s = stub("{\"status\":{\"id\":3},\"stdout\":\"[{\\\"got\\\":\\\"1\\\"},{\\\"got\\\":\\\"2\\\"}]\"}", seen);
        try {
            new Judge0Runner("http://127.0.0.1:" + s.getAddress().getPort(), null).run(request("python"));
            assertThat(seen.get()).contains("\"language_id\":71").contains("json.loads(").contains("solve(*__a)");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void mapsJudge0StatusesAndFailures() throws Exception {
        for (var e : java.util.Map.of(5, RunStatus.TIME_LIMIT, 6, RunStatus.COMPILE_ERROR, 11, RunStatus.RUNTIME_ERROR, 13, RunStatus.INTERNAL_ERROR).entrySet()) {
            HttpServer s = stub("{\"status\":{\"id\":" + e.getKey() + "},\"stderr\":\"err\",\"compile_output\":\"bad\"}", new AtomicReference<>());
            try {
                assertThat(new Judge0Runner("http://127.0.0.1:" + s.getAddress().getPort(), null).run(request("javascript")).status()).isEqualTo(e.getValue());
            } finally {
                s.stop(0);
            }
        }
        // unreachable service is an internal error, never a verdict on the user's code
        assertThat(new Judge0Runner("http://127.0.0.1:1", null).run(request("javascript")).status()).isEqualTo(RunStatus.INTERNAL_ERROR);
        assertThat(new Judge0Runner("http://127.0.0.1:1", null).run(request("ruby")).status()).isEqualTo(RunStatus.COMPILE_ERROR);
    }
}
