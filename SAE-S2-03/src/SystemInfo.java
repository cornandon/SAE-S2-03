import java.io.*;
import java.nio.file.*;
import java.text.DecimalFormat;

public class SystemInfo {
    private DecimalFormat df = new DecimalFormat("#.##");

    public String getStatusHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<title>Statut du Serveur</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 40px; background-color: #f5f5f5; }\n");
        html.append(".container { background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n");
        html.append("h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n");
        html.append(".stat-item { margin: 15px 0; padding: 10px; background-color: #f9f9f9; border-left: 4px solid #4CAF50; }\n");
        html.append(".stat-label { font-weight: bold; color: #555; }\n");
        html.append(".stat-value { color: #2196F3; font-size: 1.1em; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        html.append("<div class='container'>\n");
        html.append("<h1>Statut du Serveur MyWeb</h1>\n");

        // Mémoire disponible
        long freeMemory = Runtime.getRuntime().freeMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();

        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Mémoire disponible:</span> ");
        html.append("<span class='stat-value'>").append(formatBytes(freeMemory)).append("</span>\n");
        html.append("</div>\n");

        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Mémoire totale allouée:</span> ");
        html.append("<span class='stat-value'>").append(formatBytes(totalMemory)).append("</span>\n");
        html.append("</div>\n");

        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Mémoire maximale:</span> ");
        html.append("<span class='stat-value'>").append(formatBytes(maxMemory)).append("</span>\n");
        html.append("</div>\n");

        // Espace disque disponible
        try {
            File root = new File("/");
            long freeSpace = root.getFreeSpace();
            long totalSpace = root.getTotalSpace();

            html.append("<div class='stat-item'>\n");
            html.append("<span class='stat-label'>Espace disque disponible:</span> ");
            html.append("<span class='stat-value'>").append(formatBytes(freeSpace)).append("</span>\n");
            html.append("</div>\n");

            html.append("<div class='stat-item'>\n");
            html.append("<span class='stat-label'>Espace disque total:</span> ");
            html.append("<span class='stat-value'>").append(formatBytes(totalSpace)).append("</span>\n");
            html.append("</div>\n");
        } catch (Exception e) {
            html.append("<div class='stat-item'>\n");
            html.append("<span class='stat-label'>Espace disque:</span> ");
            html.append("<span class='stat-value'>Indisponible</span>\n");
            html.append("</div>\n");
        }

        // Nombre de processus (approximatif via Java)
        int processors = Runtime.getRuntime().availableProcessors();
        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Processeurs disponibles:</span> ");
        html.append("<span class='stat-value'>").append(processors).append("</span>\n");
        html.append("</div>\n");

        // Informations système supplémentaires
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String javaVersion = System.getProperty("java.version");

        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Système d'exploitation:</span> ");
        html.append("<span class='stat-value'>").append(osName).append(" ").append(osVersion).append("</span>\n");
        html.append("</div>\n");

        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Version Java:</span> ");
        html.append("<span class='stat-value'>").append(javaVersion).append("</span>\n");
        html.append("</div>\n");

        // Tentative d'obtenir des informations système Linux
        try {
            String processCount = executeCommand("ps aux | wc -l");
            if (processCount != null) {
                html.append("<div class='stat-item'>\n");
                html.append("<span class='stat-label'>Nombre de processus:</span> ");
                html.append("<span class='stat-value'>").append(processCount.trim()).append("</span>\n");
                html.append("</div>\n");
            }
        } catch (Exception e) {
            // Ignorer si la commande échoue
        }

        try {
            String userCount = executeCommand("who | wc -l");
            if (userCount != null) {
                html.append("<div class='stat-item'>\n");
                html.append("<span class='stat-label'>Utilisateurs connectés:</span> ");
                html.append("<span class='stat-value'>").append(userCount.trim()).append("</span>\n");
                html.append("</div>\n");
            }
        } catch (Exception e) {
            // Ignorer si la commande échoue
        }

        // Uptime du serveur
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        html.append("<div class='stat-item'>\n");
        html.append("<span class='stat-label'>Uptime du serveur:</span> ");
        html.append("<span class='stat-value'>").append(formatUptime(uptime)).append("</span>\n");
        html.append("</div>\n");

        html.append("</div>\n</body>\n</html>");

        return html.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return df.format(bytes / Math.pow(1024, exp)) + " " + pre + "B";
    }

    private String formatUptime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + " jour(s), " + (hours % 24) + " heure(s)";
        } else if (hours > 0) {
            return hours + " heure(s), " + (minutes % 60) + " minute(s)";
        } else if (minutes > 0) {
            return minutes + " minute(s), " + (seconds % 60) + " seconde(s)";
        } else {
            return seconds + " seconde(s)";
        }
    }

    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            return line;
        } catch (Exception e) {
            return null;
        }
    }
}