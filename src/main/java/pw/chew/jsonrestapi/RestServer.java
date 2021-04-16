package pw.chew.jsonrestapi;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public class RestServer extends Thread {

    private static FileConfiguration config;
    private static Logger logger;

    public RestServer(FileConfiguration config, Logger logger) {
        RestServer.config = config;
        RestServer.logger = logger;
    }

    private int getPort() {
        return config.getInt("port");
    }

    public static void updateConfig(FileConfiguration newConfig) {
        config = newConfig;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    @Override
    public void run() {
        try {
            ServerSocket socket = new ServerSocket(getPort());
            while (true) {
                ServerResponder responder = new ServerResponder(socket.accept());
                responder.setDaemon(true);
                responder.start();
            }
        } catch (IOException ex) {
            logger.severe("Failed to initialize server socket.");
            ex.printStackTrace();
        }
    }

    private static class ServerResponder extends Thread {
        private final Socket inSocket;
        private final List<String> acceptedMethods = Arrays.asList("GET", "POST");

        private ServerResponder(Socket inSocket) {
            this.inSocket = inSocket;
        }

        @Override
        public void run() {
            try (
                InputStreamReader inputStreamReader = new InputStreamReader(inSocket.getInputStream());
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(inSocket.getOutputStream());
                PrintWriter printWriter = new PrintWriter(outputStreamWriter)
            ) {
                String request = bufferedReader.readLine();
                if (request == null) {
                    return;
                }

                while (true) {
                    String ignore = bufferedReader.readLine();
                    if (ignore == null || ignore.length() == 0) {
                        break;
                    }
                }

                int initialSplit = request.indexOf(' ');
                if (initialSplit == -1) {
                    respondBadRequest(printWriter);
                    return;
                }

                String method = request.substring(0, initialSplit);

                if (method.isEmpty()) {
                    respondBadRequest(printWriter);
                    return;
                }

                if (!acceptedMethods.contains(method.toUpperCase(Locale.ROOT))) {
                    respondNotAllowed(printWriter);
                    return;
                }

                int routeSplit = request.indexOf(' ', initialSplit + 1);
                if (routeSplit == -1) {
                    respondBadRequest(printWriter);
                    return;
                }

                String route = request.substring(initialSplit + 1, routeSplit);
                int routeQuerySplit = route.indexOf('?');
                if (routeQuerySplit != -1) {
                    route = route.substring(0, routeQuerySplit);
                }

                // Remove initial slash
                route = route.replaceFirst("/", "");

                logger.info("Received request with route: " + route);

                // Check if route exists
                if (config.contains("routes." + route)) {
                    String key = "routes." + route + ".";
                    String configMethod = config.getString(key + "method");

                    if (!method.equals(configMethod)) {
                        respondNotAllowed(printWriter);
                        return;
                    }

                    String response = PlaceholderAPI.setPlaceholders(Bukkit.getOfflinePlayer(UUID.randomUUID()), config.getString(key + "placeholder"));

                    respondOk(printWriter, response);
                } else {
                    respondNotFound(printWriter);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Responds to the HTTP request with a not allowed header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondNotAllowed(PrintWriter writer) {
            writer.printf("HTTP/1.2 405 Not Allowed%n%n");
        }

        /**
         * Responds to the HTTP request with a bad request header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondBadRequest(PrintWriter writer) {
            writer.printf("HTTP/1.2 400 Bad Request%n%n");
        }

        /**
         * Responds to the HTTP request with a not found header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondNotFound(PrintWriter writer) {
            writer.printf("HTTP/1.2 404 Not Found%n%n");
        }

        /**
         * Responds to the HTTP request with an OK header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondOk(PrintWriter writer, String response) {
            writer.printf("HTTP/1.2 200 OK%n%n" + response);
        }
    }
}
