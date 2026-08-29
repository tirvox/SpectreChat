package wyvern.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatServer {
   static final List<Msg> messages = new ArrayList<>();
   static final int MAX = 1000;
   static final Object lock = new Object();

   static final class Msg {
      long time;
      String user;
      String text;
   }

   public static void main(String[] args) throws IOException {
      int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
      HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
      server.createContext("/chat", ChatServer::handle);
      server.setExecutor(null);
      server.start();
      System.out.println("RPC chat server listening on " + port);
   }

   static void handle(HttpExchange ex) throws IOException {
      try {
         ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
         ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
         String method = ex.getRequestMethod();
         if ("POST".equals(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);
            String user = params.getOrDefault("user", "").trim();
            String text = params.getOrDefault("msg", "").replace("\n", " ").replace("\r", "");
            if (!user.isEmpty() && !text.isEmpty()) {
               Msg m = new Msg();
               m.time = System.currentTimeMillis();
               m.user = user;
               m.text = text;
               synchronized (lock) {
                  messages.add(m);
                  while (messages.size() > MAX) messages.remove(0);
               }
            }
            respond(ex, 200, "text/plain", "ok");
         } else if ("GET".equals(method)) {
            long since = 0;
            String q = ex.getRequestURI().getQuery();
            if (q != null) {
               for (String p : q.split("&")) {
                  int i = p.indexOf('=');
                  if (i > 0 && p.substring(0, i).equals("since")) {
                     try { since = Long.parseLong(p.substring(i + 1)); } catch (NumberFormatException ignored) {}
                  }
               }
            }
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
               for (Msg m : messages) {
                  if (m.time > since) sb.append(m.time).append("|").append(m.user).append("|").append(m.text).append("\n");
               }
            }
            respond(ex, 200, "text/plain; charset=utf-8", sb.toString());
         } else if ("OPTIONS".equals(method)) {
            ex.sendResponseHeaders(204, -1);
         } else {
            ex.sendResponseHeaders(405, -1);
         }
      } finally {
         ex.close();
      }
   }

   static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
      byte[] r = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", type);
      ex.sendResponseHeaders(code, r.length);
      try (OutputStream os = ex.getResponseBody()) {
         os.write(r);
      }
   }

   static Map<String, String> parseForm(String body) {
      Map<String, String> map = new HashMap<>();
      if (body == null) return map;
      for (String p : body.split("&")) {
         int i = p.indexOf('=');
         if (i > 0) {
            try {
               map.put(p.substring(0, i), URLDecoder.decode(p.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {}
         }
      }
      return map;
   }
}
