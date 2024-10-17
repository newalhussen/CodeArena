package dev.codearena;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Whole workflow against PostgreSQL (scratch database) and the real sandboxed worker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5434/codearena_test}",
        "spring.datasource.username=${TEST_DB_USER:postgres}",
        "spring.datasource.password=${TEST_DB_PASSWORD:}",
})
class ApiIT {
    static final JsonMapper M = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    static int n;

    /** A browser-like client with its own cookie jar. */
    class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        int status;

        JsonNode call(String method, String path, String body, String origin) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
            if (origin != null) b.header("Origin", origin);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            return r.body().isBlank() ? null : M.readTree(r.body());
        }

        JsonNode call(String method, String path, String body) throws Exception {
            return call(method, path, body, null);
        }

        String register() throws Exception {
            String name = "user" + (++n) + "x";
            call("POST", "/api/auth/register", "{\"username\":\"" + name + "\",\"password\":\"correct-horse-42\"}");
            assertThat(status).isEqualTo(201);
            return name;
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE submission, session, app_user CASCADE");
    }

    static String code(String body) {
        return M.createObjectNode().put("language", "javascript").put("code", body).toString();
    }

    @Test
    void problemsAreSeededAndFilterable() throws Exception {
        Client c = new Client();
        JsonNode all = c.call("GET", "/api/problems", null);
        assertThat(all.size()).isEqualTo(9);
        assertThat(c.call("GET", "/api/problems?difficulty=hard", null)).hasSize(2);
        assertThat(c.call("GET", "/api/problems?topic=dynamic-programming", null).size()).isEqualTo(3);
        assertThat(c.call("GET", "/api/problems?difficulty=easy&topic=strings", null).size()).isEqualTo(2);
        assertThat(c.call("GET", "/api/problems?q=two", null).get(0).path("slug").asString()).isEqualTo("two-sum");
        assertThat(c.call("GET", "/api/meta", null).path("languages").get(0).asString()).isEqualTo("javascript");
    }

    @Test
    void problemDetailShowsOnlySamplesAndStarterCode() throws Exception {
        Client c = new Client();
        JsonNode p = c.call("GET", "/api/problems/two-sum", null);
        assertThat(p.path("samples").size()).isEqualTo(2);
        assertThat(p.path("starters").path("javascript").asString()).contains("function twoSum(nums, target)");
        assertThat(p.toString()).doesNotContain("\"hidden\"");
        c.call("GET", "/api/problems/nope", null);
        assertThat(c.status).isEqualTo(404);
    }

    @Test
    void authenticationAndOriginChecks() throws Exception {
        Client c = new Client();
        c.call("POST", "/api/problems/two-sum/submit", code("function twoSum(){}"));
        assertThat(c.status).isEqualTo(401);
        c.call("GET", "/api/me/progress", null);
        assertThat(c.status).isEqualTo(401);

        c.call("POST", "/api/auth/register", "{\"username\":\"Bad Name\",\"password\":\"correct-horse-42\"}");
        assertThat(c.status).isEqualTo(400);
        c.call("POST", "/api/auth/register", "{\"username\":\"okname\",\"password\":\"short\"}");
        assertThat(c.status).isEqualTo(400);
        c.call("POST", "/api/auth/register", "{\"username\":\"okname\",\"password\":\"correct-horse-42\"}", "https://evil.example");
        assertThat(c.status).isEqualTo(403);

        String name = c.register();
        assertThat(c.call("GET", "/api/auth/me", null).path("user").path("username").asString()).isEqualTo(name);
        Client other = new Client();
        other.call("POST", "/api/auth/register", "{\"username\":\"" + name + "\",\"password\":\"correct-horse-42\"}");
        assertThat(other.status).isEqualTo(409);

        c.call("POST", "/api/auth/logout", "{}");
        c.call("GET", "/api/me/progress", null);
        assertThat(c.status).isEqualTo(401);
        c.call("POST", "/api/auth/login", "{\"username\":\"" + name + "\",\"password\":\"wrong-password\"}");
        assertThat(c.status).isEqualTo(401);
        c.call("POST", "/api/auth/login", "{\"username\":\"" + name + "\",\"password\":\"correct-horse-42\"}");
        assertThat(c.status).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user LIMIT 1", String.class)).startsWith("$2");
    }

    @Test
    void acceptedSolutionIsRecordedAndShownOnProgressAndLeaderboard() throws Exception {
        Client c = new Client();
        String name = c.register();
        JsonNode v = c.call("POST", "/api/problems/fizz-buzz/submit", code("function fizzBuzz(n){const r=[];for(let i=1;i<=n;i++)r.push(i%15===0?'FizzBuzz':i%3===0?'Fizz':i%5===0?'Buzz':String(i));return r;}"));
        assertThat(v.path("status").asString()).as(v.toString()).isEqualTo("ACCEPTED");
        assertThat(v.path("passed").asInt()).isEqualTo(v.path("total").asInt());

        assertThat(c.call("GET", "/api/problems/fizz-buzz", null).path("solved").asBoolean()).isTrue();
        JsonNode progress = c.call("GET", "/api/me/progress", null);
        assertThat(progress.path("solved").asInt()).isEqualTo(1);
        assertThat(progress.path("byDifficulty").path("easy").path("solved").asInt()).isEqualTo(1);
        JsonNode board = c.call("GET", "/api/leaderboard", null);
        assertThat(board.get(0).path("username").asString()).isEqualTo(name);
        assertThat(board.get(0).path("points").asInt()).isEqualTo(10);
        assertThat(c.call("GET", "/api/submissions?problem=fizz-buzz", null)).hasSize(1);
        assertThat(c.call("GET", "/api/submissions/" + v.path("id").asLong(), null).path("code").asString()).contains("fizzBuzz");
    }

    @Test
    void wrongAnswerShowsVisibleTestDetailsButNeverHiddenOnes() throws Exception {
        Client c = new Client();
        c.register();
        JsonNode v = c.call("POST", "/api/problems/fizz-buzz/submit", code("function fizzBuzz(n){return [];}"));
        assertThat(v.path("status").asString()).isEqualTo("WRONG_ANSWER");
        assertThat(v.path("failure").path("hidden").asBoolean()).isFalse();
        assertThat(v.path("failure").path("expected").asString()).isEqualTo("[\"1\"]");

        Client d = new Client();
        d.register();
        // correct on the two visible samples, wrong later (hidden)
        JsonNode h = d.call("POST", "/api/problems/fizz-buzz/submit", code("function fizzBuzz(n){const r=[];for(let i=1;i<=n;i++)r.push(i===16?'x':i%15===0?'FizzBuzz':i%3===0?'Fizz':i%5===0?'Buzz':String(i));return r;}"));
        assertThat(h.path("status").asString()).isEqualTo("WRONG_ANSWER");
        assertThat(h.path("failure").path("hidden").asBoolean()).isTrue();
        assertThat(h.path("failure").has("expected") && !h.path("failure").path("expected").isNull()).isFalse();
        assertThat(d.call("GET", "/api/me/progress", null).path("solved").asInt()).isZero();
    }

    @Test
    void errorsAndLimitsAreReportedNotCrashed() throws Exception {
        Client c = new Client();
        c.register();
        assertThat(c.call("POST", "/api/problems/two-sum/run", code("function twoSum( {")).path("status").asString()).isEqualTo("COMPILE_ERROR");
        Thread.sleep(2100);
        assertThat(c.call("POST", "/api/problems/two-sum/run", code("function twoSum(a,b){ throw new Error('nope'); }")).path("status").asString()).isEqualTo("RUNTIME_ERROR");
        Thread.sleep(2100);
        long t0 = System.currentTimeMillis();
        assertThat(c.call("POST", "/api/problems/two-sum/run", code("function twoSum(a,b){ while(true){} }")).path("status").asString()).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(System.currentTimeMillis() - t0).isLessThan(30_000);
    }

    @Test
    void validatesSubmissionInputAndThrottlesRapidRuns() throws Exception {
        Client c = new Client();
        c.register();
        c.call("POST", "/api/problems/two-sum/submit", "{\"language\":\"cobol\",\"code\":\"x\"}");
        assertThat(c.status).isEqualTo(400);
        c.call("POST", "/api/problems/two-sum/submit", code("x".repeat(25_000)));
        assertThat(c.status).isEqualTo(400);
        c.call("POST", "/api/problems/two-sum/submit", "{\"language\":\"javascript\",\"code\":\"  \"}");
        assertThat(c.status).isEqualTo(400);
        c.call("POST", "/api/problems/two-sum/run", code("function twoSum(){return [0,1];}"));
        c.call("POST", "/api/problems/two-sum/run", code("function twoSum(){return [0,1];}"));
        assertThat(c.status).isEqualTo(429);
    }

    @Test
    void runDoesNotRecordASubmission() throws Exception {
        Client c = new Client();
        c.register();
        JsonNode r = c.call("POST", "/api/problems/fizz-buzz/run", code("function fizzBuzz(n){return [];}"));
        assertThat(r.path("total").asInt()).isEqualTo(2); // only the visible samples
        assertThat(c.call("GET", "/api/submissions", null)).isEmpty();
    }
}
