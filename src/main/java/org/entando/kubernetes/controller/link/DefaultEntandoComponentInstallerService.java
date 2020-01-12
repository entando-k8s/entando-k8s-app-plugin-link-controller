package org.entando.kubernetes.controller.link;

import static java.lang.Thread.sleep;
import static java.util.Optional.ofNullable;
import static org.entando.kubernetes.controller.KubeUtils.ENTANDO_KEYCLOAK_REALM;
import static org.entando.kubernetes.controller.KubeUtils.OPERATOR_CLIENT_ID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;

public class DefaultEntandoComponentInstallerService implements EntandoComponentInstallerService {

    private final Logger logger = Logger.getLogger(DefaultEntandoComponentInstallerService.class.getName());

    public static int getResponseStatus(HttpResponse response) {
        return response.getStatusLine().getStatusCode();
    }

    @Override
    public boolean isPluginHealthy(String healthCheckUrl) {
        //TODO we can move this to a utility class in common
        try {
            CloseableHttpClient client = HttpClientBuilder.create().build();
            HttpGet request = new HttpGet(healthCheckUrl);
            for (int i = 0; i < 10; i++) {
                CloseableHttpResponse response = client.execute(request);
                if (getResponseStatus(response) == 200) {
                    return true;
                } else {
                    logger.severe(() -> "Could check health status: " + getResponseStatus(response));
                    sleep(5000);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return false;
    }

    @Override
    public void registerPluginComponents(String keycloakAuthUrl, String externalBaseUrlForPlugin, String externalBaseUrlForApp) {
        try {
            String token = getOauthToken(keycloakAuthUrl);
            List<WidgetRequest> defaultWidgets = getWidgetRequests(token, externalBaseUrlForPlugin);
            registerDefaultWidgetsWithEntandoApp(defaultWidgets, token, externalBaseUrlForApp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException | HttpException e) {
            throw new IllegalStateException(e);
        }
    }

    private void registerDefaultWidgetsWithEntandoApp(List<WidgetRequest> defaultWidgets, String token, String externalBaseUrlForApp)
            throws IOException, HttpException {
        String widgetEndpoints = externalBaseUrlForApp + "/api/widgets";
        ObjectMapper mapper = new ObjectMapper();
        CloseableHttpClient client = HttpClientBuilder.create().build();
        HttpPost request = new HttpPost(widgetEndpoints);
        for (WidgetRequest widgetRequest : defaultWidgets) {
            StringEntity requestBody = new StringEntity(mapper.writeValueAsString(widgetRequest));
            request.setEntity(requestBody);
            request.addHeader("Authorization", "Bearer " + token);
            request.addHeader("Accept", "application/json");
            request.addHeader("Content-type", "application/json");
            CloseableHttpResponse response = client.execute(request);
            int status = getResponseStatus(response);
            String responseBody = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            if (!Integer.toString(status).startsWith("2") && !responseBody.contains("already exists")) {
                throw new HttpException(
                        "An error occurred while registering widget " + widgetRequest.getCode() + " in Entando\n"
                                + responseBody);
            }
        }
    }

    private List<WidgetRequest> getWidgetRequests(String token, String externalBaseUrlForPlugin)
            throws IOException, InterruptedException, HttpException {
        String defaultWidgetEndpoint = externalBaseUrlForPlugin + "/api/widgets";
        ObjectMapper mapper = new ObjectMapper();
        CloseableHttpClient client = HttpClientBuilder.create().build();
        //Messy but it will disappear soon
        HttpGet request = new HttpGet(defaultWidgetEndpoint);
        request.addHeader("Authorization", "Bearer " + token);
        for (int i = 0; i < 10; i++) {
            CloseableHttpResponse response = client.execute(request);
            if (getResponseStatus(response) == 200) {
                List<WidgetRequest> defaultWidgets = mapper
                        .readValue(response.getEntity().getContent(), new TypeReference<List<WidgetRequest>>() {
                        });
                return ofNullable(defaultWidgets).orElse(new ArrayList<>());
            } else {
                logger.severe(() -> "Could not get widgets: " + getResponseStatus(response));
                sleep(5000);
            }
        }
        throw new HttpException("Could not get widgets");
    }

    private String getOauthToken(String keycloakBaseUrl) throws IOException, HttpException {
        //TODO we can move this to KeycloakClient
        String keycloakTokenUrl = keycloakBaseUrl + "/realms/" + ENTANDO_KEYCLOAK_REALM + "/protocol/openid-connect/token";
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("client_id", OPERATOR_CLIENT_ID));
        params.add(new BasicNameValuePair("client_secret", OPERATOR_CLIENT_ID));
        params.add(new BasicNameValuePair("grant_type", "client_credentials"));
        HttpPost httpPost = new HttpPost(keycloakTokenUrl);
        httpPost.setEntity(new UrlEncodedFormEntity(params));
        CloseableHttpClient client = HttpClientBuilder.create().build();
        CloseableHttpResponse response = client.execute(httpPost);
        int responseStatus = getResponseStatus(response);
        if (responseStatus != 200) {
            throw new HttpException("An error occurred while retrieving authorization token");
        }
        TypeReference<HashMap<String, String>> typeRef = new TypeReference<HashMap<String, String>>() {
        };

        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> map = mapper.readValue(response.getEntity().getContent(), typeRef);
        return map.get("access_token");
    }

    @RegisterForReflection
    public static class WidgetRequest {

        private String code;

        private Map<String, String> titles;

        private String group;

        private String customUi;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public Map<String, String> getTitles() {
            return titles;
        }

        public void setTitles(Map<String, String> titles) {
            this.titles = titles;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getCustomUi() {
            return customUi;
        }

        public void setCustomUi(String customUi) {
            this.customUi = customUi;
        }

    }

}
