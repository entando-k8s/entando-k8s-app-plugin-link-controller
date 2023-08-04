/*
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package org.entando.kubernetes.controller.link.tenant;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("in-process")
class SsoConnectionInfoTenantAwareTest {

    @Test
    void shouldConstructorFromMapAndCopyConstructorWorkFine() {
        final String ns = "nstest";
        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{"
                        + "\"tenantCode\":\"tenant1\","
                        + "\"kcRealm\":\"realm1\","
                        + "\"kcAuthUrl\":\"http://kchost.com/auth\","
                        + "\"kcInternalAuthUrl\":\"http://mykcsvc.nstest.svc.cluster.local/auth\","
                        + "\"kcAdminUsername\":\"username\","
                        + "\"kcAdminPassword\":\"psswd\""
                        + "}]")
                .build();
        SimpleK8SClient<?> k8sClient = new SimpleK8SClientDouble();
        final EntandoCustomResource entandoCustomResource = new TestResource()
                .withNames(ns, "myCustomRes");
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        SsoConnectionInfo info = new SsoConnectionInfoTenantAware("tenant1", entandoCustomResource, k8sClient);
        Assertions.assertEquals("realm1", info.getDefaultRealm().orElse(""));
        Assertions.assertEquals("http://kchost.com/auth", info.getExternalBaseUrl());
        Assertions.assertEquals("http://mykcsvc.nstest.svc.cluster.local/auth", info.getInternalBaseUrl().orElse(""));
        Assertions.assertEquals("http://mykcsvc.nstest.svc.cluster.local/auth", info.getBaseUrlToUse());
        Assertions.assertEquals("username", info.getUsername());
        Assertions.assertEquals("psswd", info.getPassword());
        Assertions.assertEquals(ns, info.getAdminSecret().getMetadata().getNamespace());
    }

}
