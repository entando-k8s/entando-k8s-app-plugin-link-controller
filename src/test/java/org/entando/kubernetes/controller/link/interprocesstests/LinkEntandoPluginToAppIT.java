/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.link.interprocesstests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Collections;
import java.util.List;
import org.entando.kubernetes.client.EntandoOperatorTestConfig;
import org.entando.kubernetes.client.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.client.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.link.EntandoAppPluginLinkController;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.support.client.InfrastructureConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.TlsHelper;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.ResourceReference;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkBuilder;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.entando.kubernetes.test.e2etest.helpers.EntandoAppE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.EntandoPluginE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.K8SIntegrationTestHelper;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;

@Tags({@Tag("end-to-end"), @Tag("smoke"), @Tag("inter-process")})
class LinkEntandoPluginToAppIT implements FluentIntegrationTesting {

    public static final String CLUSTER_INFRASTRUCTURE_NAMESPACE = EntandoOperatorTestConfig
            .calculateNameSpace("entando-infra-namespace");
    public static final String CLUSTER_INFRASTRUCTURE_NAME = EntandoOperatorTestConfig.calculateName("eti");
    public static final String TEST_LINK = "test-link";
    private String entandoAppHostName;
    private final K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();
    private final EntandoAppPluginLinkE2ETestHelper appPluginLinks = new EntandoAppPluginLinkE2ETestHelper(
            helper.getClient());

    LinkEntandoPluginToAppIT() {
        EntandoOperatorConfig.getCertificateAuthoritySecretName()
                .ifPresent(s -> TlsHelper.trustCertificateAuthoritiesIn(helper.getClient().secrets().withName(s).get()));
    }

    @BeforeEach
    public void cleanup() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_POD_COMPLETION_TIMEOUT_SECONDS.getJvmSystemProperty(), "1200");
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_POD_READINESS_TIMEOUT_SECONDS.getJvmSystemProperty(), "1200");
        this.helper.keycloak().prepareDefaultKeycloakSecretAndConfigMap();
        this.helper.keycloak().deleteRealm(KeycloakE2ETestHelper.KEYCLOAK_REALM);
        this.entandoAppHostName = EntandoAppE2ETestHelper.TEST_APP_NAME + "." + helper.getDomainSuffix();
        this.helper.setTextFixture(
                deleteAll(EntandoKeycloakServer.class).fromNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                        .deleteAll(EntandoApp.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
                        .deleteAll(EntandoAppPluginLink.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE));
        ensureApp();
        ensurePlugin();
        registerListeners();
    }

    private void registerListeners() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            appPluginLinks().listenAndRespondWithImageVersionUnderTest(EntandoAppE2ETestHelper.TEST_NAMESPACE);
        } else {
            EntandoAppPluginLinkController controller = new EntandoAppPluginLinkController(helper.getClient(), false);
            appPluginLinks().listenAndRespondWithStartupEvent(EntandoAppE2ETestHelper.TEST_NAMESPACE, controller::onStartup);
        }
    }

    public EntandoAppPluginLinkE2ETestHelper appPluginLinks() {
        return this.appPluginLinks;
    }

    private void ensureApp() {
        EntandoApp existingApp = helper.entandoApps().getOperations()
                .inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME).get();
        if (existingApp == null || existingApp.getStatus().getEntandoDeploymentPhase() != EntandoDeploymentPhase.SUCCESSFUL) {
            helper.setTextFixture(deleteAll(EntandoApp.class).fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE));
            EntandoApp entandoApp = new EntandoAppBuilder().withNewSpec().withStandardServerImage(JeeServer.WILDFLY)
                    .withDbms(DbmsVendor.EMBEDDED)
                    .withNewKeycloakToUse()
                    .withRealm(KeycloakE2ETestHelper.KEYCLOAK_REALM)
                    .endKeycloakToUse()
                    .withIngressHostName(entandoAppHostName)
                    .withReplicas(1)
                    .withTlsSecretName(null)
                    .endSpec()
                    .build();
            entandoApp.setMetadata(new ObjectMeta());
            entandoApp.getMetadata().setName(EntandoAppE2ETestHelper.TEST_APP_NAME);
            this.helper.keycloak()
                    .deleteKeycloakClients(entandoApp, "entando-web", EntandoAppE2ETestHelper.TEST_APP_NAME + "-de",
                            EntandoAppE2ETestHelper.TEST_APP_NAME + "-" + "server");
            this.ensureInfrastructureConnectionConfig();
            String k8sSvcClientId = CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc";
            this.helper.keycloak()
                    .ensureKeycloakClient(entandoApp.getSpec(), k8sSvcClientId, Collections.singletonList(KubeUtils.ENTANDO_APP_ROLE));
            helper.entandoApps().listenAndRespondWithLatestImage(EntandoAppE2ETestHelper.TEST_NAMESPACE);
            this.helper.entandoApps().createAndWaitForApp(entandoApp, 30, false);
        }
    }

    private void ensurePlugin() {
        EntandoPlugin existingPlugin = helper.entandoPlugins().getOperations()
                .inNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME).get();
        if (existingPlugin == null || existingPlugin.getStatus().getEntandoDeploymentPhase() != EntandoDeploymentPhase.SUCCESSFUL) {
            String entandoPluginHostName =
                    EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME + "." + helper.getDomainSuffix();
            helper.setTextFixture(deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE));
            EntandoPlugin entandoPlugin = new EntandoPluginBuilder().withNewMetadata()
                    .withNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
                    .withName(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME).endMetadata()
                    .withNewSpec()
                    .withDbms(DbmsVendor.EMBEDDED)
                    .withNewKeycloakToUse()
                    .withRealm(KeycloakE2ETestHelper.KEYCLOAK_REALM)
                    .endKeycloakToUse()
                    .withImage("entando/entando-avatar-plugin")
                    .withReplicas(1)
                    .withIngressHostName(entandoPluginHostName)
                    .withHealthCheckPath("/management/health")
                    .withIngressPath("/avatarPlugin")
                    .withSecurityLevel(PluginSecurityLevel.STRICT)
                    .endSpec().build();
            String name = entandoPlugin.getMetadata().getName();
            this.helper.keycloak()
                    .deleteKeycloakClients(entandoPlugin, name + "-confsvc", name + "-" + "server", name + "-sidecar");
            this.helper.entandoPlugins().listenAndRespondWithLatestImage(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE);
            this.helper.entandoPlugins().createAndWaitForPlugin(entandoPlugin, false);
        }
    }

    @AfterEach
    public void afterwards() {
        helper.afterTest();
    }

    @Test
    void verifyIngressPathCreation() {
        appPluginLinks().getOperations().create(new EntandoAppPluginLinkBuilder()
                .withNewMetadata().withNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE).withName(TEST_LINK)
                .endMetadata()
                .withNewSpec()
                .withEntandoApp(EntandoAppE2ETestHelper.TEST_NAMESPACE, EntandoAppE2ETestHelper.TEST_APP_NAME)
                .withEntandoPlugin(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE,
                        EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME)
                .endSpec()
                .build());
        //TODO test if it is available on the right path
        await().atMost(60, SECONDS).until(() -> appPluginLinks().getOperations()
                .inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(TEST_LINK).get().getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.SUCCESSFUL);
        //Retrieve k8s-operator-token
        List<RoleRepresentation> roles = helper.keycloak()
                .retrieveServiceAccountRoles(KeycloakE2ETestHelper.KEYCLOAK_REALM,
                        EntandoAppE2ETestHelper.TEST_APP_NAME + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER,
                        EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER);
        assertTrue(roles.stream().anyMatch(roleRepresentation -> roleRepresentation.getName().equals(KubeUtils.ENTANDO_APP_ROLE)));

        Ingress appIngress = helper.getClient().extensions().ingresses()
                .inNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .withName(EntandoAppE2ETestHelper.TEST_APP_NAME + "-ingress")
                .get();

        List<HTTPIngressPath> entandoAppIngressPaths = appIngress.getSpec().getRules().get(0).getHttp().getPaths();
        assertTrue(entandoAppIngressPaths.stream().anyMatch(p -> p.getPath().equals("/avatarPlugin")));
    }

    //TODO get rid of this once we deploy K8S with the operator
    public void ensureInfrastructureConnectionConfig() {
        helper.entandoPlugins().loadDefaultCapabilitiesConfigMap()
                .addToData(InfrastructureConfig.DEFAULT_CLUSTER_INFRASTRUCTURE_NAMESPACE_KEY, CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .addToData(InfrastructureConfig.DEFAULT_CLUSTER_INFRASTRUCTURE_NAME_KEY, CLUSTER_INFRASTRUCTURE_NAME)
                .done();
        ResourceReference infrastructureToUse = new ResourceReference(CLUSTER_INFRASTRUCTURE_NAMESPACE, CLUSTER_INFRASTRUCTURE_NAME);
        delete(helper.getClient().configMaps())
                .named(InfrastructureConfig.connectionConfigMapNameFor(infrastructureToUse))
                .fromNamespace(CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .waitingAtMost(20, SECONDS);
        String hostName = "http://" + CLUSTER_INFRASTRUCTURE_NAME + "." + helper.getDomainSuffix();
        helper.getClient().configMaps()
                .inNamespace(CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .createNew()
                .withNewMetadata()
                .withName(InfrastructureConfig.connectionConfigMapNameFor(infrastructureToUse))
                .endMetadata()
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_CLIENT_ID_KEY, CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc")
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_INTERNAL_URL_KEY, hostName + "/k8s")
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_EXTERNAL_URL_KEY, hostName + "/k8s")
                .done();
    }
}
