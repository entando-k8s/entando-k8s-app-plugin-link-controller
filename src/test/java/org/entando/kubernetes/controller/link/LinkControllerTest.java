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

package org.entando.kubernetes.controller.link;

import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.Collections;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.capability.ProvidedCapabilityBuilder;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.common.Permission;
import org.entando.kubernetes.model.common.ServerStatus;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkBuilder;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.SourceLink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure"), @Tag("inner-hexagon")})
@Feature("As a deployer, I would like to link an EntandoApp to an EntandoPlugin so that I may access it from the app")
@Issue("ENG-2284")
@SourceLink("LinkControllerTest.java")
class LinkControllerTest extends LinkControllerTestBase {

    public static final String MY_CM_SSO_CLIENT_ID = "my-cm-sso-client-id";
    public static final String MY_APP_SSO_CLIENT_ID = "my-app-sso-client-id";

    @Test
    @Description("Should expose a given EntandoPlugin on the Ingress of EntandoApp in the same namespace")
    void shouldLinkInSameNamespace() {
        step("Given I have made an SSO capability avaible", this::prepareSsoCapability);
        step("And I have deployed an EntandoApp", this::prepareEntandoApp);
        step("And I have deployed an EntandoPlugin", () -> {
            entandoPlugin = getClient().entandoResources().createOrPatchEntandoResource(new EntandoPluginBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-plugin")
                    .endMetadata()
                    .withNewSpec()
                    .withDbms(DbmsVendor.EMBEDDED)
                    .withImage("entando/my-plugin:6.1.2")
                    .withHealthCheckPath("management/health")
                    .withIngressPath("/my-plugin-context")
                    .endSpec()
                    .build());
            getClient().entandoResources().createOrPatchEntandoResource(entandoPlugin);
            putExposedServerStatus(entandoPlugin, "myhost.cm", 8081,
                    new ServerStatus(NameUtils.MAIN_QUALIFIER).withSsoClientId("my-plugin-sso-client-id").withSsoRealm("my-realm")
                            .addToWebContexts(NameUtils.DEFAULT_SERVER_QUALIFIER, entandoPlugin.getSpec().getIngressPath()));
        });
        step("When I link the EntandoApp to the EntandoPlugin", this::processLink);
        step("Then the main web context path of the EntandoPlugin is exposed on the EntandoApps' ingress", () -> {
            Ingress ingress = getClient().ingresses().loadIngress(MY_NAMESPACE, NameUtils.standardIngressName(entandoApp));
            assertThat(theHttpPath("/my-plugin-context").on(ingress).getBackend().getService().getName()).isEqualTo("my-plugin-service");
            assertThat(theHttpPath("/my-plugin-context").on(ingress).getBackend().getService().getPort().getNumber()).isEqualTo(8081);
        });
        step("And the SSO Client for boths the EntandoAppServer and EntandoComponentManager have been given the 'entandApp' role on the "
                        + "EntandoPlugin's SSO cleint",
                () -> {
                    ArgumentCaptor<Permission> permissionCaptor = ArgumentCaptor.forClass(Permission.class);
                    verify(keycloakClient)
                            .assignRoleToClientServiceAccount(eq("my-realm"), eq(MY_APP_SSO_CLIENT_ID), permissionCaptor.capture());
                    assertThat(permissionCaptor.getValue().getRole()).isEqualTo(AppToPluginLinkable.ENTANDO_APP_ROLE);
                    verify(keycloakClient)
                            .assignRoleToClientServiceAccount(eq("my-realm"), eq(MY_CM_SSO_CLIENT_ID), permissionCaptor.capture());
                    assertThat(permissionCaptor.getValue().getRole()).isEqualTo(AppToPluginLinkable.ENTANDO_APP_ROLE);
                });
    }

    @Test
    @Description("Should expose a given EntandoPlugin on the Ingress of EntandoApp in the same namespace")
    void shouldLinkToPluginInDifferentNamespace() {
        step("Given I have made an SSO capability avaible", this::prepareSsoCapability);
        step("And I have deployed an EntandoApp", this::prepareEntandoApp);
        step("And I have deployed an EntandoPlugin", () -> {
            entandoPlugin = getClient().entandoResources().createOrPatchEntandoResource(new EntandoPluginBuilder()
                    .withNewMetadata()
                    .withNamespace("plugin-namespace")
                    .withName("my-plugin")
                    .endMetadata()
                    .withNewSpec()
                    .withDbms(DbmsVendor.EMBEDDED)
                    .withImage("entando/my-plugin:6.1.2")
                    .withHealthCheckPath("management/health")
                    .withIngressPath("/my-plugin-context")
                    .endSpec()
                    .build());
            getClient().entandoResources().createOrPatchEntandoResource(entandoPlugin);
            putExposedServerStatus(entandoPlugin, "myhost.cm", 8081,
                    new ServerStatus(NameUtils.MAIN_QUALIFIER).withSsoClientId("my-plugin-sso-client-id").withSsoRealm("my-realm")
                            .addToWebContexts(NameUtils.DEFAULT_SERVER_QUALIFIER, entandoPlugin.getSpec().getIngressPath()));
        });
        step("When I link the EntandoApp to the EntandoPlugin", this::processLink);
        step("Then the main web context path of the EntandoPlugin is exposed on the EntandoApps' ingress", () -> {
            Ingress ingress = getClient().ingresses().loadIngress(MY_NAMESPACE, NameUtils.standardIngressName(entandoApp));
            assertThat(theHttpPath("/my-plugin-context").on(ingress).getBackend().getService().getName())
                    .isEqualTo("my-app-ingress-to-my-plugin-service");
            assertThat(theHttpPath("/my-plugin-context").on(ingress).getBackend().getService().getPort().getNumber()).isEqualTo(8081);
        });
        step("And the SSO Client for boths the EntandoAppServer and EntandoComponentManager have been given the 'entandApp' role on the "
                        + "EntandoPlugin's SSO cleint",
                () -> {
                    ArgumentCaptor<Permission> permissionCaptor = ArgumentCaptor.forClass(Permission.class);
                    verify(keycloakClient)
                            .assignRoleToClientServiceAccount(eq("my-realm"), eq(MY_APP_SSO_CLIENT_ID), permissionCaptor.capture());
                    assertThat(permissionCaptor.getValue().getRole()).isEqualTo(AppToPluginLinkable.ENTANDO_APP_ROLE);
                    verify(keycloakClient)
                            .assignRoleToClientServiceAccount(eq("my-realm"), eq(MY_CM_SSO_CLIENT_ID), permissionCaptor.capture());
                    assertThat(permissionCaptor.getValue().getRole()).isEqualTo(AppToPluginLinkable.ENTANDO_APP_ROLE);
                });
    }

    private void processLink() {
        step("I schedule the completion of the EntandoApp custom resource", () ->
                getScheduler().submit(() -> {
                    this.entandoApp = getClient().entandoResources().updatePhase(this.entandoApp, EntandoDeploymentPhase.SUCCESSFUL);
                }));
        step("I schedule the completion of the EntandoPlugin custom resource", () ->
                getScheduler().submit(() -> {
                    this.entandoPlugin = getClient().entandoResources().updatePhase(this.entandoPlugin, EntandoDeploymentPhase.SUCCESSFUL);
                }));
        step("And I create the EntandoAppPluginLink custom resource", () -> {
            this.link = getClient().entandoResources()
                    .createOrPatchEntandoResource(new EntandoAppPluginLinkBuilder()
                            .withNewMetadata()
                            .withNamespace(MY_NAMESPACE)
                            .withName("my-link")
                            .endMetadata()
                            .withNewSpec()
                            .withEntandoApp(MY_NAMESPACE, MY_APP)
                            .withEntandoPlugin(entandoPlugin.getMetadata().getNamespace(), entandoPlugin.getMetadata().getName())
                            .endSpec()
                            .build());
            runControllerAgainstCustomResource(link);
            attachKubernetesResource("EntandoAppPluginLink", this.link);
        });
    }

    private void prepareEntandoApp() {
        entandoApp = new EntandoAppBuilder()
                .withNewMetadata()
                .withNamespace(MY_NAMESPACE)
                .withName(MY_APP)
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.EMBEDDED)
                .endSpec()
                .build();
        entandoApp.getStatus().putServerStatus(
                new ServerStatus(NameUtils.SSO_QUALIFIER, sso.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get()));
        getClient().entandoResources().createOrPatchEntandoResource(entandoApp);
        putExposedServerStatus(entandoApp, "myhost.com", 8080,
                new ServerStatus(AppToPluginLinkable.COMPONENT_MANAGER_QUALIFIER).withSsoClientId(MY_CM_SSO_CLIENT_ID)
                        .withSsoRealm("my-realm")
                        .addToWebContexts(NameUtils.DEFAULT_SERVER_QUALIFIER, "/digital-exchange"));
        putExposedServerStatus(entandoApp, "myhost.com", 8080,
                new ServerStatus(NameUtils.MAIN_QUALIFIER).withSsoClientId(MY_APP_SSO_CLIENT_ID).withSsoRealm("my-realm")
                        .addToWebContexts(NameUtils.DEFAULT_SERVER_QUALIFIER, "/entando-de-app"));
        attachKubernetesResource("EntandoApp", this.entandoApp);
    }

    private void prepareSsoCapability() {
        sso = getClient().entandoResources().createOrPatchEntandoResource(new ProvidedCapabilityBuilder()
                .withNewMetadata()
                .withNamespace(MY_NAMESPACE)
                .withName("my-sso")
                .endMetadata()
                .withNewSpec()
                .withCapability(StandardCapability.SSO)
                .endSpec()
                .build());
        putExternalServerStatus(sso, "mykeycloak.com", 8080, "/auth", Collections.singletonMap("defaultRealm", "my-default-realm"));
        attachKubernetesResource("SSO Capability", sso);
    }

}
