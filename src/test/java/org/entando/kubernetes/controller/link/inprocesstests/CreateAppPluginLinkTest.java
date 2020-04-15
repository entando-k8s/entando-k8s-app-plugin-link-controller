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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.api.model.extensions.IngressStatus;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.link.EntandoAppPluginLinkController;
import org.entando.kubernetes.controller.link.EntandoComponentInstallerService;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.link.EntandoAppPluginLinkBuilder;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")

public class CreateAppPluginLinkTest implements InProcessTestUtil, FluentTraversals, VariableReferenceAssertions {

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
    private final EntandoPlugin entandoPlugin = buildTestEntandoPlugin();
    private final EntandoKeycloakServer keycloakServer = newEntandoKeycloakServer();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    @Mock
    private EntandoComponentInstallerService entandoComponentInstallerService;

    private EntandoAppPluginLinkController linkController;

    @BeforeEach
    public void putAppAndPlugin() {
        client.entandoResources().putEntandoApp(entandoApp);
        client.entandoResources().putEntandoPlugin(entandoPlugin);
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        this.linkController = new EntandoAppPluginLinkController(client, keycloakClient, entandoComponentInstallerService);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, MY_APP_NAMESPACE);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, MY_LINK);
    }

    @Test
    public void testService() throws IOException, InterruptedException {
        //Given that K8S is up and receiving Ingress requests
        IngressStatus ingressStatus = new IngressStatus();
        lenient().when(client.ingresses()
                .addHttpPath(any(Ingress.class), argThat(matchesHttpPath(MY_PLUGIN_CONTEXT_PATH)), anyMap()))
                .then(answerWithIngressStatus(ingressStatus));
        lenient().when(client.services()
                .createOrReplaceService(eq(entandoPlugin), argThat(matchesName(MY_PLUGIN_SERVER_SERVICE))))
                .then(answerWithClusterIp(CLUSTER_IP));
        when(entandoComponentInstallerService.isPluginHealthy(anyString())).thenReturn(true);
        //And I have an app and a plugin
        new DeployCommand<>(new FakeDeployable(entandoApp)).execute(client, Optional.of(keycloakClient));
        new DeployCommand<>(new FakeDeployable(entandoPlugin)).execute(client, Optional.of(keycloakClient));

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
                MY_APP + "-" + KubeUtils.DEFAULT_INGRESS_SUFFIX);
        verify(client.ingresses()).createIngress(eq(entandoApp), ingressArgumentCaptor.capture());
        await().ignoreExceptions().pollInterval(100, TimeUnit.MILLISECONDS).atMost(20, TimeUnit.SECONDS).until(() -> {
            verify(client.entandoResources()).loadServiceResult(entandoApp);
            verify(client.entandoResources()).loadServiceResult(entandoPlugin);
            return true;
        });
        Ingress theIngress = ingressArgumentCaptor.getValue();
        //And an HTTP host  that reflects the hostname name of the EntandoPlugin
        assertThat(theHostOn(theIngress), is("myapp.192.168.0.100.nip.io"));
        //And an IngressPath that reflects the webcontext of the EntandoPlugin
        //that is mapped to the previously created HTTP service
        assertThat(theBackendFor(MY_PLUGIN_CONTEXT_PATH).on(theIngress).getServicePort().getIntVal(), is(PORT_8081));
        assertThat(theBackendFor(MY_PLUGIN_CONTEXT_PATH).on(theIngress).getServiceName(), is(MY_APP_INGRESS_TO_MY_PLUGIN_SERVER_SERVICE));
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
                .loadIngress(eq(entandoApp.getMetadata().getNamespace()), eq(MY_APP + "-" + KubeUtils.DEFAULT_INGRESS_SUFFIX));

        //And K8S was instructed to update the status of the EntandoPlugin with the status of the ingress
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoAppPluginLink), argThat(matchesIngressStatus(ingressStatus)));
        verify(this.entandoComponentInstallerService)
                .isPluginHealthy("https://myapp.192.168.0.100.nip.io" + MY_PLUGIN_CONTEXT_PATH + "/actuator/health");
        verify(keycloakClient)
                .assignRoleToClientServiceAccount(eq(ENTANDO_KEYCLOAK_REALM), eq(MY_APP + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER),
                        argThat(matches(new Permission(MY_PLUGIN_SERVER, KubeUtils.ENTANDO_APP_ROLE))));
        verify(keycloakClient)
                .assignRoleToClientServiceAccount(eq(ENTANDO_KEYCLOAK_REALM),
                        eq(MY_APP + "-" + COMPONENT_MANAGER_QUALIFIER),
                        argThat(matches(new Permission(MY_PLUGIN_SERVER, KubeUtils.ENTANDO_APP_ROLE))));
    }

    @Test
    public void testLinkCleanup() {
        //Given that K8S is up and receiving Ingress requests
        IngressStatus ingressStatus = new IngressStatus();
        lenient().when(client.ingresses()
                .addHttpPath(any(Ingress.class), argThat(matchesHttpPath(MY_PLUGIN_CONTEXT_PATH)), anyMap()))
                .then(answerWithIngressStatus(ingressStatus));
        lenient().when(client.services()
                .createOrReplaceService(eq(entandoPlugin), argThat(matchesName(MY_PLUGIN_SERVER_SERVICE))))
                .then(answerWithClusterIp(CLUSTER_IP));
        when(entandoComponentInstallerService.isPluginHealthy(anyString())).thenReturn(true);
        //And I have an app and a plugin
        new DeployCommand<>(new FakeDeployable(entandoApp)).execute(client, Optional.of(keycloakClient));
        new DeployCommand<>(new FakeDeployable(entandoPlugin)).execute(client, Optional.of(keycloakClient));

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

        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.DELETED.name());
        linkController.onStartup(new StartupEvent());
        ArgumentCaptor<EntandoAppPluginLink> linkArgumentCaptor = ArgumentCaptor.forClass(EntandoAppPluginLink.class);
        verify(client.entandoResources()).removeFinalizer(linkArgumentCaptor.capture());

        EntandoAppPluginLink link = linkArgumentCaptor.getValue();
        assertThat(link.getMetadata().getName(), is(MY_LINK));
        assertThat(link.getMetadata().getFinalizers().isEmpty(), is(true));

        ArgumentCaptor<Ingress> ingressArgumentCaptor = ArgumentCaptor.forClass(Ingress.class);
        ArgumentCaptor<HTTPIngressPath> httpIngressPathArgumentCaptorCaptor = ArgumentCaptor.forClass(HTTPIngressPath.class);
        verify(client.ingresses()).removeHttpPath(ingressArgumentCaptor.capture(), httpIngressPathArgumentCaptorCaptor.capture());

        Ingress ingress = ingressArgumentCaptor.getValue();
        assertThat(ingress.getMetadata().getLabels().get("EntandoApp"), is(MY_APP));

        HTTPIngressPath path = httpIngressPathArgumentCaptorCaptor.getValue();
        assertThat(path.getPath(), is(MY_PLUGIN_CONTEXT_PATH));
        assertThat(path.getBackend().getServicePort().getIntVal(), is(8081));
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
