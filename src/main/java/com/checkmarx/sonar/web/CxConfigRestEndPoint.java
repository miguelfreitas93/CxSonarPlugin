package com.checkmarx.sonar.web;

import com.checkmarx.sonar.cxrules.CxSonarConstants;
import com.checkmarx.sonar.dto.CxFullCredentials;
import com.checkmarx.sonar.dto.RestEndpointContext;
import com.checkmarx.sonar.sensor.utils.CxConfigHelper;
import com.cx.restclient.CxShragaClient;
import com.cx.restclient.exception.CxClientException;
import com.cx.restclient.sast.dto.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicHeader;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by: Zoharby.
 * Date: 08/05/2017.
 */
public class CxConfigRestEndPoint implements WebService {

    private static final String COMPONENT_KEY_PARAM = "component";
    private static final String IS_SUCCESSFUL = "isSuccessful";
    private static final String ERROR_MESSAGE = "errorMsg";

    private Logger logger = LoggerFactory.getLogger(CxConfigRestEndPoint.class);

    private CxShragaClient shraga;

    @Override
    public void define(Context context) {
        NewController controller = context.createController("api/checkmarx");
        controller.setDescription("Checkmarx plugin web service");

        NewAction testConnection = controller.createAction("connect")
                .setPost(true)
                .setDescription("Entry point")
                .setInternal(true)
                .setHandler(new RequestHandler() {
                    @Override
                    public void handle(Request request, Response response) {
                        try {
                            CxFullCredentials cxFullCredentials;
                            String credentialsJson = request.getParam("credentials").getValue();
                            if (StringUtils.isNotEmpty(credentialsJson)) {
                                ObjectMapper mapper = new ObjectMapper();
                                cxFullCredentials = mapper.readValue(credentialsJson, CxFullCredentials.class);
                                setPasswordIfMissing(cxFullCredentials, request);
                            } else {
                                throw new IOException("No credentials provided");
                            }

                            validateCredentials(cxFullCredentials);

                            URL url = new URL(cxFullCredentials.getCxServerUrl());
                            HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();

                            shraga = new CxShragaClient(cxFullCredentials.getCxServerUrl().trim(), cxFullCredentials.getCxUsername(), cxFullCredentials.getCxPassword(), CxSonarConstants.CX_SONAR_ORIGIN, false, logger);
                            shraga.login();
                            urlConn.connect();

                            // read request parameters and generates response output
                            sendSuccess(response);
                        } catch (Exception e) {
                            logger.error("Login failed due to Exception: " + e.getMessage());
                            sendError(response, e.getMessage());
                        }
                    }
                });
        testConnection.createParam("credentials").setDescription("cx credentials").setRequired(true);
        testConnection.createParam(COMPONENT_KEY_PARAM).setDescription("Current component key").setRequired(true);

        controller.createAction("projects")
                .setPost(true)
                .setDescription("Return projects of default Checkmarx server (the server that was configured by SonarQube administrator).")
                .setInternal(true)
                .setHandler(new RequestHandler() {
                    @Override
                    public void handle(Request request, Response response) {

                        logger.info("Retrieving Cx server projects.");
                        try {
                            String projects = getProjects();
                            response.newJsonWriter()
                                    .beginObject()
                                    .prop("projects", projects)
                                    .prop(IS_SUCCESSFUL, true)
                                    .endObject()
                                    .close();
                        } catch (Exception e) {
                            e.printStackTrace();
                            logger.error("Projects retrieval failed due to Exception: " + e.getMessage());
                            response.newJsonWriter()
                                    .beginObject()
                                    .prop("projects", "")
                                    .prop(IS_SUCCESSFUL, false)
                                    .prop(ERROR_MESSAGE, e.getMessage())
                                    .endObject()
                                    .close();
                        }
                    }
                });

        controller.createAction("clean_connection")
                .setPost(true)
                .setDescription("Close connection of default Checkmarx server (the server that was configured by SonarQube administrator).")
                .setInternal(true)
                .setHandler(new RequestHandler() {
                    @Override
                    public void handle(Request request, Response response) {
                        logger.info("Logging out of Checkmarx.");
                        try {
                            shraga.close();
                            sendSuccess(response);

                        } catch (Exception e) {
                            logger.error("Logging out of Checkmarx failed due to Exception: " + e.getMessage());
                            sendError(response, e.getMessage());
                        }
                    }
                });

        NewAction updatePassword = controller.createAction("password")
                .setInternal(true)
                .setPost(true)
                .setHandler(this::updatePassword);
        updatePassword.createParam("password").setRequired(true);
        updatePassword.createParam(COMPONENT_KEY_PARAM).setRequired(true);

        //apply changes
        controller.done();
    }

    private void updatePassword(Request request, Response response) {
        try {
            RestEndpointContext context = getRestEndpointContext(request);

            CxConfigHelper configHelper = new CxConfigHelper(logger);
            configHelper.updatePassword(context, request.getParam("password").getValue());

            sendSuccess(response);
        } catch (Exception e) {
            final String MESSAGE = "Error updating password.";
            logger.error(MESSAGE, e);
            sendError(response, MESSAGE);
        }
    }

    private void sendSuccess(Response response) {
        response.newJsonWriter()
                .beginObject()
                .prop(IS_SUCCESSFUL, true)
                .endObject()
                .close();
    }

    private void sendError(Response response, String message) {
        response.newJsonWriter()
                .beginObject()
                .prop(IS_SUCCESSFUL, false)
                .prop(ERROR_MESSAGE, message)
                .endObject()
                .close();
    }

    private void setPasswordIfMissing(CxFullCredentials cxFullCredentials, Request request) throws URISyntaxException, IOException {
        // If user hasn't changed the password, the password is null in cxFullCredentials.
        // However, we need the password to test connection => get the password from config.
        if (cxFullCredentials != null && cxFullCredentials.getCxPassword() == null) {
            RestEndpointContext context = getRestEndpointContext(request);

            CxConfigHelper configHelper = new CxConfigHelper(logger);
            String password = configHelper.getPassword(context);

            cxFullCredentials.setCxPassword(password);
        }
    }

    private RestEndpointContext getRestEndpointContext(Request request) throws URISyntaxException {
        RestEndpointContext result = new RestEndpointContext();

        String refererString = request.header("Referer").get();
        URI refererUri = new URI(refererString);

        String componentKey = request.getParam(COMPONENT_KEY_PARAM).getValue();
        result.setComponentKey(componentKey);

        String baseUrl = String.format("%s://%s", refererUri.getScheme(), refererUri.getAuthority());
        result.setSonarBaseUrl(baseUrl);

        Cookie[] requiredCookies = getRequiredCookies(request, refererUri);
        result.setRequiredCookies(requiredCookies);

        Header[] headers = getRequiredHeaders(request);
        result.setRequiredHeaders(headers);

        return result;
    }

    private Cookie[] getRequiredCookies(Request request, URI srcUri) {
        final String COOKIE_HEADER = "Cookie";
        final String COOKIE_SEPARATOR = "; ";
        final String SESSION_COOKIE_NAME = "JWT-SESSION";
        final String XSRF_TOKEN_COOKIE_NAME = "XSRF-TOKEN";

        List<Cookie> result = new ArrayList<>();

        // Copy specific cookies from the client request into the result.
        String rawCookies = request.header(COOKIE_HEADER).get();
        for (String rawCookie : rawCookies.split(COOKIE_SEPARATOR)) {
            List<HttpCookie> cookies = HttpCookie.parse(rawCookie);
            if (cookies.size() > 0) {
                HttpCookie srcCookie = cookies.get(0);
                if (srcCookie.getName().equals(SESSION_COOKIE_NAME) ||
                        srcCookie.getName().equals(XSRF_TOKEN_COOKIE_NAME)) {

                    BasicClientCookie cookieCopy = new BasicClientCookie(srcCookie.getName(), srcCookie.getValue());
                    cookieCopy.setDomain(srcUri.getHost());
                    result.add(cookieCopy);
                }
            }
        }
        return result.toArray(new Cookie[0]);
    }

    private Header[] getRequiredHeaders(Request request) {
        final String REQUIRED_HEADER_NAME = "x-xsrf-token";

        String headerValue = request.header(REQUIRED_HEADER_NAME).orElse("");
        Header requiredHeader = new BasicHeader(REQUIRED_HEADER_NAME, headerValue);
        return new Header[]{requiredHeader};
    }

    private void validateCredentials(CxFullCredentials cxFullCredentials) throws IOException {
        if (cxFullCredentials == null) {
            throw new IOException("No credentials provided");
        }
        if (cxFullCredentials.getCxServerUrl() == null || cxFullCredentials.getCxServerUrl().equals("")) {
            throw new IOException("Checkmarx server URL was not provided.");
        }
        if (cxFullCredentials.getCxUsername() == null || cxFullCredentials.getCxUsername().equals("")) {
            throw new IOException("Checkmarx server username was not provided.");
        }
        if (cxFullCredentials.getCxPassword() == null || cxFullCredentials.getCxPassword().equals("")) {
            throw new IOException("Checkmarx server password was not provided.");
        }
    }


    private String getProjects() throws IOException, CxClientException {
        List<Project> allProjects = shraga.getAllProjects();

        List<String> projectNames = new LinkedList<>();
        for (Project project : allProjects) {
            String teamName = shraga.getTeamNameById(project.getTeamId());
            projectNames.add(teamName + "\\" + project.getName());
        }
        return convertToJsonArray(projectNames);
    }

    private String convertToJsonArray(List<String> listToConvert) {
        JSONArray jsonArray = new JSONArray(listToConvert);
        return jsonArray.toString();
    }
}
