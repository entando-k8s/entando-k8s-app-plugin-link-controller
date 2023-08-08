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

package org.entando.kubernetes.controller.link.support;

import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isBlank;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.link.tenant.SsoConnectionInfoTenantAware;
import org.entando.kubernetes.controller.link.tenant.TenantConfigurationService;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.support.client.ServiceClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.creators.IngressPathCreator;
import org.entando.kubernetes.controller.support.creators.ServiceCreator;
import org.entando.kubernetes.model.common.CustomResourceReference;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoMultiTenancy;
import org.entando.kubernetes.model.common.ServerStatus;

public class LinkCommand {
    private static final Logger LOGGER = Logger.getLogger(LinkCommand.class.getName());

    private final EntandoCustomResource linkResource;
    private LinkInfo linkInfo;
    private final IngressPathCreator ingressCreator;
    private final ServerStatus status;
    private final Linkable linkable;
    private final Linkable customIngressLinkable;
    private Ingress sourceIngress;

    public LinkCommand(Linkable linkable, Linkable customIngressLinkable) {
        this.linkable = linkable;
        this.customIngressLinkable = customIngressLinkable;
        this.linkResource = linkable.getLinkResource();
        this.ingressCreator = new IngressPathCreator(linkable.getLinkResource());
        this.status = new ServerStatus(NameUtils.MAIN_QUALIFIER).withOriginatingCustomResource(linkResource);
    }

    static SerializedEntandoResource resolveResource(SimpleK8SClient<?> k8SClient, EntandoCustomResource linkResource,
            CustomResourceReference source) {
        return k8SClient.entandoResources()
                .loadCustomResource(source.getApiVersion(), source.getKind(),
                        source.getNamespace().orElse(linkResource.getMetadata().getNamespace()),
                        source.getName());
    }

    public ServerStatus execute(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient, String tenantCode) {
        this.linkInfo = new LinkInfo(linkable, customIngressLinkable,
                resolveResource(k8sClient, linkable.getLinkResource(), linkable.getSource()),
                resolveResource(k8sClient, linkable.getLinkResource(), linkable.getTarget()),
                tenantCode);
        status.withOriginatingControllerPod(k8sClient.entandoResources().getNamespace(), EntandoOperatorSpiConfig.getControllerPodName());
        if (this.linkable.getAccessStrategy() == AccessStrategy.SSO) {
            if (usingSameHostname(k8sClient)) {
                status.setServiceName(linkInfo.getSourceServiceName());
                status.setIngressName(linkInfo.getSourceIngressName());
                k8sClient.entandoResources().updateStatus(linkResource, status);
            } else {
                Service service = prepareReachableTargetService(k8sClient);
                status.setServiceName(service.getMetadata().getName());
                k8sClient.entandoResources().updateStatus(linkResource, status);
                Ingress ingress = addMissingIngressPaths(k8sClient, service);
                status.setIngressName(ingress.getMetadata().getName());
                k8sClient.entandoResources().updateStatus(linkResource, status);
            }
            grantSourceAccessToTarget(keycloakClient, k8sClient);
        }
        return this.status;
        //TODO wait for result - when new ingress path is available
    }

    private boolean usingSameHostname(SimpleK8SClient<?> client) {
        Optional<Ingress> targetIngress = linkInfo.getTargetIngressName()
                .map(ingressName -> client.ingresses().loadIngress(linkInfo.getTargetNamespace(), ingressName));
        return targetIngress
                .map(ti -> ti.getSpec().getRules().get(0).getHost().equals(getSourceIngress(client).getSpec().getRules().get(0).getHost()))
                .orElse(Boolean.FALSE);
    }

    private void grantSourceAccessToTarget(SimpleKeycloakClient keycloakClient, SimpleK8SClient<?> client) {
        SsoConnectionInfo keycloakConnectionConfig;
        String realm;
        if (isPrimary(linkInfo.getTenantCode())) {
            keycloakConnectionConfig = new ProvidedSsoCapability(client.entandoResources()
                    .loadCapabilityProvisioningResult(linkInfo.getSsoServiceStatus()));
            realm = linkInfo.getSsoRealm();
        } else {
            keycloakConnectionConfig = new SsoConnectionInfoTenantAware(linkInfo.getTenantCode(),
                    linkable.getLinkResource(), client);
            realm = keycloakConnectionConfig.getDefaultRealm().orElseThrow(() -> new IllegalStateException(
                    String.format("Missing realm for tenant: '%s'", linkInfo.getTenantCode())));
        }
        keycloakClient.login(keycloakConnectionConfig.getBaseUrlToUse(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        linkInfo.getPermissions().forEach(linkPermission ->
                keycloakClient.assignRoleToClientServiceAccount(
                        realm,
                        linkPermission.getSourceClientId(),
                        linkPermission));
    }

    private Ingress addMissingIngressPaths(SimpleK8SClient<?> k8sClient, Service service) {
        Ingress ingress = getSourceIngress(k8sClient);
        return ingressCreator.addMissingHttpPaths(k8sClient.ingresses(), linkInfo.getPathsOnPorts(), ingress, service, status);
    }

    private Ingress getSourceIngress(SimpleK8SClient<?> k8sClient) {
        if (isPrimary(linkInfo.getTenantCode())) {
            this.sourceIngress = Objects.requireNonNullElseGet(this.sourceIngress, () -> k8sClient.ingresses()
                    .loadIngress(linkInfo.getSourceNamespace(), linkInfo.getSourceIngressName()));
        } else {
            TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, linkable.getLinkResource());
            this.sourceIngress = Objects.requireNonNullElseGet(this.sourceIngress, () -> k8sClient.ingresses()
                    .loadIngress(linkInfo.getSourceNamespace(), tcs.getEntandoAppIngressNameProperty(linkInfo.getTenantCode())));
        }
        return this.sourceIngress;
    }

    private boolean isPrimary(String tenantCode) {
        boolean isPrimary = isBlank(tenantCode) || equalsIgnoreCase(EntandoMultiTenancy.PRIMARY_TENANT, tenantCode);
        LOGGER.log(Level.SEVERE, () -> String.format("tenantCode '%s' is primary ? '%s'", tenantCode, isPrimary));
        return isPrimary;
    }

    private Service prepareReachableTargetService(SimpleK8SClient<?> k8sClient) {
        ServiceClient services = k8sClient.services();
        final Service targetService = services.loadService(linkInfo.getTargetResource(), linkInfo.getTargetServiceName());
        if (linkInfo.getSourceNamespace().equals(linkInfo.getTargetNamespace())) {
            return targetService;
        } else {
            return new ServiceCreator(linkResource, targetService).newDelegatingService(services, linkInfo);
        }
    }

    public ServerStatus getStatus() {
        return status;
    }
}
