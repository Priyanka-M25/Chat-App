import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;

public class ChatHistoryServlet extends HttpServlet {
    private DatabaseManager db;

    @Override
    public void init() throws ServletException {
        try {
            db = new DatabaseManager(
                "jdbc:mysql://localhost:3306/chat_db?useSSL=false&serverTimezone=UTC",
                "root",
                "p#r12i#ya"
            );
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        List<String> msgs = db.getRecentMessages(10);
        System.out.println("DEBUG: Messages fetched = " + msgs.size());
        for (String m : msgs) {
            System.out.println("DEBUG: " + m);
        }
        req.setAttribute("messages", msgs);
        req.getRequestDispatcher("/chatHistory.jsp").forward(req, resp);
    }
}