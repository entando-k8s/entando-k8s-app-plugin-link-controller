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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.MayRequireDelegateService;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.IngressingPathOnPort;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.ServerStatus;

public class LinkInfo implements MayRequireDelegateService {

    private final SerializedEntandoResource sourceResource;
    private final SerializedEntandoResource targetResource;
    private final Linkable linkable;
    private final Linkable customIngressLinkable;

    public LinkInfo(Linkable linkable, Linkable customIngressLinkable, SerializedEntandoResource sourceResource,
            SerializedEntandoResource targetResource) {
        this.sourceResource = sourceResource;
        this.targetResource = targetResource;
        this.linkable = linkable;
        this.customIngressLinkable = customIngressLinkable;
    }

    public String getSsoRealm() {
        return sourceResource.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).flatMap(ServerStatus::getSsoRealm)
                .orElseThrow(IllegalStateException::new);
    }

    public String getSourceServiceName() {
        return this.sourceResource.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                .flatMap(ServerStatus::getServiceName)
                .orElseThrow(IllegalStateException::new);
    }

    public String getTargetServiceName() {
        return this.targetResource.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                .flatMap(ServerStatus::getServiceName)
                .orElseThrow(IllegalStateException::new);
    }

    public String getSourceIngressName() {
        return this.sourceResource.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                .flatMap(ServerStatus::getIngressName)
                .orElseThrow(IllegalStateException::new);
    }

    public List<LinkPermission> getPermissions() {
        return linkable.getRolesOfSourceOnTarget().stream().map(qra ->
                new LinkPermission(getClientId(sourceResource, qra.getSourceQualifier()),
                        getClientId(targetResource, qra.getTargetQualifier()),
                        qra.getRole())).collect(Collectors.toList());
    }

    private String getClientId(EntandoCustomResource resource, String qualifier) {
        return resource.getStatus().getServerStatus(qualifier).flatMap(ServerStatus::getSsoClientId)
                .orElseThrow(IllegalStateException::new);
    }

    public ServerStatus getSsoServiceStatus() {
        return sourceResource.getStatus().getServerStatus(NameUtils.SSO_QUALIFIER)
                .orElseThrow(IllegalStateException::new);
    }

    public String getTargetNamespace() {
        return this.targetResource.getMetadata().getNamespace();
    }

    public String getSourceNamespace() {
        return this.sourceResource.getMetadata().getNamespace();
    }

    @Override
    public String getIngressName() {
        return getSourceIngressName();
    }

    @Override
    public String getIngressNamespace() {
        return getSourceNamespace();
    }

    @Override
    public Optional<String> getQualifier() {
        return Optional.ofNullable(targetResource.getMetadata().getName());
    }

    public List<IngressingPathOnPort> getPathsOnPorts() {

        return Stream.of(linkable, customIngressLinkable)
                .filter(Objects::nonNull)
                .map(l ->
                        new IngressingPathOnPort() {
                            @Override
                            public String getNameQualifier() {
                                return targetResource.getMetadata().getName() + "-" + NameUtils.randomNumeric(4);
                            }

                            @Override
                            public int getPortForIngressPath() {
                                return l.getTargetServicePort();
                            }

                            @Override
                            public String getWebContextPath() {
                                return l.getTargetPathOnSourceIngress();
                            }

                            @Override
                            public Optional<String> getHealthCheckPath() {
                                //Not required in this context TODO normalize this
                                return Optional.empty();
                            }
                        })
                .collect(Collectors.toList());
    }

    public SerializedEntandoResource getTargetResource() {
        return targetResource;
    }

    public Optional<String> getTargetIngressName() {
        return this.targetResource.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                .flatMap(ServerStatus::getIngressName);
    }
}
