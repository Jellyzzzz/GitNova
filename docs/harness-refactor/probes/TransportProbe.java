import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline loopback transport probe. NOT the GitNova Worker implementation. */
public final class TransportProbe {
    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    private static void response(HttpExchange x, int code, byte[] bytes) throws IOException {
        x.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }
    public static void main(String[] args) throws Exception {
        var executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(executor);
        String prefix = "/route/sandbox/8081";
        Map<String, byte[]> receipts = new HashMap<>();
        AtomicInteger starts = new AtomicInteger();
        byte[][] observed = new byte[1][];
        server.createContext(prefix + "/commands", x -> {
            try {
                if (!"provider-header".equals(x.getRequestHeaders().getFirst("X-Endpoint-Token"))) {
                    response(x,403,"missing route header".getBytes(StandardCharsets.UTF_8)); return;
                }
                String id = x.getRequestHeaders().getFirst("X-Probe-Command-Id");
                byte[] body = x.getRequestBody().readNBytes(1_048_577);
                if (id == null || body.length > 1_048_576) {
                    response(x,400,"invalid".getBytes(StandardCharsets.UTF_8)); return;
                }
                int code;
                synchronized (receipts) {
                    byte[] previous = receipts.get(id);
                    if (previous == null) {
                        receipts.put(id,body); observed[0]=body; starts.incrementAndGet(); code=202;
                    } else code=Arrays.equals(previous,body)?200:409;
                }
                response(x,code,"accepted".getBytes(StandardCharsets.UTF_8));
            } finally { x.close(); }
        });
        String frame="id: 1\r\nevent: agent\r\ndata: {\"text\":\"中文😀\"}\r\n\r\n"
            +": keepalive\n\nid: 2\ndata: {\"status\":\"done\"}\n\n";
        server.createContext(prefix + "/events", x -> {
            x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            x.sendResponseHeaders(200,0);
            try (OutputStream out=x.getResponseBody()) {
                // Deliberately split UTF-8 multibyte characters and event frame boundaries.
                for(byte b:frame.getBytes(StandardCharsets.UTF_8)){out.write(new byte[]{b});out.flush();}
            } finally {x.close();}
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+prefix;
            URI endpoint=URI.create(base+"/commands");
            require(endpoint.getPath().equals(prefix+"/commands"),"prefix was lost");
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String text="{\"message\":\"修复错误\\n保留单引号'和 $(touch /tmp/not-executed)\"}";
            String id=UUID.randomUUID().toString();
            for(int code:new int[]{202,200}){
                var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type","application/json").header("X-Endpoint-Token","provider-header")
                    .header("X-Probe-Command-Id",id).POST(HttpRequest.BodyPublishers.ofString(text)).build();
                require(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==code,"receipt status");
            }
            var conflict=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("X-Endpoint-Token","provider-header").header("X-Probe-Command-Id",id)
                .POST(HttpRequest.BodyPublishers.ofString(text+" ")).build();
            require(client.send(conflict,HttpResponse.BodyHandlers.discarding()).statusCode()==409,"changed identity");
            require(starts.get()==1,"duplicate started twice");
            require(Arrays.equals(observed[0],text.getBytes(StandardCharsets.UTF_8)),"request bytes changed");
            var eventRequest=HttpRequest.newBuilder(URI.create(base+"/events?after=0"))
                .timeout(Duration.ofSeconds(3)).GET().build();
            var eventResponse=client.send(eventRequest,HttpResponse.BodyHandlers.ofInputStream());
            List<String> data=new ArrayList<>();StringBuilder current=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(eventResponse.body(),StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null){
                    if(line.isEmpty()){
                        if(!current.isEmpty()){data.add(current.toString());current.setLength(0);}
                    } else if(line.startsWith("data:")){
                        if(!current.isEmpty())current.append('\n');
                        String value=line.substring(5);if(value.startsWith(" "))value=value.substring(1);
                        current.append(value);
                    }
                }
            }
            require(data.size()==2 && data.get(0).contains("中文😀"),"SSE framing/UTF-8");
            System.out.println("PASS: loopback HTTP, opaque task text, route prefix/header, duplicate/conflict, fragmented UTF-8 SSE");
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
