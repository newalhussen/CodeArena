package dev.codearena.web;

import dev.codearena.CodeArenaApplication.Settings;
import dev.codearena.service.Judge;
import dev.codearena.service.Judge.TestCase;
import dev.codearena.service.Judge.Verdict;
import dev.codearena.service.Store;
import dev.codearena.service.Store.ProblemRow;
import dev.codearena.service.Store.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
    static final String COOKIE = "codearena_session";
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final int MAX_CODE = 20_000;
    private static final long COOLDOWN_MS = 2_000;

    public static class ApiException extends RuntimeException {
        final HttpStatus status;
        final Map<String, String> fields;

        public ApiException(HttpStatus status, String message) {
            this(status, message, Map.of());
        }

        public ApiException(HttpStatus status, String message, Map<String, String> fields) {
            super(message);
            this.status = status;
            this.fields = fields;
        }
    }

    private final Store store;
    private final Judge judge;
    private final PasswordEncoder encoder;
    private final Settings settings;
    private final Map<Long, Long> lastFinished = new ConcurrentHashMap<>();
    private final java.util.Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> loginAttempts = new ConcurrentHashMap<>(); // ip -> [count, windowStartSec]

    public Api(Store store, Judge judge, PasswordEncoder encoder, Settings settings) {
        this.store = store;
        this.judge = judge;
        this.encoder = encoder;
        this.settings = settings;
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void sameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) return; // non-browser clients still need the session cookie
        List<String> allowed = List.of(settings.allowedOrigins().split(","));
        boolean ok = allowed.stream().map(String::trim).anyMatch(origin::equals);
        if (!ok) {
            try {
                ok = URI.create(origin).getAuthority().equalsIgnoreCase(req.getHeader("Host"));
            } catch (Exception ignored) {
            }
        }
        if (!ok) throw new ApiException(HttpStatus.FORBIDDEN, "Cross-site request refused.");
    }

    private String token(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private User optionalUser(HttpServletRequest req) {
        return store.userForToken(token(req)).orElse(null);
    }

    private User requireUser(HttpServletRequest req) {
        if (!"GET".equals(req.getMethod())) sameOrigin(req);
        User u = optionalUser(req);
        if (u == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
        return u;
    }

    private void setCookie(HttpServletResponse res, String value, int maxAge) {
        Cookie c = new Cookie(COOKIE, value);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(maxAge);
        c.setSecure(settings.cookieSecure());
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
    }

    private void throttle(HttpServletRequest req) {
        long now = System.currentTimeMillis() / 1000;
        int[] w = loginAttempts.compute(req.getRemoteAddr(), (k, v) -> v == null || now - v[1] > 60 ? new int[] {1, (int) now} : new int[] {v[0] + 1, v[1]});
        if (w[0] > 15) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Wait a minute.");
    }

    private static String starter(String language, ProblemRow p) {
        String args = String.join(", ", p.argNames());
        return "python".equals(language)
                ? "def " + p.functionName() + "(" + args + "):\n    # write your solution here\n    pass\n"
                : "function " + p.functionName() + "(" + args + ") {\n  // write your solution here\n}\n";
    }

    // ------------------------------------------------------------------------------------------ auth

    public record Credentials(String username, String password) {}

    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) {
        sameOrigin(req);
        throttle(req);
        String name = c.username() == null ? "" : c.username().trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "Username must be 3-20 characters: lowercase letters, digits or underscores.", Map.of("username", "3-20 lowercase letters, digits or _"));
        if (c.password() == null || c.password().length() < 10 || c.password().length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be 10 to 100 characters.", Map.of("password", "10 to 100 characters"));
        Long id = store.insertUser(name, encoder.encode(c.password())).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "That username is taken.", Map.of("username", "Already taken")));
        setCookie(res, store.createSession(id, 14), 14 * 86400);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", name));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) {
        sameOrigin(req);
        throttle(req);
        String name = c.username() == null ? "" : c.username().trim().toLowerCase(Locale.ROOT);
        var found = store.credentials(name);
        // always run a hash comparison so timing does not reveal whether the user exists
        String hash = found.map(o -> (String) o[1]).orElse("$2a$11$7EqJtq98hPqEX7fNZaFWoOhi5BUa3MEYPuvjJ0mPC0vJ0e9bvF9Ei");
        boolean ok = c.password() != null && c.password().length() <= 100 && encoder.matches(c.password(), hash);
        if (found.isEmpty() || !ok) throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
        setCookie(res, store.createSession((Long) found.get()[0], 14), 14 * 86400);
        return Map.of("username", name);
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        sameOrigin(req);
        store.deleteSession(token(req));
        setCookie(res, "", 0);
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest req) {
        User u = optionalUser(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", u == null ? null : Map.of("username", u.username()));
        return m;
    }

    // ------------------------------------------------------------------------------------------ problems

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of("topics", store.allTopics(), "languages", judge.languages(), "runner", judge.adapter());
    }

    @GetMapping("/problems")
    public List<Store.ProblemSummary> problems(@RequestParam(required = false) String difficulty, @RequestParam(required = false) String topic,
                                               @RequestParam(required = false) String q, HttpServletRequest req) {
        User u = optionalUser(req);
        String d = difficulty != null && List.of("easy", "medium", "hard").contains(difficulty) ? difficulty : null;
        String t = topic == null || topic.isBlank() ? null : topic.length() > 40 ? null : topic;
        String s = q == null || q.isBlank() ? null : q.trim().substring(0, Math.min(q.trim().length(), 60));
        return store.problems(d, t, s, u == null ? null : u.id());
    }

    @GetMapping("/problems/{slug}")
    public Map<String, Object> problem(@PathVariable String slug, HttpServletRequest req) {
        ProblemRow p = store.problem(slug).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Problem not found."));
        User u = optionalUser(req);
        List<Map<String, Object>> samples = new ArrayList<>();
        for (TestCase t : store.tests(p.id(), true)) samples.add(Map.of("input", t.args().toString(), "output", t.expected().toString()));
        Map<String, Object> starters = new LinkedHashMap<>();
        for (String l : judge.languages()) starters.put(l, starter(l, p));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", p.slug()); m.put("title", p.title()); m.put("difficulty", p.difficulty()); m.put("topics", p.topics());
        m.put("statement", p.statement()); m.put("functionName", p.functionName()); m.put("argNames", p.argNames());
        m.put("samples", samples); m.put("starters", starters); m.put("solved", u != null && store.solved(u.id(), p.id()));
        return m;
    }

    // ------------------------------------------------------------------------------------------ submissions

    public record CodeRequest(String language, String code) {}

    private Verdict execute(User u, ProblemRow p, CodeRequest body, boolean samplesOnly) {
        if (body.code() == null || body.code().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Write some code first.");
        if (body.code().length() > MAX_CODE) throw new ApiException(HttpStatus.BAD_REQUEST, "Your code is longer than " + MAX_CODE + " characters.");
        if (body.language() == null || !judge.languages().contains(body.language())) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose one of: " + String.join(", ", judge.languages()) + ".");
        // one run at a time per user, with a short pause after each, so nobody can monopolise the sandbox
        if (!running.add(u.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Your previous run is still in progress.");
        try {
            Long done = lastFinished.get(u.id());
            if (done != null && System.currentTimeMillis() - done < COOLDOWN_MS) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Wait a couple of seconds between runs.");
            return judge.judge(body.language(), body.code(), p.functionName(), store.tests(p.id(), samplesOnly));
        } finally {
            lastFinished.put(u.id(), System.currentTimeMillis());
            running.remove(u.id());
        }
    }

    private static Map<String, Object> verdictJson(Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", v.status()); m.put("passed", v.passed()); m.put("total", v.total()); m.put("message", v.message()); m.put("runtimeMs", v.runtimeMs());
        m.put("failure", v.failure());
        return m;
    }

    /** Runs the code against the visible sample tests only; nothing is recorded. */
    @PostMapping("/problems/{slug}/run")
    public Map<String, Object> run(@PathVariable String slug, @RequestBody CodeRequest body, HttpServletRequest req) {
        User u = requireUser(req);
        ProblemRow p = store.problem(slug).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Problem not found."));
        return verdictJson(execute(u, p, body, true));
    }

    @PostMapping("/problems/{slug}/submit")
    public Map<String, Object> submit(@PathVariable String slug, @RequestBody CodeRequest body, HttpServletRequest req) {
        User u = requireUser(req);
        ProblemRow p = store.problem(slug).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Problem not found."));
        Verdict v = execute(u, p, body, false);
        long id = store.insertSubmission(u.id(), p.id(), body.language(), body.code(), v);
        Map<String, Object> m = verdictJson(v);
        m.put("id", id);
        return m;
    }

    @GetMapping("/submissions")
    public List<Store.SubmissionRow> submissions(@RequestParam(required = false) String problem, HttpServletRequest req) {
        return store.submissions(requireUser(req).id(), problem, 50);
    }

    @GetMapping("/submissions/{id}")
    public Map<String, Object> submission(@PathVariable long id, HttpServletRequest req) {
        return store.submissionWithCode(requireUser(req).id(), id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Submission not found."));
    }

    @GetMapping("/me/progress")
    public Map<String, Object> progress(HttpServletRequest req) {
        User u = requireUser(req);
        Map<String, Object> byDifficulty = new LinkedHashMap<>();
        int solved = 0, total = 0;
        for (var e : store.progress(u.id()).entrySet()) {
            byDifficulty.put(e.getKey(), Map.of("solved", e.getValue()[0], "total", e.getValue()[1]));
            solved += e.getValue()[0];
            total += e.getValue()[1];
        }
        return Map.of("username", u.username(), "solved", solved, "total", total, "byDifficulty", byDifficulty, "recent", store.submissions(u.id(), null, 8));
    }

    @GetMapping("/leaderboard")
    public List<Store.LeaderRow> leaderboard() {
        return store.leaderboard(50);
    }

    // ------------------------------------------------------------------------------------------ errors

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(ApiException.class)
        ResponseEntity<Map<String, Object>> api(ApiException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            if (!e.fields.isEmpty()) m.put("fields", e.fields);
            return ResponseEntity.status(e.status).body(m);
        }

        @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
        ResponseEntity<Map<String, Object>> bad(Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed request."));
        }
    }
}
