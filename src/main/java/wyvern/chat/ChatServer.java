package wyvern.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatServer {
   static final List<Msg> messages = new ArrayList<>();
   static final Map<String, Boolean> logoStatus = new HashMap<>();
   static final int MAX = 1000;
   static final Object lock = new Object();
   static Path staticDir;

   static final class Msg {
      long time;
      String user;
      String text;
      boolean logo;
   }

   public static void main(String[] args) throws IOException {
      int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
      staticDir = Path.of(System.getenv().getOrDefault("STATIC_DIR", "static"));
      HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
      server.createContext("/chat", ChatServer::handleChat);
      server.createContext("/logo-status", ChatServer::handleLogoStatus);
      server.createContext("/static", ChatServer::handleStatic);
      server.setExecutor(null);
      server.start();
      System.out.println("RPC chat server listening on " + port);
   }

   static void handleChat(HttpExchange ex) throws IOException {
      try {
         ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
         ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
         String method = ex.getRequestMethod();
         if ("POST".equals(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);
            String user = params.getOrDefault("user", "").trim();
            String text = params.getOrDefault("msg", "").replace("\n", " ").replace("\r", "");
            boolean logo = "true".equals(params.getOrDefault("logo", "false"));
            if (!user.isEmpty() && !text.isEmpty()) {
               Msg m = new Msg();
               m.time = System.currentTimeMillis();
               m.user = user;
               m.text = text;
               m.logo = logo;
               synchronized (lock) {
                  messages.add(m);
                  logoStatus.put(user, logo);
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
                  if (m.time > since) {
                     sb.append(m.time).append("|").append(m.user).append("|").append(m.text).append("|").append(m.logo ? "1" : "0").append("\n");
                  }
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

   static void handleLogoStatus(HttpExchange ex) throws IOException {
      try {
         ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
         ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
         String method = ex.getRequestMethod();
         if ("GET".equals(method)) {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
               for (Map.Entry<String, Boolean> e : logoStatus.entrySet()) {
                  sb.append(e.getKey()).append("|").append(e.getValue() ? "1" : "0").append("\n");
               }
            }
            respond(ex, 200, "text/plain; charset=utf-8", sb.toString());
         } else if ("POST".equals(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);
            String user = params.getOrDefault("user", "").trim();
            boolean logo = "true".equals(params.getOrDefault("logo", "false"));
            if (!user.isEmpty()) {
               synchronized (lock) {
                  logoStatus.put(user, logo);
               }
            }
            respond(ex, 200, "text/plain", "ok");
         } else if ("OPTIONS".equals(method)) {
            ex.sendResponseHeaders(204, -1);
         } else {
            ex.sendResponseHeaders(405, -1);
         }
      } finally {
         ex.close();
      }
   }

   static void handleStatic(HttpExchange ex) throws IOException {
      try {
         if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
         }
         String path = ex.getRequestURI().getPath().substring("/static".length());
         if (path.isEmpty() || path.equals("/")) path = "/index.html";
         Path file = staticDir.resolve(path).normalize();
         if (!file.startsWith(staticDir) || !Files.exists(file)) {
            respond(ex, 404, "text/plain", "not found");
            return;
         }
         String ct = "application/octet-stream";
         if (path.endsWith(".png")) ct = "image/png";
         else if (path.endsWith(".svg")) ct = "image/svg+xml";
         else if (path.endsWith(".html")) ct = "text/html; charset=utf-8";
         else if (path.endsWith(".css")) ct = "text/css; charset=utf-8";
         else if (path.endsWith(".js")) ct = "text/javascript; charset=utf-8";
         byte[] data = Files.readAllBytes(file);
         ex.getResponseHeaders().add("Content-Type", ct);
         ex.sendResponseHeaders(200, data.length);
         try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
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
