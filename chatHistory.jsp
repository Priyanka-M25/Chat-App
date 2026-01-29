<%@ page import="java.util.*" %>
<%
    List<String> messages = (List<String>) request.getAttribute("messages");

    // Assign a unique color per username
    Map<String, String> userColors = new HashMap<>();

    // Soft pastel base colors
    String[] colors = {
        "#ffd6e0", "#d6eaff", "#e8ffd6", "#ffecc2",
        "#e6d6ff", "#d6fff2", "#ffe0d6", "#f2ffd6"
    };
    int colorIndex = 0;
%>

<!DOCTYPE html>
<html>
<head>
    <title>Chat History</title>

    <style>
        body {
            font-family: "Segoe UI", Arial, sans-serif;
            background: #f4f6f9;
            margin: 0;
            padding: 0;
        }

        .container {
            width: 70%;
            margin: 40px auto;
            background: #fff;
            padding: 20px 40px;
            border-radius: 16px;
            box-shadow: 0 4px 14px rgba(0,0,0,0.1);
        }

        h2 {
            text-align: center;
            margin-bottom: 25px;
            color: #333;
            font-size: 28px;
            letter-spacing: 0.5px;
        }

        .chat-box {
            max-height: 600px;
            overflow-y: auto;
            padding-right: 10px;
        }

        .msg {
            display: flex;
            align-items: flex-start;
            gap: 15px;

            margin: 14px 0;
            padding: 14px 18px;
            border-radius: 12px;
            transition: transform 0.1s ease-in-out;
        }

        .msg:hover {
            transform: scale(1.01);
        }

        .avatar {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            font-size: 20px;
            font-weight: bold;
            color: #333;
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
        }

        .content {
            flex: 1;
        }

        .timestamp {
            color: #555;
            font-size: 13px;
            margin-bottom: 4px;
            display: block;
        }

        .username {
            font-weight: bold;
            font-size: 16px;
            color: #222;
        }

        .text {
            font-size: 15px;
            color: #333;
            margin-top: 2px;
        }

    </style>
</head>

<body>

<div class="container">
    <h2>Chat History</h2>

    <div class="chat-box">
        <% 
        if (messages != null) {

            for (String msg : messages) {  
                // msg format: [2025-11-15 07:33:13] priyanka: hello
                String ts = msg.substring(msg.indexOf("[")+1, msg.indexOf("]"));
                String rest = msg.substring(msg.indexOf("]")+2);

                String user = rest.substring(0, rest.indexOf(":"));
                String messageText = rest.substring(rest.indexOf(":")+2);

                // Initials for avatar
                String initials = user.substring(0,1).toUpperCase();

                // Assign color if user doesn't have one
                if (!userColors.containsKey(user)) {
                    userColors.put(user, colors[colorIndex % colors.length]);
                    colorIndex++;
                }

                String userColor = userColors.get(user);

                // Create soft translucent bubble color
                String bubbleColor = userColor + "33";  // adds 20% transparency
        %>

        <div class="msg" style="background:<%= bubbleColor %>;">
            <div class="avatar" style="background:<%= userColor %>;">
                <%= initials %>
            </div>

            <div class="content">
                <span class="timestamp"><%= ts %></span>
                <span class="username"><%= user %></span>
                <div class="text"><%= messageText %></div>
            </div>
        </div>

        <% 
            }
        } else { 
        %>
            <p>No messages found.</p>
        <% } %>
    </div>

</div>

</body>
</html>