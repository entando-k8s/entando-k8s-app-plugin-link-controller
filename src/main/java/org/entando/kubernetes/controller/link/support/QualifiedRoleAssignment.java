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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.quarkus.runtime.annotations.RegisterForReflection;

@JsonInclude(Include.NON_NULL)
@JsonAutoDetect(
        fieldVisibility = Visibility.ANY,
        isGetterVisibility = Visibility.NONE,
        getterVisibility = Visibility.NONE,
        setterVisibility = Visibility.NONE
)
@RegisterForReflection
@JsonIgnoreProperties(
        ignoreUnknown = true
)
public class QualifiedRoleAssignment {
    private String sourceQualifier;
    private String targetQualifier;
    private String role;

    public QualifiedRoleAssignment(String sourceQualifier, String targetQualifier, String role) {
        this.sourceQualifier = sourceQualifier;
        this.targetQualifier = targetQualifier;
        this.role = role;
    }

    public String getSourceQualifier() {
        return sourceQualifier;
    }

    public String getTargetQualifier() {
        return targetQualifier;
    }

    public String getRole() {
        return role;
    }
}
