import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * ChatServer with JDBC persistence via DatabaseManager.
 * Assumes DatabaseManager class exists and MySQL is configured.
 */
public class ChatServer {

    private static final int DEFAULT_PORT = 8081;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final DatabaseManager db; // <- persistence

    public ChatServer(int port, DatabaseManager db) throws IOException {
        serverSocket = new ServerSocket(port);
        this.db = db;
        System.out.println("ChatServer running at ws://localhost:" + port + "/");
    }

    public void start() throws IOException {
        while (true) {
            Socket socket = serverSocket.accept();
            socket.setSoTimeout(0);
            ClientHandler handler = new ClientHandler(socket);
            clients.add(handler);

            pool.submit(() -> {
                try { handler.handle(); }
                finally { clients.remove(handler); }
            });
        }
    }

    private void broadcast(String json, ClientHandler exclude) {
        for (ClientHandler c : clients) {
            if (c != exclude && c.isOpen()) {
                try { c.sendText(json); } catch (IOException ignore) {}
            }
        }
    }

    private void sendUsersList(ClientHandler target) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"users\",\"users\":[");
        boolean first = true;
        for (ClientHandler c : clients) {
            if (c.user != null && c.clientId != null) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"user\":").append(escape(c.user))
                  .append(",\"clientId\":").append(escape(c.clientId))
                  .append("}");
            }
        }
        sb.append("]}");
        try { target.sendText(sb.toString()); } catch (IOException ignore) {}
    }

    private String buildUsersJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"users\",\"users\":[");
        boolean first = true;
        for (ClientHandler c : clients) {
            if (c.user != null && c.clientId != null) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"user\":").append(escape(c.user))
                  .append(",\"clientId\":").append(escape(c.clientId))
                  .append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /**
     * Robust extractor for JSON string fields.
     * Matches patterns like: "key"  :  "value"
     * Returns unescaped string or null if not found.
     */
    private static String getJson(String json, String key) {
        if (json == null || key == null) return null;
        try {
            // regex: "key"\s*:\s*"(...escaped content...)"
            String quotedKey = Pattern.quote(key);
            String pattern = "\"\\s*" + quotedKey + "\\s*\"\\s*:\\s*\"((?:\\\\\"|\\\\\\\\|\\\\/|\\\\b|\\\\f|\\\\n|\\\\r|\\\\t|\\\\u[0-9a-fA-F]{4}|[^\"\\\\])*)\"";
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(json);
            if (m.find()) {
                String raw = m.group(1);
                return unescapeJsonString(raw);
            }
        } catch (Exception e) {
            // swallow and return null
        }
        return null;
    }

    /** Unescape JSON string fragments (handles \", \\, \netc.) */
    private static String unescapeJsonString(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length()) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            String hex = s.substring(i, i + 4);
                            try { int code = Integer.parseInt(hex, 16); sb.append((char) code); } catch (NumberFormatException ignore) {}
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private class ClientHandler {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private String user;
        private String clientId;
        private String avatar;
        private boolean open = false;
        // ensure we only handle logout once (prevents duplicate DB rows and duplicate broadcast)
        private volatile boolean logoutHandled = false;

        ClientHandler(Socket socket) { this.socket = socket; }

        boolean isOpen() { return open && !socket.isClosed(); }

        void handle() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                if (!handshake()) return;

                open = true;

                // send current users list
                sendUsersList(this);

                while (open) {
                    String msg = readFrame();
                    if (msg == null) break;

                    // DEBUG: show raw JSON received from client (remove when stable)
                    System.out.println("RAW MSG JSON: " + msg);

                    String type = getJson(msg, "type");
                    if (type == null) continue;

                    switch (type) {
                        case "join": {
                            user = getJson(msg, "user");
                            clientId = getJson(msg, "clientId");
                            avatar = getJson(msg, "avatar");
                            if (user == null) user = "Unknown";

                            // persist login event
                            try { db.recordLogin(user, clientId); } catch (Exception e) { System.err.println("DB login error: " + e.getMessage()); }

                            String joinMsg = "{\"type\":\"join\",\"user\":" + escape(user) + ",\"clientId\":" + escape(clientId) + "}";
                            broadcast(joinMsg, this);

                            // broadcast updated users list
                            broadcast(buildUsersJson(), null);
                            System.out.println("JOIN: " + user);
                            break;
                        }

                        case "leave": {
                            // Mark logout handled so finally cleanup won't run duplicate code
                            logoutHandled = true;

                            String leavingUser = getJson(msg, "user");
                            String leavingCid = getJson(msg, "clientId");
                            if (leavingUser == null) leavingUser = (user != null ? user : "Unknown");

                            // persist logout (only once)
                            try { db.recordLogout(leavingUser, leavingCid); } catch (Exception e) { System.err.println("DB logout error: " + e.getMessage()); }

                            String leaveMsg = "{\"type\":\"leave\",\"user\":" + escape(leavingUser) + ",\"clientId\":" + escape(leavingCid) + "}";
                            broadcast(leaveMsg, null);

                            // Close the socket and exit loop
                            close();
                            break;
                        }


                        case "message": {
                            // Forward the exact JSON to all clients
                            broadcast(msg, null);

                            // Persist the message: extract fields (try several common keys)
                            String mUser = getJson(msg, "user");
                            String mCid = getJson(msg, "clientId");
                            String mText = getJson(msg, "message");
                            if (mText == null) mText = getJson(msg, "text");
                            if (mText == null) mText = getJson(msg, "msg");
                            if (mText == null) mText = getJson(msg, "content");
                            if (mUser == null) mUser = (user != null ? user : "Unknown");
                            if (mText == null) mText = "";

                            try { db.saveMessage(mUser, mCid, mText); } catch (Exception e) { System.err.println("DB saveMessage error: " + e.getMessage()); }

                            // log
                            System.out.println("MSG " + mUser + ": " + mText);
                            break;
                        }

                        default:
                            System.out.println("Unknown type: " + type);
                    }
                }

            } catch (Exception e) {
                System.err.println("Handler exception: " + e.getMessage());
            } finally {
                // If we have not already handled logout (via explicit "leave"), do it now one time.
                if (!logoutHandled && user != null) {
                    logoutHandled = true; // mark it, just in case
                    try { db.recordLogout(user, clientId); } catch (Exception e) { System.err.println("DB logout error (finally): " + e.getMessage()); }
                    String leaveMsg = "{\"type\":\"leave\",\"user\":" + escape(user) + ",\"clientId\":" + escape(clientId) + "}";
                    broadcast(leaveMsg, null);
                }
                close();
            }

        }

        private boolean handshake() {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String line;
                String wsKey = null;
                // read request lines until blank
                while (!(line = br.readLine()).isEmpty()) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                        wsKey = line.split(":")[1].trim();
                    }
                }
                if (wsKey == null) return false;
                String accept = base64Sha1(wsKey + WS_GUID);
                String resp =
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes());
                out.flush();
                return true;
            } catch (Exception e) {
                System.err.println("Handshake failed: " + e.getMessage());
                return false;
            }
        }

        private String base64Sha1(String s) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(digest);
        }

        private String readFrame() {
            try {
                DataInputStream dis = new DataInputStream(in);
                int b1 = dis.readUnsignedByte();
                int b2 = dis.readUnsignedByte();
                boolean masked = (b2 & 0x80) != 0;
                int len = b2 & 0x7F;
                if (len == 126) len = dis.readUnsignedShort();
                else if (len == 127) {
                    long l = dis.readLong();
                    if (l > Integer.MAX_VALUE) return null;
                    len = (int) l;
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    dis.readFully(mask);
                }
                byte[] data = new byte[len];
                dis.readFully(data);
                if (masked) {
                    for (int i = 0; i < len; i++) data[i] ^= mask[i % 4];
                }
                return new String(data, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }

        void sendText(String text) throws IOException {
            byte[] data = text.getBytes("UTF-8");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bo.write(0x81);
            if (data.length <= 125) {
                bo.write(data.length);
            } else if (data.length <= 65535) {
                bo.write(126);
                bo.write((data.length >> 8) & 0xFF);
                bo.write(data.length & 0xFF);
            } else {
                bo.write(127);
                bo.write(0); bo.write(0); bo.write(0); bo.write(0);
                bo.write((data.length >> 24) & 0xFF);
                bo.write((data.length >> 16) & 0xFF);
                bo.write((data.length >> 8) & 0xFF);
                bo.write(data.length & 0xFF);
            }
            bo.write(data);
            out.write(bo.toByteArray());
            out.flush();
        }

        void close() {
            open = false;
            try { socket.close(); } catch (Exception ignore) {}
            clients.remove(this);
        }
    }

    public static void main(String[] args) throws Exception {
        // Update these for your MySQL instance:
        String jdbcUrl = "jdbc:mysql://localhost:3306/chat_db?useSSL=false&serverTimezone=UTC";
        String dbUser = "root";
        String dbPass = "p#r12i#ya";

        DatabaseManager db = new DatabaseManager(jdbcUrl, dbUser, dbPass);
        int port = DEFAULT_PORT;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        ChatServer server = new ChatServer(port, db);
        server.start();
    }
}