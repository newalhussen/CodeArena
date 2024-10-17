package dev.codearena.service;

import dev.codearena.exec.Exec.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Runs a submission against test cases through the configured {@link CodeRunner} and decides the verdict. */
@Service
public class Judge {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public record TestCase(int ord, JsonNode args, JsonNode expected, boolean hidden) {}

    public record Failure(int index, boolean hidden, String args, String expected, String got, String message) {}

    public record Verdict(String status, int passed, int total, String message, long runtimeMs, Failure failure) {
        public boolean accepted() {
            return "ACCEPTED".equals(status);
        }
    }

    private final CodeRunner runner;
    private final Semaphore slots;

    public Judge(CodeRunner runner, dev.codearena.CodeArenaApplication.Settings settings) {
        this.runner = runner;
        this.slots = new Semaphore(Math.max(1, settings.execution().maxConcurrent()));
    }

    public List<String> languages() {
        return runner.languages();
    }

    public String adapter() {
        return runner.name();
    }

    public Verdict judge(String language, String code, String functionName, List<TestCase> tests) {
        long t0 = System.currentTimeMillis();
        RunResult r;
        try {
            slots.acquire(); // bounded concurrency: executions are expensive
            try {
                r = runner.run(new RunRequest(language, code, functionName, tests.stream().map(t -> new Case(t.args())).toList()));
            } finally {
                slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Verdict("INTERNAL_ERROR", 0, tests.size(), "Interrupted.", 0, null);
        }
        long ms = System.currentTimeMillis() - t0;
        int total = tests.size();
        switch (r.status()) {
            case COMPILE_ERROR -> { return new Verdict("COMPILE_ERROR", 0, total, r.message(), ms, null); }
            case RUNTIME_ERROR -> { return new Verdict("RUNTIME_ERROR", 0, total, r.message(), ms, null); }
            case TIME_LIMIT -> { return new Verdict("TIME_LIMIT_EXCEEDED", 0, total, "Your program took too long.", ms, null); }
            case MEMORY_LIMIT -> { return new Verdict("MEMORY_LIMIT_EXCEEDED", 0, total, "Your program used too much memory.", ms, null); }
            case INTERNAL_ERROR -> { org.slf4j.LoggerFactory.getLogger(Judge.class).warn("runner internal error: {}", r.message()); return new Verdict("INTERNAL_ERROR", 0, total, "The code runner failed. This is not your fault; try again.", ms, null); }
            case OK -> { /* compare below */ }
        }
        List<CaseOutput> outs = r.outputs();
        int passed = 0;
        for (int i = 0; i < tests.size(); i++) {
            TestCase t = tests.get(i);
            if (i >= outs.size()) return new Verdict("INTERNAL_ERROR", passed, total, "The runner stopped early.", ms, null);
            CaseOutput o = outs.get(i);
            if (!o.returned()) {
                String status = "TIME_LIMIT".equals(o.error()) ? "TIME_LIMIT_EXCEEDED" : "INTERNAL_ERROR".equals(o.error()) ? "INTERNAL_ERROR" : "RUNTIME_ERROR";
                return new Verdict(status, passed, total, "TIME_LIMIT".equals(o.error()) ? "Your program took too long." : o.message(), ms, failure(i, t, null, o.message()));
            }
            if (!equal(parse(o.got()), t.expected())) {
                return new Verdict("WRONG_ANSWER", passed, total, "Wrong answer on test " + (i + 1) + ".", ms, failure(i, t, o.got(), null));
            }
            passed++;
        }
        return new Verdict("ACCEPTED", passed, total, "All " + total + " tests passed.", ms, null);
    }

    /** Hidden tests reveal only that they failed; visible ones show input, expected and actual output. */
    private static Failure failure(int i, TestCase t, String got, String message) {
        if (t.hidden()) return new Failure(i + 1, true, null, null, null, null);
        return new Failure(i + 1, false, t.args().toString(), t.expected().toString(), got, message);
    }

    private static JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().stringNode("<unparseable output>");
        }
    }

    /** JSON equality where 1 and 1.0 are the same number and object key order is irrelevant. */
    public static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            List<String> names = new ArrayList<>();
            a.propertyNames().forEach(names::add);
            for (String n : names) if (!b.has(n) || !equal(a.get(n), b.get(n))) return false;
            return true;
        }
        return a.equals(b);
    }
}
