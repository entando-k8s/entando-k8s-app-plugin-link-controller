package org.entando.kubernetes.controller.link;

public interface EntandoComponentInstallerService {

    boolean isPluginHealthy(String healthCheckUrl);

    void registerPluginComponents(String keycloakAuthUrl, String externalBaseUrlForPlugin, String externalBaseUrlForApp);

}