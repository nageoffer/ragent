/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.integration;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

final class RealIntegrationTestSupport {

    static final String ENABLED_PROPERTY = "ragent.real-it.enabled";
    static final String DEFAULT_TAILSCALE_HOST = "100.67.140.111";
    private static final Properties REAL_PROFILE_PROPERTIES = loadRealProfileProperties();

    private RealIntegrationTestSupport() {
    }

    static String value(String propertyName, String envName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String profileValue = REAL_PROFILE_PROPERTIES.getProperty(propertyName);
        if (profileValue != null && !profileValue.isBlank()) {
            return profileValue;
        }
        return defaultValue;
    }

    private static Properties loadRealProfileProperties() {
        ClassPathResource resource = new ClassPathResource("application-real.yaml");
        if (!resource.exists()) {
            return new Properties();
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }
}
