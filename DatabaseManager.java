import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager - simple JDBC helper for chat server persistence.
 * Uses PreparedStatement for safety.
 */
public class DatabaseManager {
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    static {
        try {
            // Force the MySQL driver to load and register with DriverManager
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("DEBUG: MySQL driver Class.forName OK");
        } catch (ClassNotFoundException e) {
            System.err.println("DEBUG: MySQL driver not found via Class.forName(): " + e.getMessage());
        }
    }

    public DatabaseManager() throws ClassNotFoundException {
        // Load driver ONLY (values will be default)
        Class.forName("com.mysql.cj.jdbc.Driver");

        // Default DB settings (you can change DB name if needed)
        this.jdbcUrl = "jdbc:mysql://localhost:3306/chat_db?useSSL=false&serverTimezone=UTC";
        this.dbUser = "root";
        this.dbPass = "p#r12i#ya";  // <-- Replace this
    }

    public DatabaseManager(String jdbcUrl, String dbUser, String dbPass) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
    }

    private Connection getConn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
    }

    /** Record a login event */
    public void recordLogin(String username, String clientId) {
        final String sql = "INSERT INTO user_logins (username, clientId, event_type) VALUES (?, ?, 'LOGIN')";
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, clientId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("DB recordLogin error: " + e.getMessage());
        }
    }

    /** Record a logout event */
    public void recordLogout(String username, String clientId) {
        final String sql = "INSERT INTO user_logins (username, clientId, event_type) VALUES (?, ?, 'LOGOUT')";
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, clientId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("DB recordLogout error: " + e.getMessage());
        }
    }

    /** Save chat message with server timestamp */
    public void saveMessage(String username, String clientId, String message) {
        final String sql = "INSERT INTO messages (username, clientId, message) VALUES (?, ?, ?)";
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, clientId);
            ps.setString(3, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("DB saveMessage error: " + e.getMessage());
        }
    }

    /**
     * Get recent messages (oldest-first).
     * limit: number of messages (e.g. 50)
     * returns list of strings formatted like: "[ts] username: message"
     */
    public List<String> getRecentMessages(int limit) {
        List<String> out = new ArrayList<>();
        final String sql = "SELECT ts, username, message FROM messages ORDER BY ts DESC LIMIT ?";
        System.out.println("DEBUG: Running query: " + sql);
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("ts");
                    String user = rs.getString("username");
                    String msg = rs.getString("message");
                    System.out.println("DEBUG ROW: " + user + " → " + msg);
                    out.add("[" + ts.toString() + "] " + user + ": " + msg);
                }
            }
        } catch (SQLException e) {
            System.err.println("DB getRecentMessages error: " + e.getMessage());
        }
        // result is newest-first; reverse to oldest-first before returning
        List<String> rev = new ArrayList<>();
        for (int i = out.size() - 1; i >= 0; i--) rev.add(out.get(i));
        System.out.println("DEBUG: Final size = " + rev.size());
        return rev;
    }

    public List<String> fetchRecentMessages(int limit) {
        return getRecentMessages(limit);
    }
}