import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class WebConfig {
    private int port = 8080;
    private String documentRoot = System.getProperty("user.dir");
    private boolean allowIndexes = false;
    private String accessLogPath = null;
    private String errorLogPath = null;

    // Sécurité
    private List<NetworkRule> acceptRules = new ArrayList<>();
    private List<NetworkRule> rejectRules = new ArrayList<>();
    private boolean acceptFirst = true; // true = accept first, false = reject first
    private boolean defaultAccept = true;

    private PrintWriter accessLogWriter;
    private PrintWriter errorLogWriter;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");

    public WebConfig(String configFile) throws Exception {
        loadConfiguration(configFile);
        initializeLoggers();
    }

    private void loadConfiguration(String configFile) throws Exception {
        File file = new File(configFile);
        if (!file.exists()) {
            System.out.println("[config] Fichier de configuration non trouvé, utilisation des valeurs par défaut");
            return;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);
        doc.getDocumentElement().normalize();

        // Port
        NodeList portNodes = doc.getElementsByTagName("port");
        if (portNodes.getLength() > 0) {
            this.port = Integer.parseInt(portNodes.item(0).getTextContent().trim());
        }

        // DocumentRoot
        NodeList rootNodes = doc.getElementsByTagName("DocumentRoot");
        if (rootNodes.getLength() > 0) {
            this.documentRoot = rootNodes.item(0).getTextContent().trim();
        }

        // Directory options
        NodeList directoryNodes = doc.getElementsByTagName("Directory");
        for (int i = 0; i < directoryNodes.getLength(); i++) {
            Element dirElement = (Element) directoryNodes.item(i);
            NodeList optionsNodes = dirElement.getElementsByTagName("Options");
            if (optionsNodes.getLength() > 0) {
                String options = optionsNodes.item(0).getTextContent().trim();
                if (options.equalsIgnoreCase("Indexes")) {
                    this.allowIndexes = true;
                }
            }
        }

        // Sécurité
        NodeList securityNodes = doc.getElementsByTagName("security");
        if (securityNodes.getLength() > 0) {
            Element securityElement = (Element) securityNodes.item(0);

            // Order
            NodeList orderNodes = securityElement.getElementsByTagName("order");
            if (orderNodes.getLength() > 0) {
                Element orderElement = (Element) orderNodes.item(0);
                NodeList firstNodes = orderElement.getElementsByTagName("first");
                if (firstNodes.getLength() > 0) {
                    String first = firstNodes.item(0).getTextContent().trim();
                    this.acceptFirst = first.equalsIgnoreCase("accept");
                }
            }

            // Default
            NodeList defaultNodes = securityElement.getElementsByTagName("default");
            if (defaultNodes.getLength() > 0) {
                String defaultAction = defaultNodes.item(0).getTextContent().trim();
                this.defaultAccept = defaultAction.equalsIgnoreCase("accept");
            }

            // Accept rules
            NodeList acceptNodes = securityElement.getElementsByTagName("accept");
            for (int i = 0; i < acceptNodes.getLength(); i++) {
                String rule = acceptNodes.item(i).getTextContent().trim();
                acceptRules.add(new NetworkRule(rule));
            }

            // Reject rules
            NodeList rejectNodes = securityElement.getElementsByTagName("reject");
            for (int i = 0; i < rejectNodes.getLength(); i++) {
                String rule = rejectNodes.item(i).getTextContent().trim();
                rejectRules.add(new NetworkRule(rule));
            }
        }

        // Logs
        NodeList accessLogNodes = doc.getElementsByTagName("accesslog");
        if (accessLogNodes.getLength() > 0) {
            this.accessLogPath = accessLogNodes.item(0).getTextContent().trim();
        }

        NodeList errorLogNodes = doc.getElementsByTagName("errorlog");
        if (errorLogNodes.getLength() > 0) {
            this.errorLogPath = errorLogNodes.item(0).getTextContent().trim();
        }
    }

    private void initializeLoggers() throws IOException {
        if (accessLogPath != null) {
            Path logPath = Paths.get(accessLogPath);
            Files.createDirectories(logPath.getParent());
            accessLogWriter = new PrintWriter(new FileWriter(accessLogPath, true));
        }

        if (errorLogPath != null) {
            Path logPath = Paths.get(errorLogPath);
            Files.createDirectories(logPath.getParent());
            errorLogWriter = new PrintWriter(new FileWriter(errorLogPath, true));
        }
    }

    public boolean isIPAllowed(String clientIP) {
        try {
            InetAddress clientAddr = InetAddress.getByName(clientIP);

            if (acceptFirst) {
                // Vérifier d'abord les règles d'acceptation
                for (NetworkRule rule : acceptRules) {
                    if (rule.matches(clientAddr)) {
                        logAccess(clientIP + " - ACCEPTED by rule: " + rule.getRule());
                        return true;
                    }
                }

                // Puis les règles de rejet
                for (NetworkRule rule : rejectRules) {
                    if (rule.matches(clientAddr)) {
                        logAccess(clientIP + " - REJECTED by rule: " + rule.getRule());
                        return false;
                    }
                }
            } else {
                // Vérifier d'abord les règles de rejet
                for (NetworkRule rule : rejectRules) {
                    if (rule.matches(clientAddr)) {
                        logAccess(clientIP + " - REJECTED by rule: " + rule.getRule());
                        return false;
                    }
                }

                // Puis les règles d'acceptation
                for (NetworkRule rule : acceptRules) {
                    if (rule.matches(clientAddr)) {
                        logAccess(clientIP + " - ACCEPTED by rule: " + rule.getRule());
                        return true;
                    }
                }
            }

            // Aucune règle ne correspond, utiliser la valeur par défaut
            return defaultAccept;

        } catch (UnknownHostException e) {
            logError("IP invalide: " + clientIP);
            return false;
        }
    }

    public void logAccess(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;

        System.out.println(logEntry);

        if (accessLogWriter != null) {
            accessLogWriter.println(logEntry);
            accessLogWriter.flush();
        }
    }

    public void logError(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] ERROR: " + message;

        System.err.println(logEntry);

        if (errorLogWriter != null) {
            errorLogWriter.println(logEntry);
            errorLogWriter.flush();
        }
    }

    public void close() {
        if (accessLogWriter != null) {
            accessLogWriter.close();
        }
        if (errorLogWriter != null) {
            errorLogWriter.close();
        }
    }

    // Getters
    public int getPort() { return port; }
    public String getDocumentRoot() { return documentRoot; }
    public boolean allowDirectoryListing() { return allowIndexes; }

    // Classe interne pour gérer les règles réseau
    private static class NetworkRule {
        private String rule;
        private InetAddress network;
        private int prefixLength;

        public NetworkRule(String rule) throws UnknownHostException {
            this.rule = rule;
            parseRule(rule);
        }

        private void parseRule(String rule) throws UnknownHostException {
            if (rule.contains("/")) {
                String[] parts = rule.split("/");
                this.network = InetAddress.getByName(parts[0]);
                this.prefixLength = Integer.parseInt(parts[1]);
            } else {
                this.network = InetAddress.getByName(rule);
                this.prefixLength = 32; // IP unique
            }
        }

        public boolean matches(InetAddress clientIP) {
            try {
                byte[] networkBytes = network.getAddress();
                byte[] clientBytes = clientIP.getAddress();

                if (networkBytes.length != clientBytes.length) {
                    return false;
                }

                int bytesToCheck = prefixLength / 8;
                int remainingBits = prefixLength % 8;

                // Vérifier les octets complets
                for (int i = 0; i < bytesToCheck; i++) {
                    if (networkBytes[i] != clientBytes[i]) {
                        return false;
                    }
                }

                // Vérifier les bits restants
                if (remainingBits > 0 && bytesToCheck < networkBytes.length) {
                    int mask = 0xFF << (8 - remainingBits);
                    if ((networkBytes[bytesToCheck] & mask) != (clientBytes[bytesToCheck] & mask)) {
                        return false;
                    }
                }

                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public String getRule() {
            return rule;
        }
    }
}