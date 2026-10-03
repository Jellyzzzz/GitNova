import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.*;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated infrastructure probe. No GitNova product Task, model or user repo.
 * SDK API source checked at release-1.1.0; live SDK/container run remains required.
 */
public class OpenSandboxProbe {
    static final String MARKER = "gitnova-dual-host-probe";
    static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + key);
        return value;
    }
    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
    static HttpRequest request(ConnectionConfig config, SandboxEndpoint endpoint, String suffix) {
        String base = endpoint.getEndpoint();
        if (!base.contains("://")) base = config.getProtocol() + "://" + base;
        base = base.replaceAll("/+$", "");
        var builder = HttpRequest.newBuilder(URI.create(base + suffix)).timeout(Duration.ofSeconds(10));
        endpoint.getHeaders().forEach(builder::header);
        // Needed for the selected server-proxy access path; never log header values.
        builder.setHeader("OPEN-SANDBOX-API-KEY", env("OPEN_SANDBOX_API_KEY"));
        return builder.GET().build();
    }
    static void streamProbe(HttpClient client, HttpRequest request) throws Exception {
        long start = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        check(response.statusCode() == 200, "SSE status=" + response.statusCode());
        InputStream body = response.body();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        var closeLater = timer.schedule(() -> {
            try { body.close(); } catch (IOException ignored) { }
        }, 10, TimeUnit.SECONDS);
        List<Long> times = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line; StringBuilder frame = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (frame.indexOf("data:") >= 0) {
                        frames.add(frame.toString());
                        times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                    frame.setLength(0);
                } else frame.append(line).append('\n');
            }
        } finally {
            closeLater.cancel(false); timer.shutdownNow(); body.close();
        }
        check(frames.size() == 3, "Expected three full SSE frames, got " + frames.size());
        for (int i = 0; i < 3; i++) {
            check(frames.get(i).contains("id: " + i) && frames.get(i).contains("中文"), "Bad SSE frame " + i);
        }
        long maxFirst = Long.parseLong(envOr("GITNOVA_PROBE_FIRST_EVENT_MS", "2000"));
        check(times.get(0) < maxFirst, "First event too late: " + times + "; check proxy buffering/network");
        check(times.get(2) - times.get(0) >= 1000, "Frames arrived in one buffered burst: " + times);
        System.out.println("SSE_FRAME_ARRIVAL_MS=" + times);
    }
    public static void main(String[] args) throws Exception {
        String protocol = envOr("OPEN_SANDBOX_PROTOCOL", "http");
        check(protocol.equals("http") || protocol.equals("https"), "Only http/https supported");
        String arch = envOr("OPEN_SANDBOX_ARCH", "amd64");
        check(arch.equals("amd64") || arch.equals("arm64"), "Unsupported arch: " + arch);
        String callback = envOr("GITNOVA_PROBE_CALLBACK_URL", "");
        if (!callback.isEmpty()) {
            URI u = URI.create(callback);
            check(("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null
                && u.getUserInfo() == null, "Callback must be a trusted HTTP URL, without credentials");
        }
        ConnectionConfig config = ConnectionConfig.builder()
            .domain(env("OPEN_SANDBOX_DOMAIN"))
            .apiKey(env("OPEN_SANDBOX_API_KEY")).protocol(protocol).useServerProxy(true)
            .requestTimeout(Duration.ofSeconds(60)).build();
        String operation = UUID.randomUUID().toString();
        String volume = "gitnova-probe-" + operation;
        String program = """
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os, platform, time, urllib.request
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path.startswith('/events'):
   self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Connection','close'); self.end_headers()
   for i in range(3):
    self.wfile.write(('id: %d\\ndata: {"text":"中文","n":%d}\\n\\n'%(i,i)).encode()); self.wfile.flush(); time.sleep(1)
   self.close_connection=True
  elif self.path == '/callback':
   try:
    # Explicit tester-provided destination only. This is not a general fetch service.
    url=os.environ['GITNOVA_PROBE_CALLBACK_URL']
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=5) as result:
     ok=result.status == 200 and result.read(128).decode() == 'gitnova-dual-host-probe'
    self.send_response(200 if ok else 502); self.end_headers(); self.wfile.write(b'callback-ok' if ok else b'callback-bad-marker')
   except Exception:
    self.send_response(502); self.end_headers(); self.wfile.write(b'callback-failed')
  elif self.path == '/platform':
   self.send_response(200); self.end_headers(); self.wfile.write((platform.system()+'/'+platform.machine()).encode())
  else:
   self.send_response(200); self.end_headers(); self.wfile.write(b'probe-ready')
 def log_message(self,*args): pass
Path('/session/probe-file.txt').write_text('persistent-sample')
ThreadingHTTPServer(('0.0.0.0',8081),Handler).serve_forever()
""";
        try (Sandbox sandbox = Sandbox.builder().connectionConfig(config)
            .image(envOr("OPEN_SANDBOX_PROBE_IMAGE", "python:3.12-slim"))
            .platform(PlatformSpec.builder().os("linux").arch(arch).build())
            .entrypoint(List.of("/bin/sh", "-c", "while [ ! -f /tmp/gitnova-probe.py ]; do sleep 1; done; exec python /tmp/gitnova-probe.py"))
            .volume(Volume.builder().name("session").pvc(PVC.builder().claimName(volume)
                .createIfNotExists(true).deleteOnSandboxTermination(false).build()).mountPath("/session").build())
            .env("GITNOVA_PROBE_CALLBACK_URL", callback)
            .metadata("gitnova-probe-operation", operation).timeout(Duration.ofMinutes(10)).build()) {
            System.out.println("sandboxId=" + sandbox.getId() + " volume=" + volume);
            try {
                sandbox.files().write(List.of(WriteEntry.builder().path("/tmp/gitnova-probe.py").data(program).build()));
                SandboxEndpoint endpoint = sandbox.getEndpoint(8081);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                boolean ready = false;
                for (int i = 0; i < 30 && !ready; i++) {
                    try { ready = client.send(request(config, endpoint, "/health/live"), HttpResponse.BodyHandlers.ofString()).statusCode() == 200; }
                    catch (IOException ignored) { }
                    if (!ready) Thread.sleep(500);
                }
                check(ready, "Application not ready; inspect lifecycle -> Docker routing and application startup");
                var platform = client.send(request(config, endpoint, "/platform"), HttpResponse.BodyHandlers.ofString());
                check(platform.statusCode() == 200, "Platform endpoint failed");
                String expected = arch.equals("amd64") ? "Linux/x86_64" : "Linux/aarch64";
                check(expected.equals(platform.body()), "Wrong execution platform: " + platform.body());
                System.out.println("SANDBOX_PLATFORM=" + platform.body());
                streamProbe(client, request(config, endpoint, "/events?after=-1"));
                if (!callback.isEmpty()) {
                    var result = client.send(request(config, endpoint, "/callback"), HttpResponse.BodyHandlers.ofString());
                    check(result.statusCode() == 200 && result.body().equals("callback-ok"), "Sandbox -> client callback failed");
                    System.out.println("CALLBACK_NETWORK=PASS (synthetic marker, NOT model API authentication)");
                } else System.out.println("CALLBACK_NETWORK=NOT_RUN");
                sandbox.renew(Duration.ofMinutes(15));
                System.out.println("PASS: create, mount, platform, app proxy, streamed SSE, renew request");
            } finally { sandbox.kill(); }
        }
        System.out.println("Deletion requested, not confirmed. Verify termination; named test volume retained: " + volume);
    }
}
