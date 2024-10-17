package dev.codearena;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codearena.exec.Exec.*;
import dev.codearena.exec.LocalJsRunner;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Runs real submissions through the real sandboxed worker process. */
class LocalJsRunnerTest {
    static final LocalJsRunner RUNNER = new LocalJsRunner(15_000, 256, 5_000_000L);
    static final JsonMapper M = JsonMapper.builder().build();

    static Case args(String json) {
        return new Case(M.readTree(json));
    }

    static RunResult run(String code, Case... cases) {
        return RUNNER.run(new RunRequest("javascript", code, "solve", List.of(cases)));
    }

    @Test
    void returnsTheFunctionOutputAsJson() {
        RunResult r = run("function solve(a, b) { return { sum: a + b, list: [a, b] }; }", args("[1, 2]"), args("[10, -4]"));
        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.outputs()).extracting(CaseOutput::got).containsExactly("{\"sum\":3,\"list\":[1,2]}", "{\"sum\":6,\"list\":[10,-4]}");
    }

    @Test
    void reportsSyntaxErrorsAndMissingFunctions() {
        assertThat(run("function solve( {", args("[1]")).status()).isEqualTo(RunStatus.COMPILE_ERROR);
        RunResult missing = run("function other() {}", args("[1]"));
        assertThat(missing.status()).isEqualTo(RunStatus.COMPILE_ERROR);
        assertThat(missing.message()).contains("solve");
    }

    @Test
    void reportsRuntimeErrorsPerCase() {
        RunResult r = run("function solve(x) { if (x === 0) throw new Error('boom'); return x; }", args("[1]"), args("[0]"), args("[2]"));
        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.outputs().get(0).returned()).isTrue();
        assertThat(r.outputs().get(1).error()).isEqualTo("RUNTIME_ERROR");
        assertThat(r.outputs().get(1).message()).contains("boom");
        assertThat(r.outputs().get(2).got()).isEqualTo("2");
    }

    @Test
    void stopsInfiniteLoopsWithinTheLimits() {
        long t0 = System.currentTimeMillis();
        RunResult r = run("function solve() { while (true) {} }", args("[]"));
        assertThat(System.currentTimeMillis() - t0).isLessThan(15_000);
        boolean limited = r.status() == RunStatus.TIME_LIMIT || (!r.outputs().isEmpty() && "TIME_LIMIT".equals(r.outputs().get(0).error()));
        assertThat(limited).as("infinite loop must hit a limit, got %s", r).isTrue();
    }

    @Test
    void stopsRunawayRecursion() {
        RunResult r = run("function solve(n) { return solve(n + 1) + 1; }", args("[0]"));
        assertThat(r.status() == RunStatus.OK && r.outputs().get(0).error() != null || r.status() != RunStatus.OK).isTrue();
    }

    @Test
    void cannotTouchTheHostFilesNetworkOrJava() {
        String[] attempts = {
                "function solve() { return require('fs').readdirSync('.'); }",
                "function solve() { return process.env.PATH; }",
                "function solve() { return Java.type('java.lang.System').getProperty('user.home'); }",
                "function solve() { return new java.io.File('.').list(); }",
                "function solve() { return Packages.java.lang.Runtime.getRuntime().exec('cmd'); }",
                "function solve() { return fetch('http://example.com'); }",
                "function solve() { return load('file:///etc/passwd'); }",
                "function solve() { return Polyglot.eval('python', '1'); }",
        };
        for (String code : attempts) {
            RunResult r = run(code, args("[]"));
            boolean leaked = r.status() == RunStatus.OK && r.outputs().get(0).returned() && !r.outputs().get(0).got().equals("null") && !r.outputs().get(0).got().isBlank();
            assertThat(leaked).as("sandbox escape?\n%s\n-> %s", code, r).isFalse();
        }
    }

    @Test
    void theChildProcessSeesNoServerEnvironment() {
        // the worker has no Java access, so this checks the JS side sees nothing either way
        RunResult r = run("function solve() { return typeof process === 'undefined' && typeof require === 'undefined'; }", args("[]"));
        assertThat(r.outputs().get(0).got()).isEqualTo("true");
    }

    @Test
    void consoleOutputCannotCorruptTheResult() {
        RunResult r = run("function solve() { console.log('{\"status\":\"HACK\"}'); return 7; }", args("[]"));
        assertThat(r.status()).isEqualTo(RunStatus.OK);
        assertThat(r.outputs().get(0).got()).isEqualTo("7");
    }

    @Test
    void refusesOtherLanguages() {
        assertThat(RUNNER.run(new RunRequest("python", "x", "solve", List.of())).status()).isEqualTo(RunStatus.COMPILE_ERROR);
    }
}
