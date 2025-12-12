import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-client chat server with:
 *  - username + password authentication
 *  - registration for new users
 *  - server-side chat history file
 *  - typing indicator
 *  - private messages and file transfer passthrough
 */
public class ChatServer {

    private static final int PORT = 9001;

    // all connected clients
    private static final Set<ClientHandler> clients =
            Collections.synchronizedSet(new HashSet<>());

    // active usernames (to stop duplicate login)
    private static final Set<String> activeUsernames =
            Collections.synchronizedSet(new HashSet<>());

    // simple user database: username -> password (plain for project)
    private static final Map<String, String> userDb = new ConcurrentHashMap<>();
    private static final File USER_DB_FILE = new File("users.db");

    // server chat history
    private static final File HISTORY_FILE = new File("server_chat_history.txt");

    public static void main(String[] args) throws Exception {
        loadUsers();

        System.out.println("ChatServer running on port " + PORT);

        try (ServerSocket listener = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = listener.accept();
                ClientHandler handler = new ClientHandler(socket);
                handler.start();
            }
        }
    }

    // ---------- USER DATABASE ----------

    private static void loadUsers() {
        if (!USER_DB_FILE.exists()) return;
        try {
            List<String> lines = Files.readAllLines(USER_DB_FILE.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String user = line.substring(0, idx);
                String pass = line.substring(idx + 1);
                userDb.put(user, pass);
            }
            System.out.println("Loaded " + userDb.size() + " users.");
        } catch (IOException e) {
            System.err.println("Could not load users.db: " + e.getMessage());
        }
    }

    // save a new user append-only
    private static synchronized void saveUser(String username, String password) throws IOException {
        userDb.put(username, password);
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(USER_DB_FILE, true), StandardCharsets.UTF_8))) {
            pw.println(username + ":" + password);
        }
    }

    // ---------- HISTORY ----------

    private static synchronized void saveHistory(String sender, String message) {
        try {
            if (!HISTORY_FILE.exists()) {
                HISTORY_FILE.createNewFile();
            }
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(HISTORY_FILE, true), StandardCharsets.UTF_8))) {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                pw.println("[" + time + "] " + sender + ": " + message);
            }
        } catch (IOException e) {
            System.err.println("Could not write history: " + e.getMessage());
        }
    }

    // ---------- BROADCAST / USERLIST ----------

    private static void broadcast(String msg) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                client.send(msg);
            }
        }
        // normal messages and server messages go into history
        if (msg.startsWith("SERVER: ")) {
            saveHistory("SERVER", msg.substring("SERVER: ".length()));
        } else if (msg.contains(": ")) {
            int idx = msg.indexOf(": ");
            String sender = msg.substring(0, idx);
            String text = msg.substring(idx + 2);
            saveHistory(sender, text);
        }
    }

    private static void broadcastUserList() {
        StringBuilder sb = new StringBuilder("USERLIST ");
        synchronized (clients) {
            boolean first = true;
            for (ClientHandler c : clients) {
                if (!first) sb.append(",");
                first = false;
                sb.append(c.username);
            }
        }
        broadcast(sb.toString());
    }

    private static void broadcastTyping(String who) {
        broadcast("TYPING " + who);
    }

    private static void broadcastTypingStop(String who) {
        broadcast("TYPINGSTOP " + who);
    }

    // ---------- PRIVATE MESSAGE ----------

    private static void sendPrivate(String from, String to, String message) {
        boolean found = false;
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c.username.equals(to)) {
                    c.send(from + " (private): " + message);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            // Inform sender that user not found
            synchronized (clients) {
                for (ClientHandler c : clients) {
                    if (c.username.equals(from)) {
                        c.send("SERVER: User \"" + to + "\" not online.");
                        break;
                    }
                }
            }
        } else {
            saveHistory(from + "->" + to, message);
        }
    }

    // ======================================================================
    //                         CLIENT HANDLER THREAD
    // ======================================================================

    private static class ClientHandler extends Thread {
        private final Socket socket;
        private String username;
        private BufferedReader in;
        private PrintWriter out;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void send(String msg) {
            if (out != null) {
                out.println(msg);
            }
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                // ------------------ LOGIN HANDSHAKE ------------------
                out.println("SUBMITNAME");
                String name = in.readLine();
                if (name == null || name.trim().isEmpty()) {
                    closeQuietly();
                    return;
                }
                name = name.trim();

                out.println("SUBMITPASS");
                String pass = in.readLine();
                if (pass == null) {
                    closeQuietly();
                    return;
                }
                pass = pass.trim();

                // Authentication & registration
                synchronized (ChatServer.class) {
                    if (activeUsernames.contains(name)) {
                        out.println("AUTHFAILED Username already in use.");
                        closeQuietly();
                        return;
                    }

                    if (userDb.containsKey(name)) {
                        String storedPass = userDb.get(name);
                        if (!storedPass.equals(pass)) {
                            out.println("AUTHFAILED Invalid password.");
                            closeQuietly();
                            return;
                        }
                    } else {
                        // register new user
                        saveUser(name, pass);
                    }

                    // success
                    username = name;
                    clients.add(this);
                    activeUsernames.add(username);
                }

                out.println("NAMEACCEPTED " + username);
                broadcast("SERVER: " + username + " joined the chat.");
                broadcastUserList();

                // ------------------ CHAT LOOP ------------------
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("/typing")) {
                        if (line.equals("/typing start")) {
                            broadcastTyping(username);
                        } else if (line.equals("/typing stop")) {
                            broadcastTypingStop(username);
                        }
                    } else if (line.startsWith("/file ")) {
                        // passthrough to everyone: FILE sender filename base64...
                        String rest = line.substring(6); // after "/file "
                        broadcast("FILE " + username + " " + rest);
                        saveHistory(username, "[sent file] " + rest.split(" ", 2)[0]); // filename only
                    } else if (line.startsWith("/msg ")) {
                        // /msg target message...
                        String[] parts = line.split(" ", 3);
                        if (parts.length < 3) {
                            send("SERVER: Usage: /msg <user> <message>");
                        } else {
                            String target = parts[1];
                            String msg = parts[2];
                            sendPrivate(username, target, msg);
                        }
                    } else {
                        // normal public message
                        broadcast(username + ": " + line);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error with client: " + e.getMessage());
            } finally {
                // cleanup on disconnect
                if (username != null) {
                    System.out.println(username + " disconnected.");
                    clients.remove(this);
                    activeUsernames.remove(username);
                    broadcast("SERVER: " + username + " left the chat.");
                    broadcastUserList();
                }
                closeQuietly();
            }
        }

        private void closeQuietly() {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
        }
    }
}
