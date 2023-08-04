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
class TenantConfigurationServiceTest {

    @Test
    void shouldManageMissedOrWrongConfiguration() {
        final String myNamespace = "nstest";
        SimpleK8SClient<?> k8sClient = new SimpleK8SClientDouble();
        final EntandoCustomResource entandoCustomResource = new TestResource()
                .withNames(myNamespace, "myCustomRes");

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, entandoCustomResource);
        Exception ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load secret with name 'entando-tenants-secret'", ex.getMessage());


        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());

        k8sClient = new SimpleK8SClientDouble();
        TenantConfigurationService tcs2 = new TenantConfigurationService(k8sClient, entandoCustomResource);
        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("key", "value")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs2.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());

        k8sClient = new SimpleK8SClientDouble();
        TenantConfigurationService tcs3 = new TenantConfigurationService(k8sClient, entandoCustomResource);
        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToData("key", "value")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs3.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());

    }

    @Test
    void shouldManageInvalidTenatConfigJson() {
        final String myNamespace = "nstest";
        SimpleK8SClient<?> k8sClient = new SimpleK8SClientDouble();
        final EntandoCustomResource entandoCustomResource = new TestResource()
                .withNames(myNamespace, "myCustomRes");

        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "ppp")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, entandoCustomResource);
        Exception ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Error in parse tenant configuration: 'ppp'", ex.getMessage());

        k8sClient = new SimpleK8SClientDouble();
        TenantConfigurationService tcs2 = new TenantConfigurationService(k8sClient, entandoCustomResource);
        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{\"tenantCode\":\"primary\"}]")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs2.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("You cannot use 'primary' as tenant code", ex.getMessage());

    }

    @Test
    void shouldManageTenatConfigEmptyOrTenantCodeNotFound() {
        final String myNamespace = "nstest";
        SimpleK8SClient<?> k8sClient = new SimpleK8SClientDouble();
        final EntandoCustomResource entandoCustomResource = new TestResource()
                .withNames(myNamespace, "myCustomRes");

        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, entandoCustomResource);
        Exception ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("tenant configuration not found for tenantCode: 'tenant1'", ex.getMessage());

        k8sClient = new SimpleK8SClientDouble();
        TenantConfigurationService tcs2 = new TenantConfigurationService(k8sClient, entandoCustomResource);
        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{\"tenantCode\":\"tenant1\"}]")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);

        ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs2.getKcAdminPassword("tenant2"));
        Assertions.assertEquals("tenant configuration not found for tenantCode: 'tenant2'", ex.getMessage());

    }

    @Test
    void shouldAllWorkFine() {
        final String myNamespace = "nstest";
        SimpleK8SClient<?> k8sClient = new SimpleK8SClientDouble();
        final EntandoCustomResource entandoCustomResource = new TestResource()
                .withNames(myNamespace, "myCustomRes");

        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(myNamespace)
                .withName("entando-tenants-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{"
                        + "\"tenantCode\":\"tenant1\","
                        + "\"kcRealm\":\"realm1\","
                        + "\"kcAuthUrl\":\"http://kchost.com/auth\","
                        + "\"kcAdminPassword\":\"psswd\""
                        + "}]")
                .build();
        k8sClient.secrets().createSecretIfAbsent(entandoCustomResource, sec);
        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, entandoCustomResource);

        Assertions.assertEquals("realm1", tcs.getKcRealm("tenant1"));
        Assertions.assertEquals("http://kchost.com/auth", tcs.getKcAuthUrl("tenant1"));
        Assertions.assertEquals("psswd", tcs.getKcAdminPassword("tenant1"));
        Exception ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs.getKcAdminUsername("tenant1"));
        Assertions.assertEquals("tenant parameter 'kcAdminUsername' not found for tenant: 'tenant1'", ex.getMessage());
    }

}
