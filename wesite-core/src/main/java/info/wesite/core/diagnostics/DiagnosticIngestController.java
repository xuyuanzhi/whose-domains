package info.wesite.core.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import info.wesite.core.config.AccessControl;
import info.wesite.core.utils.RateLimitUtils;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/diagnostics")
@AccessControl(level = AccessControl.Level.NONE)
public class DiagnosticIngestController {
    private final DiagnosticsProperties config;
    private final DiagnosticRecorder recorder;
    private final DiagnosticCatalog catalog;
    private final ObjectMapper json;
    private static final Set<String> FIELDS = Set.of("id","kind","route","requestId","code","script","line","column","status","method");
    private static final Set<String> CODES = Set.of("Error","TypeError","ReferenceError","SyntaxError","RangeError","URIError","EvalError","AggregateError","UNHANDLED_REJECTION","NETWORK_FAILURE","HTTP_5XX");
    public DiagnosticIngestController(DiagnosticsProperties config, DiagnosticRecorder recorder, DiagnosticCatalog catalog, ObjectMapper json) {
        this.config=config; this.recorder=recorder; this.catalog=catalog; this.json=json;
    }
    @GetMapping(value="/bootstrap.js",produces="application/javascript")
    public ResponseEntity<String> bootstrap() throws IOException {
        Map<String,Object> settings = config.isEnabled() ? Map.of("enabled",true,"routes",catalog.routes(),"scripts",catalog.scripts()) : Map.of("enabled",false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("window.__diagnostics="+json.writeValueAsString(settings)+";");
    }
    @PostMapping("/events")
    public ResponseEntity<?> ingest(HttpServletRequest request) throws IOException {
        if (!config.isEnabled()) return ResponseEntity.status(204).build();
        if (!sameOrigin(request)) return ResponseEntity.status(403).build();
        if (request.getContentType()==null || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) return ResponseEntity.status(415).build();
        // Bound before parsing, including chunked bodies without Content-Length.
        if (request.getContentLengthLong()>16384) return ResponseEntity.status(413).build();
        String ip = request.getRemoteAddr(); // Never trust arbitrary X-Forwarded-For from the caller.
        if (!RateLimitUtils.isAllowed("diagnostics:"+ip,60,60000)) return ResponseEntity.status(429).build();
        byte[] body=request.getInputStream().readNBytes(16385);
        if (body.length>16384) return ResponseEntity.status(413).build();
        try {
            JsonNode root=json.readTree(body);
            if (root==null || !root.isObject() || root.size()!=1 || !root.has("events") || !root.get("events").isArray()
                    || root.get("events").size()<1 || root.get("events").size()>10) throw new IllegalArgumentException();
            List<DiagnosticEvent> events=new ArrayList<>();
            for (JsonNode node:root.get("events")) events.add(validate(node));
            // Separate event quota prevents batching from multiplying the limit by ten.
            for (int i=0;i<events.size();i++) if (!RateLimitUtils.isAllowed("diagnostic-events:"+ip,60,60000)) return ResponseEntity.status(429).build();
            int accepted=0;
            for (DiagnosticEvent event:events) if (recorder.submit(event)) accepted++;
            return ResponseEntity.ok(Map.of("accepted",accepted));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error","Invalid diagnostic event"));
        }
    }
    private DiagnosticEvent validate(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key -> { if (!FIELDS.contains(key)) throw new IllegalArgumentException(); });
        String id=text(node,"id",36),kind=text(node,"kind",32),route=text(node,"route",200),code=text(node,"code",40);
        uuid(id);
        if (!Set.of("client_error","request_failure").contains(kind) || !CODES.contains(code)
                || !catalog.routes().contains(route)) throw new IllegalArgumentException();
        String requestId=text(node,"requestId",36);
        String method=text(node,"method",12);
        if (!Set.of("","GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","OTHER").contains(method)) throw new IllegalArgumentException();
        int status=integer(node,"status",599),line=integer(node,"line",1000000),column=integer(node,"column",1000000);
        String script=text(node,"script",200);
        if (!script.isEmpty() && !catalog.script(script) && !(script.equals("inline") && !route.equals("unmatched"))) throw new IllegalArgumentException();
        if (kind.equals("client_error") && script.isEmpty()) throw new IllegalArgumentException();
        if (kind.equals("request_failure") && !(code.equals("HTTP_5XX") && status>=500 || code.equals("NETWORK_FAILURE") && status==0)) throw new IllegalArgumentException();
        if (!requestId.isEmpty()) {
            uuid(requestId);
            if (!kind.equals("request_failure") || status<500) throw new IllegalArgumentException();
        }
        return new DiagnosticEvent(requestId.isEmpty()?"client:"+id:"http:"+requestId,kind,route,method,status,code,
            script.isEmpty()?"":script+":"+line+":"+column,requestId,System.currentTimeMillis(),!requestId.isEmpty());
    }
    private static String text(JsonNode node,String field,int max) {
        JsonNode value=node.get(field);
        if (value==null) return "";
        if (!value.isTextual() || value.textValue().length()>max) throw new IllegalArgumentException();
        return value.textValue();
    }
    private static int integer(JsonNode node,String key,int max) {
        if (!node.has(key)) return 0;
        if (!node.get(key).isIntegralNumber() || !node.get(key).canConvertToInt()) throw new IllegalArgumentException();
        int value=node.get(key).intValue(); if (value<0 || value>max) throw new IllegalArgumentException(); return value;
    }
    private static void uuid(String id) { if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException(); }
    private static boolean sameOrigin(HttpServletRequest request) {
        String fetchSite=request.getHeader("Sec-Fetch-Site");
        if (fetchSite!=null && !Set.of("same-origin","none").contains(fetchSite)) return false;
        String origin=request.getHeader("Origin");
        if (origin==null) return "same-origin".equals(fetchSite);
        try {
            URI expected=URI.create(request.getRequestURL().toString()), actual=URI.create(origin);
            return Objects.equals(expected.getScheme(),actual.getScheme()) && Objects.equals(expected.getHost(),actual.getHost())
                && port(expected)==port(actual) && actual.getRawUserInfo()==null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static int port(URI uri) { return uri.getPort()<0 ? ("https".equals(uri.getScheme())?443:80) : uri.getPort(); }
}
