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

package com.nageoffer.ai.ragent.framework.config;

import com.nageoffer.ai.ragent.framework.cache.RedisKeySerializer;
import org.jspecify.annotations.NonNull;
import org.redisson.config.NameMapper;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis key prefix configuration.
 */
@Configuration
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeyPrefixAutoConfiguration {

    @Bean
    public BeanPostProcessor stringRedisTemplateKeyPrefixPostProcessor(RedisKeySerializer redisKeySerializer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
                if (bean instanceof StringRedisTemplate template) {
                    template.setKeySerializer(redisKeySerializer);
                    template.setHashKeySerializer(redisKeySerializer);
                }
                return bean;
            }
        };
    }

    @Bean
    public RedissonAutoConfigurationCustomizer redissonKeyPrefixCustomizer(
            @Value("${framework.cache.redis.prefix}") String keyPrefix) {
        return config -> config.setNameMapper(new PrefixNameMapper(keyPrefix, config.getNameMapper()));
    }

    private record PrefixNameMapper(String keyPrefix, NameMapper delegate) implements NameMapper {

        @Override
        public String map(String name) {
            String mappedName = delegate.map(name);
            if (mappedName == null || hasPrefix(mappedName)) {
                return mappedName;
            }
            return keyPrefix + mappedName;
        }

        @Override
        public String unmap(String name) {
            if (name == null || !hasPrefix(name)) {
                return delegate.unmap(name);
            }
            return delegate.unmap(name.substring(keyPrefix.length()));
        }

        private boolean hasPrefix(String name) {
            return keyPrefix != null && !keyPrefix.isEmpty() && name.startsWith(keyPrefix);
        }
    }
}
