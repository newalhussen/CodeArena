package dev.codearena.worker;

import java.io.OutputStream;
import java.io.PrintStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one submission inside a GraalJS context with every capability switched off: no file system, no network, no
 * threads, no native access, no host (Java) objects, no environment variables. It is started by the API as a separate,
 * memory-capped JVM process that the API kills when the wall-clock limit passes.
 *
 * Protocol: JSON request on stdin {code, functionName, cases:[{args:[...]}], statementLimit}, JSON response on stdout.
 */
public final class JsWorker {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_OUTPUT_CHARS = 500_000;

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(System.out, false, "UTF-8");
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // nothing but the protocol reaches the real stdout
        JsonNode req = MAPPER.readTree(System.in);
        ObjectNode res = MAPPER.createObjectNode();
        try {
            run(req, res);
        } catch (Throwable t) {
            res.put("status", "INTERNAL_ERROR");
            res.put("message", String.valueOf(t.getMessage()));
        }
        protocol.println(MAPPER.writeValueAsString(res));
        protocol.flush();
        System.exit(0);
    }

    private static void run(JsonNode req, ObjectNode res) {
        long limit = req.path("statementLimit").asLong(30_000_000L);
        ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(limit, null).build();
        Context ctx = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(c -> false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowExperimentalOptions(false)
                .resourceLimits(limits)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        try {
            try {
                ctx.eval("js", req.path("code").asString());
            } catch (PolyglotException e) {
                res.put("status", e.isSyntaxError() ? "COMPILE_ERROR" : "RUNTIME_ERROR");
                res.put("message", clip(e.getMessage()));
                return;
            }
            Value fn = ctx.getBindings("js").getMember(req.path("functionName").asString());
            if (fn == null || !fn.canExecute()) {
                res.put("status", "COMPILE_ERROR");
                res.put("message", "Define a function named " + req.path("functionName").asString() + ".");
                return;
            }
            Value call = ctx.eval("js", "(f, json) => { const r = f(...JSON.parse(json)); return r === undefined ? 'null' : JSON.stringify(r); }");
            ArrayNode outputs = res.putArray("outputs");
            for (JsonNode c : req.path("cases")) {
                ObjectNode o = outputs.addObject();
                ctx.resetLimits();
                long t0 = System.nanoTime();
                try {
                    String got = call.execute(fn, c.path("args").toString()).asString();
                    o.put("got", got.length() > MAX_OUTPUT_CHARS ? got.substring(0, MAX_OUTPUT_CHARS) : got);
                } catch (PolyglotException e) {
                    o.put("error", classify(e));
                    o.put("message", clip(e.getMessage()));
                    if (e.isResourceExhausted() || e.isCancelled()) {
                        o.put("timedOut", true);
                        o.put("ms", (System.nanoTime() - t0) / 1_000_000);
                        break; // later cases would only burn more time
                    }
                }
                o.put("ms", (System.nanoTime() - t0) / 1_000_000);
            }
            res.put("status", "OK");
        } finally {
            try {
                ctx.close(true); // closing a context that hit its limit can throw; the result is already decided
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String classify(PolyglotException e) {
        if (e.isResourceExhausted() || e.isCancelled()) return "TIME_LIMIT";
        if (e.isGuestException()) return "RUNTIME_ERROR";
        return "INTERNAL_ERROR";
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

}
