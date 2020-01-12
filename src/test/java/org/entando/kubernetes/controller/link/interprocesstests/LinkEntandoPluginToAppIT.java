package org.entando.kubernetes.controller.link.interprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.util.Collections;
import java.util.List;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.TlsHelper;
import org.entando.kubernetes.controller.integrationtest.support.ClusterInfrastructureIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.EntandoAppIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.HttpTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.K8SIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.link.EntandoAppPluginLinkController;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkBuilder;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;

@Tags({@Tag("end-to-end"), @Tag("smoke"), @Tag("inter-process")})
public class LinkEntandoPluginToAppIT implements FluentIntegrationTesting {

    public static final String TEST_LINK = "test-link";
    private static final DbmsImageVendor DBMS = DbmsImageVendor.POSTGRESQL;
    private String entandoAppHostName;
    private K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();

    @BeforeEach
    public void cleanup() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_POD_COMPLETION_TIMEOUT_SECONDS.getJvmSystemProperty(), "1200");
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_POD_READINESS_TIMEOUT_SECONDS.getJvmSystemProperty(), "1200");
        this.entandoAppHostName = EntandoAppIntegrationTestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix();
        this.helper.setTextFixture(
                deleteAll(EntandoKeycloakServer.class).fromNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
                        .deleteAll(EntandoApp.class).fromNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                        .deleteAll(EntandoAppPluginLink.class).fromNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE));
        ensureKeycloak();
        ensureApp();
        ensurePlugin();
        registerListeners();
    }

    private void registerListeners() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            helper.appPluginLinks().listenAndRespondWithImageVersionUnderTest(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
        } else {
            EntandoAppPluginLinkController controller = new EntandoAppPluginLinkController(helper.getClient(), false);
            helper.appPluginLinks()
                    .listenAndRespondWithStartupEvent(EntandoAppIntegrationTestHelper.TEST_NAMESPACE, controller::onStartup);
        }
    }

    private void ensureKeycloak() {
        helper.keycloak().listenAndRespondWithLatestImage(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE);
        helper.keycloak().ensureKeycloak();
    }

    private void ensureApp() {
        EntandoApp existingApp = helper.entandoApps().getOperations()
                .inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(EntandoAppIntegrationTestHelper.TEST_APP_NAME).get();
        if (existingApp == null || existingApp.getStatus().getEntandoDeploymentPhase() != EntandoDeploymentPhase.SUCCESSFUL) {
            helper.setTextFixture(deleteAll(EntandoApp.class).fromNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE));
            EntandoApp entandoApp = new EntandoAppBuilder().withNewSpec().withStandardServerImage(JeeServer.WILDFLY)
                    .withDbms(DbmsImageVendor.POSTGRESQL)
                    .withIngressHostName(entandoAppHostName)
                    .withReplicas(1)
                    .withEntandoImageVersion("6.0.0-SNAPSHOT")
                    .withTlsSecretName(null)
                    .endSpec()
                    .build();
            entandoApp.setMetadata(new ObjectMeta());
            entandoApp.getMetadata().setName(EntandoAppIntegrationTestHelper.TEST_APP_NAME);
            this.helper.keycloak().deleteKeycloakClients("entando-web", EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-de",
                    EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-" + "server");
            this.helper.clusterInfrastructure().ensureInfrastructureSecret();
            String k8sSvcClientId = ClusterInfrastructureIntegrationTestHelper.CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc";
            this.helper.keycloak().ensureKeycloakClient(k8sSvcClientId, Collections.singletonList(KubeUtils.ENTANDO_APP_ROLE));
            helper.entandoApps().listenAndRespondWithLatestImage(EntandoAppIntegrationTestHelper.TEST_NAMESPACE);
            this.helper.entandoApps().createAndWaitForApp(entandoApp, 30, true);
        }
    }

    private void ensurePlugin() {
        EntandoPlugin existingPlugin = helper.entandoPlugins().getOperations()
                .inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME).get();
        if (existingPlugin == null || existingPlugin.getStatus().getEntandoDeploymentPhase() != EntandoDeploymentPhase.SUCCESSFUL) {
            String entandoPluginHostName =
                    EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "." + helper.getDomainSuffix();
            helper.setTextFixture(deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE));
            EntandoPlugin entandoPlugin = new EntandoPluginBuilder().withNewMetadata()
                    .withNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                    .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME).endMetadata()
                    .withNewSpec()
                    .withImage("entando/entando-avatar-plugin")
                    .withDbms(DBMS)
                    .withReplicas(1)
                    .withIngressHostName(entandoPluginHostName)
                    .withHealthCheckPath("/management/health")
                    .withIngressPath("/avatarPlugin")
                    .withSecurityLevel(PluginSecurityLevel.STRICT)
                    .endSpec().build();
            String name = entandoPlugin.getMetadata().getName();
            this.helper.keycloak().deleteKeycloakClients(name + "-confsvc", name + "-" + "server", name + "-sidecar");
            this.helper.entandoPlugins().listenAndRespondWithLatestImage(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE);
            this.helper.entandoPlugins().createAndWaitForPlugin(entandoPlugin, true);
        }
    }

    @AfterEach
    public void afterwards() {
        helper.afterTest();
    }

    @Test
    public void verifyWidgetRegistration() {
        helper.appPluginLinks().getOperations().create(new EntandoAppPluginLinkBuilder()
                .withNewMetadata().withNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE).withName(TEST_LINK)
                .endMetadata()
                .withNewSpec()
                .withEntandoApp(EntandoAppIntegrationTestHelper.TEST_NAMESPACE, EntandoAppIntegrationTestHelper.TEST_APP_NAME)
                .withEntandoPlugin(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE,
                        EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME)
                .endSpec()
                .build());
        //TODO test if it is available on the right path
        await().atMost(60, SECONDS).until(() -> helper.appPluginLinks().getOperations()
                .inNamespace(EntandoAppIntegrationTestHelper.TEST_NAMESPACE)
                .withName(TEST_LINK).get().getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.SUCCESSFUL);
        //Retrieve k8s-operator-token
        String tokenUrl =
                TlsHelper.getDefaultProtocol() + "://" + KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "." + helper
                        .getDomainSuffix()
                        + "/auth/realms/entando/protocol/openid-connect/token";
        String operatorToken = HttpTestHelper.getOperatorOAuthToken(tokenUrl);
        List<String> pluginWidgetsCodes = HttpTestHelper
                .getPluginsWidgetsCode(TlsHelper.getDefaultProtocol() + "://" + entandoAppHostName + "/avatarPlugin", operatorToken);
        assertThat("There are some default widgets", !pluginWidgetsCodes.isEmpty());

        List<String> coreInstalledWidgetsCodes = HttpTestHelper
                .getEntandoCoreWidgetsCode(TlsHelper.getDefaultProtocol() + "://" + entandoAppHostName + "/entando-de-app", operatorToken);
        assertThat(String.format("[%s] core widgets don't contain [%s]",
                String.join(", ", coreInstalledWidgetsCodes),
                String.join(", ", pluginWidgetsCodes)),
                coreInstalledWidgetsCodes.containsAll(pluginWidgetsCodes));
        List<RoleRepresentation> roles = helper.keycloak()
                .retrieveServiceAccountRoles(EntandoAppIntegrationTestHelper.TEST_APP_NAME + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER,
                        EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER);
        assertTrue(roles.stream().anyMatch(roleRepresentation -> roleRepresentation.getName().equals(KubeUtils.ENTANDO_APP_ROLE)));
    }

}
