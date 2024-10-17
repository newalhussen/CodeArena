package dev.codearena.exec;

import dev.codearena.exec.Exec.Case;
import dev.codearena.exec.Exec.RunRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Wraps a user's function in a small program for stdin/stdout judges: it runs every case and prints one JSON line,
 * [{"got":"<json>"} | {"error":"<message>"}, ...]. The test data is embedded as a JSON string literal built here by
 * the server, never from user input, so it cannot break out of the string.
 */
final class Harness {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Harness() {}

    static String build(RunRequest r) {
        ArrayNode all = MAPPER.createArrayNode();
        for (Case c : r.cases()) all.add(c.args());
        String casesLiteral = MAPPER.writeValueAsString(MAPPER.writeValueAsString(all)); // a JSON string containing JSON
        String fn = r.functionName();
        return switch (r.language()) {
            case "javascript" -> r.code() + "\n;(function(){const __cases=JSON.parse(" + casesLiteral + ");const __out=[];for(const a of __cases){try{const r=" + fn
                    + "(...a);__out.push({got:r===undefined?'null':JSON.stringify(r)});}catch(e){__out.push({error:String(e&&e.message||e).slice(0,300)});}}"
                    + "console.log(JSON.stringify(__out));})();\n";
            case "python" -> "import json, sys\nsys.setrecursionlimit(10000)\n" + r.code() + "\n__cases = json.loads(" + casesLiteral + ")\n__out = []\nfor __a in __cases:\n    try:\n        __r = "
                    + fn + "(*__a)\n        __out.append({'got': json.dumps(__r, separators=(',', ':'))})\n    except Exception as __e:\n        __out.append({'error': str(__e)[:300]})\nprint(json.dumps(__out))\n";
            default -> throw new IllegalArgumentException("Unsupported language: " + r.language());
        };
    }
}
