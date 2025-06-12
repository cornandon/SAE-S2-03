import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

public class HttpServer {
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private WebConfig config;
    private SystemInfo systemInfo;
    private boolean running = false;

    // Types MIME supportés
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("htm", "text/html; charset=utf-8");
        CONTENT_TYPES.put("txt", "text/plain; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css");
        CONTENT_TYPES.put("js", "application/javascript");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("mp3", "audio/mpeg");
        CONTENT_TYPES.put("wav", "audio/wav");
        CONTENT_TYPES.put("mp4", "video/mp4");
        CONTENT_TYPES.put("pdf", "application/pdf");
    }

    // Extensions nécessitant une compression
    private static final Set<String> COMPRESSIBLE_TYPES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "mp3", "wav", "mp4", "pdf"
    ));

    public HttpServer() {
        this.threadPool = Executors.newFixedThreadPool(10);
        this.systemInfo = new SystemInfo();
    }

    public static void main(String[] args) {
        HttpServer server = new HttpServer();

        String configFile = "config/myweb.conf";
        if (args.length > 0) {
            configFile = args[0];
        }

        try {
            server.loadConfiguration(configFile);
            server.start();
        } catch (Exception e) {
            System.err.println("Erreur lors du démarrage du serveur: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadConfiguration(String configFile) throws Exception {
        this.config = new WebConfig(configFile);
        System.out.println("[serveur] Configuration chargée depuis: " + configFile);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.getPort());
        running = true;

        // Créer le fichier PID
        createPidFile();

        System.out.println("[serveur] Serveur démarré sur le port " + config.getPort());
        System.out.println("[serveur] DocumentRoot: " + config.getDocumentRoot());

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(new ClientHandler(clientSocket));
            } catch (IOException e) {
                if (running) {
                    config.logError("Erreur acceptation connexion: " + e.getMessage());
                }
            }
        }
    }

    private void createPidFile() {
        try {
            Path pidDir = Paths.get("/tmp/var/run/myweb");
            Files.createDirectories(pidDir);

            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            Files.write(pidDir.resolve("myweb.pid"), pid.getBytes());
        } catch (IOException e) {
            System.err.println("Impossible de créer le fichier PID: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            threadPool.shutdown();

            // Supprimer le fichier PID
            Files.deleteIfExists(Paths.get("/tmp/var/run/myweb/myweb.pid"));
        } catch (IOException e) {
            System.err.println("Erreur lors de l'arrêt: " + e.getMessage());
        }
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                handleRequest();
            } catch (Exception e) {
                config.logError("Erreur traitement requête: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // Ignorer
                }
            }
        }

        private void handleRequest() throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream outputStream = clientSocket.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Lire les en-têtes
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String[] parts = line.split(": ", 2);
                if (parts.length == 2) {
                    headers.put(parts[0].toLowerCase(), parts[1]);
                }
            }

            String clientIP = clientSocket.getInetAddress().getHostAddress();

            // Vérifier la sécurité
            if (!config.isIPAllowed(clientIP)) {
                sendForbidden(outputStream);
                config.logAccess(clientIP + " - FORBIDDEN - " + requestLine);
                return;
            }

            System.out.println("[serveur] " + clientIP + " - " + requestLine);
            config.logAccess(clientIP + " - " + requestLine);

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String path = parts[1];

            if (method.equals("GET")) {
                handleGet(path, outputStream, headers);
            } else if (method.equals("POST")) {
                handlePost(path, outputStream, headers, in);
            } else {
                sendMethodNotAllowed(outputStream);
            }
        }

        private void handleGet(String path, OutputStream outputStream, Map<String, String> headers) throws IOException {
            // Gérer l'URL de statut
            if (path.equals("/status")) {
                sendStatusPage(outputStream);
                return;
            }

            // Traiter les paramètres GET pour les formulaires
            String queryString = "";
            if (path.contains("?")) {
                String[] pathParts = path.split("\\?", 2);
                path = pathParts[0];
                queryString = pathParts[1];
            }

            // Servir les fichiers statiques
            serveFile(path, outputStream, headers, queryString);
        }

        private void handlePost(String path, OutputStream outputStream, Map<String, String> headers, BufferedReader in) throws IOException {
            // Lire le corps de la requête POST
            String contentLengthStr = headers.get("content-length");
            if (contentLengthStr != null) {
                int contentLength = Integer.parseInt(contentLengthStr);
                char[] buffer = new char[contentLength];
                in.read(buffer, 0, contentLength);
                String postData = new String(buffer);

                // Traiter les données du formulaire
                handleFormSubmission(path, postData, outputStream);
            } else {
                sendBadRequest(outputStream);
            }
        }

        private void serveFile(String path, OutputStream outputStream, Map<String, String> headers, String queryString) throws IOException {
            if (path.equals("/")) {
                path = "/index.html";
            }

            Path filePath = Paths.get(config.getDocumentRoot(), path.substring(1));

            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                String extension = getFileExtension(filePath.toString());
                String contentType = CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");

                byte[] fileContent = Files.readAllBytes(filePath);
                boolean shouldCompress = COMPRESSIBLE_TYPES.contains(extension) &&
                        acceptsGzip(headers);

                sendFileResponse(outputStream, fileContent, contentType, shouldCompress);

            } else if (Files.exists(filePath) && Files.isDirectory(filePath)) {
                if (config.allowDirectoryListing()) {
                    sendDirectoryListing(filePath, outputStream);
                } else {
                    sendForbidden(outputStream);
                }
            } else {
                // Si c'est une requête de formulaire, essayer d'exécuter le programme
                if (!queryString.isEmpty()) {
                    handleFormSubmission(path, queryString, outputStream);
                } else {
                    sendNotFound(outputStream);
                }
            }
        }

        private void handleFormSubmission(String path, String data, OutputStream outputStream) throws IOException {
            try {
                // Exécuter le programme correspondant
                Path programPath = Paths.get("/tmp/usr/local/lib/myweb", path.substring(1));

                if (Files.exists(programPath) && Files.isExecutable(programPath)) {
                    ProcessBuilder pb = new ProcessBuilder();

                    // Préparer les variables d'environnement avec les données du formulaire
                    Map<String, String> env = pb.environment();
                    String[] pairs = data.split("&");
                    for (String pair : pairs) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2) {
                            env.put(keyValue[0], URLDecoder.decode(keyValue[1], "UTF-8"));
                        }
                    }

                    pb.command(programPath.toString());
                    Process process = pb.start();

                    // Lire la sortie du programme
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                    }

                    process.waitFor();

                    // Envoyer la réponse
                    PrintWriter out = new PrintWriter(outputStream);
                    out.print("HTTP/1.1 200 OK\r\n");
                    out.print("Content-Type: text/html; charset=utf-8\r\n");
                    out.print("Content-Length: " + output.length() + "\r\n");
                    out.print("\r\n");
                    out.print(output.toString());
                    out.flush();

                } else {
                    sendNotFound(outputStream);
                }
            } catch (Exception e) {
                config.logError("Erreur exécution formulaire: " + e.getMessage());
                sendInternalServerError(outputStream);
            }
        }

        private boolean acceptsGzip(Map<String, String> headers) {
            String acceptEncoding = headers.get("accept-encoding");
            return acceptEncoding != null && acceptEncoding.contains("gzip");
        }

        private void sendFileResponse(OutputStream outputStream, byte[] content, String contentType, boolean compress) throws IOException {
            PrintWriter out = new PrintWriter(outputStream);

            byte[] responseContent = content;
            if (compress) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
                    gzipOut.write(content);
                }
                responseContent = baos.toByteArray();

                out.print("HTTP/1.1 200 OK\r\n");
                out.print("Content-Type: " + contentType + "\r\n");
                out.print("Content-Encoding: gzip\r\n");
                out.print("Content-Length: " + responseContent.length + "\r\n");
                out.print("\r\n");
            } else {
                out.print("HTTP/1.1 200 OK\r\n");
                out.print("Content-Type: " + contentType + "\r\n");
                out.print("Content-Length: " + responseContent.length + "\r\n");
                out.print("\r\n");
            }

            out.flush();
            outputStream.write(responseContent);
            outputStream.flush();
        }

        private void sendDirectoryListing(Path dirPath, OutputStream outputStream) throws IOException {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><title>Index of ").append(dirPath).append("</title></head><body>");
            html.append("<h1>Index of ").append(dirPath).append("</h1><hr><pre>");

            try {
                Files.list(dirPath).sorted().forEach(path -> {
                    String name = path.getFileName().toString();
                    if (Files.isDirectory(path)) {
                        name += "/";
                    }
                    html.append("<a href=\"").append(name).append("\">").append(name).append("</a>\n");
                });
            } catch (IOException e) {
                html.append("Erreur lors de la lecture du répertoire");
            }

            html.append("</pre><hr></body></html>");

            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 200 OK\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html.toString());
            out.flush();
        }

        private void sendStatusPage(OutputStream outputStream) throws IOException {
            String statusHtml = systemInfo.getStatusHtml();

            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 200 OK\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + statusHtml.length() + "\r\n");
            out.print("\r\n");
            out.print(statusHtml);
            out.flush();
        }

        private void sendNotFound(OutputStream outputStream) throws IOException {
            String html = "<html><body><h1>404 Not Found</h1><p>La ressource demandée n'a pas été trouvée.</p></body></html>";
            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 404 Not Found\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html);
            out.flush();
        }

        private void sendForbidden(OutputStream outputStream) throws IOException {
            String html = "<html><body><h1>403 Forbidden</h1><p>Accès interdit.</p></body></html>";
            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 403 Forbidden\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html);
            out.flush();
        }

        private void sendMethodNotAllowed(OutputStream outputStream) throws IOException {
            String html = "<html><body><h1>405 Method Not Allowed</h1></body></html>";
            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 405 Method Not Allowed\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html);
            out.flush();
        }

        private void sendBadRequest(OutputStream outputStream) throws IOException {
            String html = "<html><body><h1>400 Bad Request</h1></body></html>";
            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 400 Bad Request\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html);
            out.flush();
        }

        private void sendInternalServerError(OutputStream outputStream) throws IOException {
            String html = "<html><body><h1>500 Internal Server Error</h1></body></html>";
            PrintWriter out = new PrintWriter(outputStream);
            out.print("HTTP/1.1 500 Internal Server Error\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + html.length() + "\r\n");
            out.print("\r\n");
            out.print(html);
            out.flush();
        }
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) return "";
        return filename.substring(lastDot + 1).toLowerCase();
    }
}