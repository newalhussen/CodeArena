package dev.codearena;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codearena.exec.Exec.*;
import dev.codearena.exec.LocalJsRunner;
import dev.codearena.service.Judge;
import dev.codearena.service.Judge.TestCase;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every bundled problem must be solvable: its reference solution runs through the real sandbox and the judge and must be
 * ACCEPTED, and naive wrong or slow solutions must not be. Needs no database.
 */
class ReferenceSolutionsIT {
    static final JsonMapper M = JsonMapper.builder().build();

    static JsonNode load(String name) throws Exception {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return M.readTree(in);
        }
    }

    static Judge judge() {
        var settings = new CodeArenaApplication.Settings("http://localhost:3000", false, new CodeArenaApplication.Settings.Execution("local-js", 60_000, 256, 30_000_000L, 1, "", ""), false);
        return new Judge(new LocalJsRunner(60_000, 256, 30_000_000L), settings);
    }

    static List<TestCase> tests(JsonNode problem) {
        List<TestCase> out = new ArrayList<>();
        int i = 0;
        for (JsonNode t : problem.path("tests")) out.add(new TestCase(i++, t.path("args"), t.path("expected"), t.path("hidden").asBoolean()));
        return out;
    }

    @Test
    void everyReferenceSolutionIsAccepted() throws Exception {
        JsonNode problems = load("problems.json");
        JsonNode refs = load("references.json");
        Judge judge = judge();
        for (JsonNode p : problems) {
            String slug = p.path("slug").asString();
            var v = judge.judge("javascript", refs.path(slug).asString(), p.path("functionName").asString(), tests(p));
            assertThat(v.status()).as("%s: %s", slug, v).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void naiveExponentialSolutionIsRejectedByTheLimits() throws Exception {
        JsonNode coin = null;
        for (JsonNode p : load("problems.json")) if (p.path("slug").asString().equals("coin-change")) coin = p;
        String exponential = "function coinChange(coins, amount) { function go(a) { if (a === 0) return 0; if (a < 0) return -1; let best = -1; for (const c of coins) { const r = go(a - c); if (r >= 0 && (best < 0 || r + 1 < best)) best = r + 1; } return best; } return go(amount); }";
        var settings = new CodeArenaApplication.Settings("x", false, new CodeArenaApplication.Settings.Execution("local-js", 60_000, 256, 3_000_000L, 1, "", ""), false);
        var v = new Judge(new LocalJsRunner(60_000, 256, 3_000_000L), settings).judge("javascript", exponential, "coinChange", tests(coin));
        assertThat(v.accepted()).isFalse();
        assertThat(v.status()).isIn("TIME_LIMIT_EXCEEDED", "WRONG_ANSWER");
    }

    @Test
    void jsonComparisonTreatsEquivalentValuesAsEqual() throws Exception {
        assertThat(Judge.equal(M.readTree("1"), M.readTree("1.0"))).isTrue();
        assertThat(Judge.equal(M.readTree("{\"a\":[1,2],\"b\":null}"), M.readTree("{\"b\":null,\"a\":[1.0,2]}"))).isTrue();
        assertThat(Judge.equal(M.readTree("[1,2]"), M.readTree("[2,1]"))).isFalse();
        assertThat(Judge.equal(M.readTree("\"1\""), M.readTree("1"))).isFalse();
        assertThat(Judge.equal(M.readTree("[]"), M.readTree("{}"))).isFalse();
    }
}
