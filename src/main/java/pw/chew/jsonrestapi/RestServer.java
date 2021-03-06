package pw.chew.jsonrestapi;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

                // Debug message
                if (config.getBoolean("debug")) {
                    logger.info("Received request: " + request);
                }

                // Get parameters and route
                String route = request.substring(initialSplit + 1, routeSplit);
                Map<String, String> params = new HashMap<>();
                int routeQuerySplit = route.indexOf('?');
                if (routeQuerySplit != -1) {
                    route = route.substring(0, routeQuerySplit);
                    String paramString = request.split(" ")[1].split("\\?")[1];
                    String[] paramSplit = paramString.split("&");
                    for (String param : paramSplit) {
                        String key = param.split("=")[0];
                        String value = param.split("=")[1];

                        // Debug message
                        if (config.getBoolean("debug")) {
                            logger.info("Added parameter " + key + " with value " + value);
                        }
                        params.put(key, value);
                    }
                }

                // Remove initial slash
                route = route.replaceFirst("/", "");

                // Debug message
                if (config.getBoolean("debug")) {
                    logger.info("Route: " + route);
                }

                // Default route.
                // This is a special case.
                if (route.equals("")) {
                    // Handle GET, just in case they try to go to the URL.
                    if (method.equals("GET")) {
                        // Send a "I'm alive!" response
                        respondOk(printWriter, "{\"success\": true}");
                    } else {
                        // Handle POST as described in the config.
                        // They essentially pass what would be in the route
                        // Requires "request", "key", and "username"/"uuid"

                        respondOk(printWriter, buildResponse(method, route, params, printWriter,
                            true, params.getOrDefault("request", "")));
                    }
                    // We're done here.
                    return;
                }

                // Check if route exists
                if (config.contains("routes." + route)) {
                    // Build the request and send it off!
                    respondOk(printWriter, buildResponse(method, route, params, printWriter, false, null));
                } else {
                    // Doesn't exist. Try again!
                    respondNotFound(printWriter);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private boolean isUnauthorized(String key) {
            return !key.equals(config.getString("authkey"));
        }

        private String buildResponse(String method, String route, Map<String, String> params, PrintWriter printWriter,
                                     boolean root, String request) /* Root request-specific params */ {
            // By default, POST requires a key.
            boolean keyRequired = method.equals("POST");

            // Don't do this stuff for the root route.
            if (!root) {
                // Get the config key for this route
                String key = "routes." + route + ".";

                // Ensure the method matches the route itself
                if (!method.equals(config.getString(key + "method"))) {
                    respondNotAllowed(printWriter);
                    return null;
                }

                // If auth key is specified, override keyRequired.
                if (config.contains(key + ".authkey")) {
                    keyRequired = config.getBoolean(key + ".authkey");
                }

                // Get the request to fill in from the config.
                request = config.getString(key + "response");
            }

            // Check if a key is required, and if the key is specified
            if (keyRequired && isUnauthorized(params.getOrDefault("key", ""))) {
                respondUnauthorized(printWriter);
                return null;
            }

            // Set the player, depending on the request.
            OfflinePlayer player;
            if (method.equals("POST")) {
                if (params.containsKey("uuid")) {
                    player = Bukkit.getOfflinePlayer(UUID.fromString(params.get("uuid")));
                } else if (params.containsKey("username")) {
                    player = Bukkit.getPlayer(params.get("username"));
                } else {
                    respondBadRequest(printWriter);
                    return null;
                }
            } else {
                player = Bukkit.getOfflinePlayer(UUID.randomUUID());
            }

            // Build the response by letting PAPI parse the placeholders
            String response = PlaceholderAPI.setPlaceholders(player, request);

            // Simple check to see if response is valid JSON. If not, just wrap it.
            Gson gson = new Gson();
            try {
                // Check if string starts with what we will assume to be an object or array
                if (response.startsWith("[") || response.startsWith("{")) {
                    gson.fromJson(response, Object.class);
                } else {
                    response = "\"" + response + "\"";
                }
            } catch (JsonSyntaxException ex) {
                response = "\"" + response + "\"";
            }

            // Wrap the response
            String json = "{\"success\": true, \"response\": " + response + "}";

            if (config.getBoolean("debug")) {
                logger.info("Response is " + json);
            }

            return json;
        }

        /**
         * Responds to the HTTP request with a not allowed header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondNotAllowed(PrintWriter writer) {
            writer.print("HTTP/1.2 405 Not Allowed\n"
                + "Content-Type: application/json; charset=utf-8\n"
                + "\n"
                + "{\"success\": false}");
        }

        /**
         * Responds to the HTTP request with a bad request header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondBadRequest(PrintWriter writer) {
            writer.print("HTTP/1.2 400 Bad Request\n"
                + "Content-Type: application/json; charset=utf-8\n"
                + "\n"
                + "{\"success\": false}");
        }

        /**
         * Responds to the HTTP request with a not found header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondNotFound(PrintWriter writer) {
            writer.print("HTTP/1.2 404 Not Found\n"
                + "Content-Type: application/json; charset=utf-8\n"
                + "\n"
                + "{\"success\": false}");
        }

        /**
         * Responds to the HTTP request with a not found header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondUnauthorized(PrintWriter writer) {
            writer.print("HTTP/1.2 401 Unauthorized\n"
                + "Content-Type: application/json; charset=utf-8\n"
                + "\n"
                + "{\"success\": false}");
        }

        /**
         * Responds to the HTTP request with an OK header.
         *
         * @param writer The {@link PrintWriter} to print the response to.
         */
        private void respondOk(PrintWriter writer, String response) {
            // Don't send a null response.
            if (response == null) {
                return;
            }

            writer.print("HTTP/1.2 200 OK\n" + "Content-Type: application/json; charset=utf-8" + "\n\n" + response);
        }
    }
}
