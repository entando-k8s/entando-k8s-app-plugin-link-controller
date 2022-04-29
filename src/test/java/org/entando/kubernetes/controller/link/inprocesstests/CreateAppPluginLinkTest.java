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

package org.entando.kubernetes.controller.link.inprocesstests;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.link.EntandoAppPluginLinkController;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.examples.SampleExposedDeploymentResult;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.support.command.IngressingDeployCommand;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkBuilder;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.Permission;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.entando.kubernetes.test.componenttest.InProcessTestUtil;
import org.entando.kubernetes.test.componenttest.argumentcaptors.NamedArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")
//Because Sonar doesn't pick up custom captors
@SuppressWarnings("java:S6073")
class CreateAppPluginLinkTest implements InProcessTestUtil, FluentTraversals, VariableReferenceAssertions {

    public static final String MY_LINK = "my-link";
    private static final String MY_PLUGIN_SERVER = MY_PLUGIN + "-server";
    private static final String MY_PLUGIN_SERVER_SERVICE = MY_PLUGIN_SERVER + "-service";
    private static final String SERVER_PORT = "server-port";
    private static final String CONFSVC_PORT = "confsvc-port";
    private static final String CLUSTER_IP = "172.30.12.12";
    private static final String MY_PLUGIN_CONTEXT_PATH = "/myplugin";
    private static final String MY_APP_INGRESS_TO_MY_PLUGIN_SERVER_SERVICE = MY_APP + "-ingress-to-" + MY_PLUGIN_SERVER + "-service";
    private static final int PORT_8081 = 8081;
    private static final int PORT_8083 = 8083;
    private static final String COMPONENT_MANAGER_QUALIFIER = "de";
    private final EntandoApp entandoApp = newTestEntandoApp();
    private final EntandoPlugin entandoPlugin = newTestEntandoPlugin();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;

    private EntandoAppPluginLinkController linkController;

    @BeforeEach
    public void putAppAndPlugin() {
        emulateClusterInfrastuctureDeployment(client);
        emulateKeycloakDeployment(client);
        this.linkController = new EntandoAppPluginLinkController(client, keycloakClient);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, MY_APP_NAMESPACE);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, MY_LINK);
    }

    @Test
    void testBasicFlow() {
        //Given that K8S is up and receiving Ingress requests
        client.entandoResources().createOrPatchEntandoResource(entandoApp);
        client.entandoResources().createOrPatchEntandoResource(entandoPlugin);
        IngressStatus ingressStatus = new IngressStatus();
        lenient().when(client.ingresses()
                        .addHttpPath(any(Ingress.class), argThat(matchesHttpPath(MY_PLUGIN_CONTEXT_PATH)), anyMap()))
                .then(answerWithIngressStatus(ingressStatus));
        lenient().when(client.services()
                        .createOrReplaceService(eq(entandoPlugin), argThat(matchesName(MY_PLUGIN_SERVER_SERVICE))))
                .then(answerWithClusterIp(CLUSTER_IP));
        //And I have an app and a plugin
        new IngressingDeployCommand<SampleExposedDeploymentResult>(new FakeDeployable<>(entandoApp)).execute(client, keycloakClient);
        new IngressingDeployCommand<SampleExposedDeploymentResult>(new FakeDeployable<>(entandoPlugin)).execute(client, keycloakClient);

        //When I link the plugin to the app
        EntandoAppPluginLink newEntandoAppPluginLink = new EntandoAppPluginLinkBuilder()
                .withNewMetadata()
                .withNamespace(MY_APP_NAMESPACE)
                .withName(MY_LINK)
                .endMetadata()
                .withNewSpec()
                .withEntandoApp(MY_APP_NAMESPACE, MY_APP)
                .withEntandoPlugin(MY_PLUGIN_NAMESPACE, MY_PLUGIN)
                .endSpec()
                .build();
        client.entandoResources().createOrPatchEntandoResource(newEntandoAppPluginLink);
        linkController.onStartup(new StartupEvent());

        //Then K8S was instructed to create an Ingress for the Plugin JEE Server
        NamedArgumentCaptor<Ingress> ingressArgumentCaptor = forResourceNamed(Ingress.class,
                MY_APP + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX);
        verify(client.ingresses()).createIngress(eq(entandoApp), ingressArgumentCaptor.capture());
        await().ignoreExceptions().pollInterval(100, TimeUnit.MILLISECONDS).atMost(20, TimeUnit.SECONDS).until(() -> {
            verify(client.entandoResources()).loadExposedService(entandoApp);
            verify(client.entandoResources()).loadExposedService(entandoPlugin);
            return true;
        });
        Ingress theIngress = ingressArgumentCaptor.getValue();
        //And an HTTP host  that reflects the hostname name of the EntandoPlugin
        assertThat(theHostOn(theIngress), is("myapp.192.168.0.100.nip.io"));
        //And an IngressPath that reflects the webcontext of the EntandoPlugin
        //that is mapped to the previously created HTTP service
        assertThat(theBackendFor(MY_PLUGIN_CONTEXT_PATH).on(theIngress).getService().getPort().getNumber(), is(PORT_8081));
        assertThat(theBackendFor(MY_PLUGIN_CONTEXT_PATH).on(theIngress).getService().getName(),
                is(MY_APP_INGRESS_TO_MY_PLUGIN_SERVER_SERVICE));
        assertThat(theIngress.getMetadata().getAnnotations().get(MY_LINK + "-path"), is(MY_PLUGIN_CONTEXT_PATH));

        //And a delegating service was created in the App's namespace
        NamedArgumentCaptor<Service> serviceArgumentCaptor = forResourceNamed(Service.class, MY_APP_INGRESS_TO_MY_PLUGIN_SERVER_SERVICE);
        verify(client.services()).createOrReplaceService(eq(newEntandoAppPluginLink), serviceArgumentCaptor.capture());
        assertThat(thePortNamed(SERVER_PORT).on(serviceArgumentCaptor.getValue()).getPort(), is(PORT_8081));
        assertThat(serviceArgumentCaptor.getValue().getMetadata().getLabels().get(ENTANDO_APP_PLUGIN_LINK_LABEL_NAME), is(MY_LINK));
        assertThat(serviceArgumentCaptor.getValue().getMetadata().getOwnerReferences().get(0).getName(), is(MY_LINK));
        assertThat(serviceArgumentCaptor.getValue().getMetadata().getOwnerReferences().get(0).getKind(),
                is(ENTANDO_APP_PLUGIN_LINK_LABEL_NAME));

        NamedArgumentCaptor<Endpoints> endpointsArgumentCaptor = forResourceNamed(Endpoints.class,
                MY_APP_INGRESS_TO_MY_PLUGIN_SERVER_SERVICE);
        verify(client.services()).createOrReplaceEndpoints(eq(newEntandoAppPluginLink), endpointsArgumentCaptor.capture());
        assertThat(thePortNamed(SERVER_PORT).on(endpointsArgumentCaptor.getValue()).getPort(), is(PORT_8081));
        assertThat(endpointsArgumentCaptor.getValue().getSubsets().get(0).getAddresses().get(0).getIp(), is(CLUSTER_IP));
        assertThat(endpointsArgumentCaptor.getValue().getMetadata().getLabels().get(ENTANDO_APP_PLUGIN_LINK_LABEL_NAME), is(MY_LINK));
        assertThat(endpointsArgumentCaptor.getValue().getMetadata().getOwnerReferences().get(0).getName(), is(MY_LINK));
        assertThat(endpointsArgumentCaptor.getValue().getMetadata().getOwnerReferences().get(0).getKind(),
                is(ENTANDO_APP_PLUGIN_LINK_LABEL_NAME));

        //And the EntandoApp's Ingress state was reloaded from K8S
        verify(client.ingresses(), atLeastOnce())
                .loadIngress(entandoApp.getMetadata().getNamespace(), MY_APP + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX);

        //And K8S was instructed to update the status of the EntandoPlugin with the status of the ingress
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoAppPluginLink), argThat(matchesIngressStatus(ingressStatus)));
        verify(keycloakClient)
                .assignRoleToClientServiceAccount(eq(ENTANDO_KEYCLOAK_REALM), eq(MY_APP + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER),
                        argThat(matches(new Permission(MY_PLUGIN_SERVER, KubeUtils.ENTANDO_APP_ROLE))));
        verify(keycloakClient)
                .assignRoleToClientServiceAccount(eq(ENTANDO_KEYCLOAK_REALM),
                        eq(MY_APP + "-" + COMPONENT_MANAGER_QUALIFIER),
                        argThat(matches(new Permission(MY_PLUGIN_SERVER, KubeUtils.ENTANDO_APP_ROLE))));
    }

    @Test
    void testSharedHostname() {
        //Given that K8S is up and receiving Ingress requests
        IngressStatus ingressStatus = new IngressStatus();
        lenient().when(client.ingresses()
                        .addHttpPath(any(Ingress.class), argThat(matchesHttpPath(MY_PLUGIN_CONTEXT_PATH)), anyMap()))
                .then(answerWithIngressStatus(ingressStatus));
        final EntandoPlugin newPlugin = new EntandoPluginBuilder(this.entandoPlugin).editSpec().withIngressHostName("shared.com").endSpec()
                .build();
        final EntandoApp newApp = new EntandoAppBuilder(this.entandoApp).editSpec().withIngressHostName("shared.com").endSpec().build();
        client.entandoResources().createOrPatchEntandoResource(newApp);
        client.entandoResources().createOrPatchEntandoResource(newPlugin);
        lenient().when(client.services()
                        .createOrReplaceService(eq(newPlugin), argThat(matchesName(MY_PLUGIN_SERVER_SERVICE))))
                .then(answerWithClusterIp(CLUSTER_IP));
        //And I have an app and a plugin  that share a hostname
        new IngressingDeployCommand<SampleExposedDeploymentResult>(new FakeDeployable<>(newApp)).execute(client, keycloakClient);
        new IngressingDeployCommand<SampleExposedDeploymentResult>(new FakeDeployable<>(newPlugin)).execute(client, keycloakClient);

        //When I link the plugin to the app
        EntandoAppPluginLink newEntandoAppPluginLink = new EntandoAppPluginLinkBuilder()
                .withNewMetadata()
                .withNamespace(MY_APP_NAMESPACE)
                .withName(MY_LINK)
                .endMetadata()
                .withNewSpec()
                .withEntandoApp(MY_APP_NAMESPACE, MY_APP)
                .withEntandoPlugin(MY_PLUGIN_NAMESPACE, MY_PLUGIN)
                .endSpec()
                .build();
        client.entandoResources().createOrPatchEntandoResource(newEntandoAppPluginLink);
        linkController.onStartup(new StartupEvent());

        //Then K8S was instructed to create an Ingress for the Plugin JEE Server
        NamedArgumentCaptor<Ingress> pluginIngressCaptor = forResourceNamed(Ingress.class,
                MY_PLUGIN + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX);
        verify(client.ingresses()).createIngress(eq(newPlugin), pluginIngressCaptor.capture());
        Ingress pluginIngress = pluginIngressCaptor.getValue();
        NamedArgumentCaptor<Ingress> appIngressCaptor = forResourceNamed(Ingress.class,
                MY_APP + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX);
        verify(client.ingresses()).createIngress(eq(newApp), appIngressCaptor.capture());
        Ingress appIngress = appIngressCaptor.getValue();
        assertThat(pluginIngress.getSpec().getRules().get(0).getHttp().getPaths().size(), is(1));
        //And no additional paths were added to the app's ingress
        assertThat(appIngress.getSpec().getRules().get(0).getHttp().getPaths().size(), is(1));
        assertThat(appIngress.getSpec().getRules().get(0).getHost(), is(pluginIngress.getSpec().getRules().get(0).getHost()));

    }

    private ArgumentMatcher<Permission> matches(Permission permission) {
        return p -> p.getRole().equals(permission.getRole()) && p.getClientId().equals(permission.getClientId());
    }

    private Answer<Object> answerWithIngressStatus(IngressStatus ingressStatus) {
        return invocationOnMock -> {
            Ingress ingress = (Ingress) invocationOnMock.callRealMethod();
            ingress.setStatus(ingressStatus);
            return ingress;
        };
    }

    private ArgumentMatcher<HTTPIngressPath> matchesHttpPath(String myPluginContextPath) {
        return httpIngressPath -> httpIngressPath.getPath().equals(myPluginContextPath);
    }

    private Answer<Service> answerWithClusterIp(String clusterIp) {
        return invocationOnMock -> {
            Service service = (Service) invocationOnMock.callRealMethod();
            if (service != null) {
                service.getSpec().setClusterIP(clusterIp);
            }
            return service;
        };
    }

}
