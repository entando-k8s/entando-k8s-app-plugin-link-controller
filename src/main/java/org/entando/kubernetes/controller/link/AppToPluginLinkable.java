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

import java.util.Arrays;
import java.util.List;
import org.entando.kubernetes.controller.link.support.AccessStrategy;
import org.entando.kubernetes.controller.link.support.Linkable;
import org.entando.kubernetes.controller.link.support.QualifiedRoleAssignment;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.model.common.CustomResourceReference;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;

public class AppToPluginLinkable implements Linkable {

    public static final String ENTANDO_APP_ROLE = "entandoApp";
    public static final String COMPONENT_MANAGER_QUALIFIER = "cm";
    private final CustomResourceReference source;
    private final EntandoAppPluginLink link;
    private CustomResourceReference target;
    private String targetPathOnSourceIngres;

    public AppToPluginLinkable(EntandoAppPluginLink link) {
        this.link = link;
        this.source = new CustomResourceReference(link.getApiVersion(), "EntandoApp",
                link.getSpec().getEntandoAppNamespace().orElse(link.getMetadata().getNamespace()), link.getSpec().getEntandoAppName());
        this.target = new CustomResourceReference(link.getApiVersion(), "EntandoPlugin",
                link.getSpec().getEntandoPluginNamespace().orElse(link.getMetadata().getNamespace()),
                link.getSpec().getEntandoPluginName());
    }

    @Override
    public CustomResourceReference getSource() {
        return source;
    }

    @Override
    public CustomResourceReference getTarget() {
        return target;
    }

    @Override
    public AccessStrategy getAccessStrategy() {
        return AccessStrategy.SSO;
    }

    @Override
    public List<QualifiedRoleAssignment> getRolesOfSourceOnTarget() {
        return Arrays.asList(
                new QualifiedRoleAssignment(NameUtils.MAIN_QUALIFIER, NameUtils.MAIN_QUALIFIER, ENTANDO_APP_ROLE),
                new QualifiedRoleAssignment(COMPONENT_MANAGER_QUALIFIER, NameUtils.MAIN_QUALIFIER, ENTANDO_APP_ROLE)

        );
    }

    @Override
    public EntandoCustomResource getLinkResource() {
        return link;
    }

    @Override
    public String getTargetPathOnSourceIngress() {
        return this.targetPathOnSourceIngres;
    }

    @Override
    public int getTargetServicePort() {
        return 8081;
    }

    public void setTargetPathOnSourceIngress(String targetPathOnSourceIngres) {
        this.targetPathOnSourceIngres = targetPathOnSourceIngres;
    }
}
