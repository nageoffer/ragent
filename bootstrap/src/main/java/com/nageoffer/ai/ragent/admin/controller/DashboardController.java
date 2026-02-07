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

package com.nageoffer.ai.ragent.admin.controller;

import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadOverview(window));
    }

    @GetMapping("/performance")
    public Result<DashboardPerformanceVO> performance(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadPerformance(window));
    }

    @GetMapping("/trends")
    public Result<DashboardTrendsVO> trends(@RequestParam String metric,
                                            @RequestParam(required = false) String window,
                                            @RequestParam(required = false) String granularity) {
        return Results.success(dashboardService.loadTrends(metric, window, granularity));
    }
}
