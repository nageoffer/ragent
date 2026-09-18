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

package com.nageoffer.ai.ragent.framework.distributedid;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分布式 Snowflake 初始化器
 * 从 Redis 获取 workerId 和 datacenterId，并注册到 Hutool 的 IdUtil 中
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdInitializer {

    private static final String SNOWFLAKE_WORK_ID_KEY = "snowflake_work_id_key";

    private final StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @PostConstruct
    public void init() {
        // 加载Lua脚本
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/snowflake_init.lua")));
        script.setResultType(List.class);

        try {
            // 执行 Lua 脚本获取 workerId 和 datacenterId
            List<Long> result = stringRedisTemplate.execute(script, List.of(SNOWFLAKE_WORK_ID_KEY));

            if (CollUtil.isEmpty(result) || result.size() != 2) {
                throw new RuntimeException("从Redis获取WorkerId和DataCenterId失败");
            }

            Long workerId = result.get(0);
            Long datacenterId = result.get(1);

            // 注册到 Hutool 的 IdUtil
            Snowflake snowflake = new Snowflake(workerId, datacenterId);
            Singleton.put(snowflake);

            log.info("分布式Snowflake初始化完成, workerId: {}, datacenterId: {}", workerId, datacenterId);
        } catch (Exception e) {
            throw new RuntimeException("分布式Snowflake初始化失败", e);
        }
    }
}
