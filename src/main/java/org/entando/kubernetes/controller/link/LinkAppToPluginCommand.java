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

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.creators.IngressPathCreator;
import org.entando.kubernetes.controller.creators.ServiceCreator;
import org.entando.kubernetes.controller.k8sclient.ServiceClient;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.spi.ServiceResult;
import org.entando.kubernetes.model.AbstractServerStatus;
import org.entando.kubernetes.model.WebServerStatus;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.plugin.Permission;

public class LinkAppToPluginCommand {

    private static final String COMPONENT_MANAGER_QUALIFIER = "de";
    private final EntandoAppPluginLink entandoAppPluginLink;
    private final IngressPathCreator ingressCreator;
    private final EntandoLinkedPluginIngressing entandoLinkedPluginIngressing;
    private final ServiceCreator serviceCreator;
    private final WebServerStatus status = new WebServerStatus("link");

    public LinkAppToPluginCommand(EntandoAppPluginLink entandoAppPluginLink, EntandoLinkedPluginIngressing entandoLinkedPluginIngressing) {
        this.entandoAppPluginLink = entandoAppPluginLink;
        this.serviceCreator = new ServiceCreator(entandoAppPluginLink,
                entandoLinkedPluginIngressing.getEntandoPluginDeploymentResult().getService());
        this.ingressCreator = new IngressPathCreator(entandoAppPluginLink);
        this.entandoLinkedPluginIngressing = entandoLinkedPluginIngressing;
    }

    public ServiceResult execute(SimpleK8SClient k8sClient, SimpleKeycloakClient keycloakClient) {
        Service service = prepareReachableService(k8sClient.services());
        status.setServiceStatus(service.getStatus());
        k8sClient.entandoResources().updateStatus(entandoAppPluginLink, status);
        Ingress ingress = addMissingIngressPaths(k8sClient, service);
        status.setIngressStatus(ingress.getStatus());
        k8sClient.entandoResources().updateStatus(entandoAppPluginLink, status);
        grantAppAccessToPlugin(k8sClient, keycloakClient);
        //TODO wait for result - when new ingress path is available
        return new ServiceDeploymentResult(service, ingress);
    }

    private void grantAppAccessToPlugin(SimpleK8SClient k8sClient, SimpleKeycloakClient keycloakClient) {
        String pluginClientId = entandoAppPluginLink.getSpec().getEntandoPluginName() + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER;
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources()
                .findKeycloak(entandoLinkedPluginIngressing.getEntandoApp());
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.assignRoleToClientServiceAccount(
                KubeUtils.ENTANDO_KEYCLOAK_REALM,
                entandoAppPluginLink.getSpec().getEntandoAppName() + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER,
                new Permission(pluginClientId,
                        KubeUtils.ENTANDO_APP_ROLE)
        );
        keycloakClient.assignRoleToClientServiceAccount(
                KubeUtils.ENTANDO_KEYCLOAK_REALM,
                entandoAppPluginLink.getSpec().getEntandoAppName() + "-" + COMPONENT_MANAGER_QUALIFIER,
                new Permission(pluginClientId,
                        KubeUtils.ENTANDO_APP_ROLE)
        );
    }

    private Ingress addMissingIngressPaths(SimpleK8SClient k8sClient, Service service) {
        return ingressCreator.addMissingHttpPaths(k8sClient.ingresses(), entandoLinkedPluginIngressing,
                entandoLinkedPluginIngressing.getEntandoAppDeploymentResult().getIngress(), service);
    }

    private Service prepareReachableService(ServiceClient services) {
        if (entandoAppPluginLink.getSpec().getEntandoAppNamespace().equals(entandoAppPluginLink.getSpec().getEntandoPluginNamespace())) {
            return serviceCreator.getService();
        } else {
            return serviceCreator.newDelegatingService(services, entandoLinkedPluginIngressing);
        }
    }

    public AbstractServerStatus getStatus() {
        return status;
    }
}
