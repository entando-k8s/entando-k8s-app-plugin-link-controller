package org.entando.kubernetes.controller.link;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.spi.IngressingPathOnPort;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkSpec;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoAppPluginLinkController extends AbstractDbAwareController<EntandoAppPluginLink> {

    private EntandoComponentInstallerService entandoComponentInstallerService;

    @Inject
    public EntandoAppPluginLinkController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
        this.entandoComponentInstallerService = new DefaultEntandoComponentInstallerService();
    }

    public EntandoAppPluginLinkController(SimpleK8SClient<?> k8sClient,
            SimpleKeycloakClient keycloakClient,
            EntandoComponentInstallerService entandoComponentInstallerService) {
        super(k8sClient, keycloakClient);
        this.entandoComponentInstallerService = entandoComponentInstallerService;
    }

    public EntandoAppPluginLinkController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
        this.entandoComponentInstallerService = new DefaultEntandoComponentInstallerService();
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoAppPluginLink newEntandoAppPluginLink) {
        EntandoLinkedPluginIngressing entandoLinkedPluginIngressing = prepareEntandoPluginIngressing(newEntandoAppPluginLink);
        LinkAppToPluginCommand linkAppToPluginCommand = new LinkAppToPluginCommand(newEntandoAppPluginLink,
                entandoLinkedPluginIngressing);
        linkAppToPluginCommand.execute(k8sClient, keycloakClient);
        boolean wasSuccessful = !linkAppToPluginCommand.getStatus().hasFailed();
        if (wasSuccessful && isPluginAvailableOnAppIngress(entandoLinkedPluginIngressing)) {
            //TODO in future this is where we send the message to the Queue in the ENtandoK8S pod
            registerDefaultComponents(entandoLinkedPluginIngressing);
        }
        k8sClient.entandoResources().updateStatus(newEntandoAppPluginLink, linkAppToPluginCommand.getStatus());
    }

    private void registerDefaultComponents(EntandoLinkedPluginIngressing entandoLinkedPluginIngressing) {
        String keycloakAuthUrl = k8sClient.entandoResources().findKeycloak(entandoLinkedPluginIngressing.getEntandoApp()).getBaseUrl();
        String externalBaseUrlForPlugin = entandoLinkedPluginIngressing.getEntandoPluginDeploymentResult()
                .getExternalBaseUrlForPort("server-port");
        String externalBaseUrlForApp = entandoLinkedPluginIngressing.getEntandoAppDeploymentResult()
                .getExternalBaseUrlForPort("server-port");
        entandoComponentInstallerService.registerPluginComponents(keycloakAuthUrl, externalBaseUrlForPlugin, externalBaseUrlForApp);
    }

    private boolean isPluginAvailableOnAppIngress(EntandoLinkedPluginIngressing entandoLinkedPluginIngressing) {
        IngressingPathOnPort pluginPath = entandoLinkedPluginIngressing.getIngressingContainers().get(0);
        String healthCheck = entandoLinkedPluginIngressing.getEntandoAppDeploymentResult().getExternalHostUrl()
                + pluginPath.getHealthCheckPath().orElse("");
        return this.entandoComponentInstallerService.isPluginHealthy(healthCheck);
    }

    private EntandoLinkedPluginIngressing prepareEntandoPluginIngressing(EntandoAppPluginLink newEntandoAppPluginLink) {
        EntandoAppPluginLinkSpec spec = newEntandoAppPluginLink.getSpec();
        EntandoApp entandoApp = k8sClient.entandoResources().loadEntandoApp(spec.getEntandoAppNamespace(), spec.getEntandoAppName());
        EntandoPlugin entandoPlugin = k8sClient.entandoResources()
                .loadEntandoPlugin(spec.getEntandoPluginNamespace(), spec.getEntandoPluginName());
        k8sClient.pods().waitForPod(entandoPlugin.getMetadata().getNamespace(), DeployCommand.DEPLOYMENT_LABEL_NAME,
                entandoPlugin.getMetadata().getName() + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER);
        ServiceDeploymentResult entandoAppDeploymentResult = k8sClient.entandoResources().loadServiceResult(entandoApp);
        ServiceDeploymentResult entandoPluginDeploymentResult = k8sClient.entandoResources().loadServiceResult(entandoPlugin);
        return new EntandoLinkedPluginIngressing(entandoApp, entandoPlugin, entandoAppDeploymentResult, entandoPluginDeploymentResult);
    }

}
