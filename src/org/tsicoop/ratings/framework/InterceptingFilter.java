package org.tsicoop.ratings.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.*;


public class InterceptingFilter implements Filter {

    private static final String URL_DELIMITER = "/";
    private static final String ADMIN_URI_PATH = "admin";
    private static final String CLIENT_URI_PATH = "client";

    private static final String BOOTSTRAP_URI_PATH = "bootstrap";
    private static final String API_PREFIX = "/api/v1/";

    private static final Set<String> CLIENT_ALLOWED_FUNCS = new HashSet<>(Arrays.asList(
            "record_consent",
            "get_active_consent",
            "get_policy",
            "get_active_policy",
            "link_user",
            "submit_grievance",
            "get_grievance",
            "get_dma_assessment_details"
    ));

    private static final Set<String> ADMIN_NOAUTH_FUNCS = new HashSet<>(Arrays.asList(
            "request_otp",
            "register_user",
            "login_otp",
            "login",
            "get_cma_status",
            "get_dma_assessment_details",
            "validate_assessment"
    ));

    private static final HashMap<String, String> filterConfig = new HashMap<>();

    @Override
    public void destroy() {
    }

    static {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String method = req.getMethod();
        String uri = req.getRequestURI();
        String servletPath = req.getServletPath();

        if (servletPath != null && servletPath.equalsIgnoreCase("/verify")) {
            request.getRequestDispatcher("/blockchain-proof.html").forward(request, response);
            return;
        } else if (!servletPath.startsWith("/api")) {
            res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }

        /*
         * =========================
         * CORS FIX
         * =========================
         */

        String origin = req.getHeader("Origin");

        if (origin != null) {
            res.setHeader("Access-Control-Allow-Origin", origin);
        }

        res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers",
                "Origin, Content-Type, Accept, Authorization, X-API-KEY");
        res.setHeader("Access-Control-Allow-Credentials", "true");
        res.setHeader("Access-Control-Max-Age", "3600");

        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");

        // Handle OPTIONS preflight request
        if ("OPTIONS".equalsIgnoreCase(method)) {
            res.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        Properties apiRegistry = SystemConfig.getProcessorConfig();

        if (!uri.startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String pathAfterApiPrefix = uri.substring(API_PREFIX.length());
        String[] pathSegments = pathAfterApiPrefix.split(URL_DELIMITER);

        String apiCategory = null;
        String serviceName = null;

        if (pathSegments.length >= 1) {

            if (ADMIN_URI_PATH.equalsIgnoreCase(pathSegments[0])) {

                apiCategory = ADMIN_URI_PATH;

                if (pathSegments.length >= 2) {
                    serviceName = pathSegments[1];
                }

            } else if (CLIENT_URI_PATH.equalsIgnoreCase(pathSegments[0])) {

                apiCategory = CLIENT_URI_PATH;

                if (pathSegments.length >= 2) {
                    serviceName = pathSegments[1];
                }

            } else if (BOOTSTRAP_URI_PATH.equalsIgnoreCase(pathSegments[0])) {

                apiCategory = BOOTSTRAP_URI_PATH;

                if (pathSegments.length >= 2) {
                    serviceName = pathSegments[1];
                }

            } else {

                apiCategory = ADMIN_URI_PATH;
                serviceName = pathSegments[0];
            }
        }

        String targetServletPath = API_PREFIX + (serviceName != null ? serviceName : "");

        if (!apiRegistry.containsKey(targetServletPath.trim())) {

            OutputProcessor.errorResponse(
                    res,
                    HttpServletResponse.SC_NOT_FOUND,
                    "Not Found",
                    "API endpoint not found: " + uri,
                    uri
            );

            return;
        }

        String classname = apiRegistry.getProperty(targetServletPath.trim());

        if (classname == null) {

            OutputProcessor.errorResponse(
                    res,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Configuration Error",
                    "Servlet class not mapped for: " + uri,
                    uri
            );

            return;
        }

        boolean authenticated = false;
        String errorMessage = "Authentication failed.";

        try {

            InputProcessor.processInput(req, res);

            JSONObject inputJson = InputProcessor.getInput(req);

            String func = null;

            if (inputJson != null) {
                func = (String) inputJson.get("_func");
            }

            if ("POST".equalsIgnoreCase(method)) {

                if (!InputProcessor.validate(req, res)) {
                    return;
                }

                if (inputJson == null) {

                    OutputProcessor.errorResponse(
                            res,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "Bad Request",
                            "Missing JSON request body.",
                            uri
                    );

                    return;
                }

                if (func == null || func.trim().isEmpty()) {

                    OutputProcessor.errorResponse(
                            res,
                            HttpServletResponse.SC_BAD_REQUEST,
                            "Bad Request",
                            "Missing required '_func' attribute in input JSON.",
                            uri
                    );

                    return;
                }

                if (CLIENT_URI_PATH.equalsIgnoreCase(apiCategory)) {

                    if (!CLIENT_ALLOWED_FUNCS.contains(func.toLowerCase())) {

                        OutputProcessor.errorResponse(
                                res,
                                HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden",
                                "Function '" + func + "' is not allowed for client API access.",
                                uri
                        );

                        return;
                    }
                }
            }

            /*
             * =========================
             * AUTHENTICATION
             * =========================
             */

            if (ADMIN_URI_PATH.equalsIgnoreCase(apiCategory)) {

                if (func != null && ADMIN_NOAUTH_FUNCS.contains(func.toLowerCase())) {

                    authenticated = true;

                } else {

                    authenticated = InputProcessor.processAdminHeader(req, res);
                }

            } else if (CLIENT_URI_PATH.equalsIgnoreCase(apiCategory)) {

                authenticated = InputProcessor.processClientHeader(req, res);

            } else if (BOOTSTRAP_URI_PATH.equalsIgnoreCase(apiCategory)) {

                authenticated = true;

            } else {

                errorMessage = "API category not specified or recognized. Access denied.";
            }

            if (!authenticated) {

                OutputProcessor.errorResponse(
                        res,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Unauthorized",
                        errorMessage,
                        uri
                );

                return;
            }

            /*
             * =========================
             * LOAD ACTION
             * =========================
             */

            Action action = ((Action) Class.forName(classname)
                    .getConstructor()
                    .newInstance());

            boolean validRequest = action.validate(method, req, res);

            if (validRequest) {

                if (method.equalsIgnoreCase("POST")) {

                    action.post(req, res);

                } else {

                    res.sendError(
                            HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                            "Method Not Allowed"
                    );
                }
            }

        } catch (ClassNotFoundException |
                 NoSuchMethodException |
                 InstantiationException |
                 IllegalAccessException |
                 java.lang.reflect.InvocationTargetException e) {

            e.printStackTrace();

            OutputProcessor.errorResponse(
                    res,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Server Error",
                    "Failed to instantiate API handler: " + e.getMessage(),
                    uri
            );

        } catch (Exception e) {

            e.printStackTrace();

            OutputProcessor.errorResponse(
                    res,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal Server Error",
                    "An unexpected error occurred: " + e.getMessage(),
                    uri
            );
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

        SystemConfig.loadProcessorConfig(filterConfig.getServletContext());
        System.out.println("Loaded TSI Processor Config");

        SystemConfig.loadAppConfig(filterConfig.getServletContext());
        System.out.println("Loaded TSI App Config");

        JSONSchemaValidator.createInstance(filterConfig.getServletContext());
        System.out.println("Loaded TSI Schema Validator");

        System.out.println(
                "TSI Ratings Service started in "
                        + System.getenv("TSI_RATINGS_ENV")
                        + " environment"
        );
    }
}
