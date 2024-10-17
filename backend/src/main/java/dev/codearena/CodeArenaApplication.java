package dev.codearena;

import dev.codearena.exec.Exec.CodeRunner;
import dev.codearena.exec.Judge0Runner;
import dev.codearena.exec.LocalJsRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeArenaApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeArenaApplication.class, args);
    }

    @ConfigurationProperties(prefix = "codearena")
    public record Settings(String allowedOrigins, boolean cookieSecure, Execution execution, boolean seedProblems) {
        public record Execution(String adapter, long wallTimeMs, int heapMb, long statementLimit, int maxConcurrent, String judge0Url, String judge0Token) {}
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }

    /** The execution adapter is chosen by configuration; swapping it never touches the rest of the application. */
    @Bean
    CodeRunner codeRunner(Settings s) {
        var e = s.execution();
        return switch (e.adapter()) {
            case "judge0" -> new Judge0Runner(e.judge0Url(), e.judge0Token());
            case "local-js" -> new LocalJsRunner(e.wallTimeMs(), e.heapMb(), e.statementLimit());
            default -> throw new IllegalStateException("Unknown execution adapter: " + e.adapter() + " (use local-js or judge0)");
        };
    }
}
