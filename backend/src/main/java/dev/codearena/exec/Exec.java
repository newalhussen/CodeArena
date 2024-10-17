package dev.codearena.exec;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Types shared by the code-execution adapters. */
public final class Exec {
    private Exec() {}

    /** One test: [args] is a JSON array of arguments to the solution function. */
    public record Case(JsonNode args) {}

    public record RunRequest(String language, String code, String functionName, List<Case> cases) {}

    /** What happened to one test case. [got] is the JSON the function returned, [error] is set when it did not return. */
    public record CaseOutput(String got, String error, String message, long ms) {
        public boolean returned() {
            return error == null && got != null;
        }
    }

    public enum RunStatus { OK, COMPILE_ERROR, RUNTIME_ERROR, TIME_LIMIT, MEMORY_LIMIT, INTERNAL_ERROR }

    /** Raw result of running a submission. The judge, not the runner, decides right and wrong. */
    public record RunResult(RunStatus status, String message, List<CaseOutput> outputs) {
        public static RunResult failure(RunStatus s, String message) {
            return new RunResult(s, message, List.of());
        }
    }

    /**
     * The seam where code actually runs. Implementations MUST execute submissions in isolation (separate process or
     * remote service) with CPU, memory, time, file-system and network limits. Never evaluate submitted code inside the
     * API server's own JVM.
     */
    public interface CodeRunner {
        String name();

        /** Languages this runner can execute, e.g. ["javascript"]. */
        List<String> languages();

        RunResult run(RunRequest request);
    }
}
