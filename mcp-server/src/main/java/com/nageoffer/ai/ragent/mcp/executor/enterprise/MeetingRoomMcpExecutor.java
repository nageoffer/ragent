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

package com.nageoffer.ai.ragent.mcp.executor.enterprise;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

/**
 * 会议室空闲查询与预订，一读一写两个工具
 * 占用时段按「会议室 + 日期」定种子生成，同一天反复查结果稳定，预订才能据此判冲突
 */
@Slf4j
@Component
public class MeetingRoomMcpExecutor {

    private static final String QUERY_TOOL_ID = "meeting_room_query";
    private static final String BOOK_TOOL_ID = "meeting_room_book";

    private static final String CURRENT_EMPLOYEE = "张三";

    private static final int OPEN_MINUTE = 9 * 60;
    private static final int CLOSE_MINUTE = 21 * 60;

    /**
     * 单次预订时长上限，拦住模型把「下午」摊成一整天
     */
    private static final int MAX_DURATION_MINUTES = 4 * 60;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<Room> ROOMS = List.of(
            new Room("A701", "光明顶", "西溪园区A楼7F", 12, "视频会议、白板"),
            new Room("A703", "桃花岛", "西溪园区A楼7F", 6, "白板"),
            new Room("B305", "聚贤庄", "西溪园区B楼3F", 20, "投屏、视频会议"),
            new Room("B308", "百花谷", "西溪园区B楼3F", 4, "电话间"),
            new Room("C502", "侠客岛", "紫金港园区C楼5F", 8, "投屏"),
            new Room("C509", "黑木崖", "紫金港园区C楼5F", 30, "视频会议、音响")
    );

    @Bean
    public McpServerFeatures.SyncToolSpecification meetingRoomQueryToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildQueryTool())
                .callHandler((exchange, request) -> handleQuery(request))
                .build();
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification meetingRoomBookToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildBookTool())
                .callHandler((exchange, request) -> handleBook(request))
                .build();
    }

    private Tool buildQueryTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("date", "查询日期，格式 yyyy-MM-dd，如 2026-09-07"))
                .optional(string("startTime", "时段开始时间，格式 HH:mm，如 14:00，不填则返回全天占用情况"))
                .optional(string("endTime", "时段结束时间，格式 HH:mm，如 15:30，与开始时间成对提供"))
                .optional(integer("capacity", "最少容纳人数，不填则不限"))
                .build();

        return Tool.builder()
                .name(QUERY_TOOL_ID)
                .description("查询指定日期各会议室的占用与空闲情况，支持按时段和容纳人数筛选，"
                        + "返回会议室名称、ID、位置、容纳人数、设备与已占用时段，预订前应先用本工具确认空闲")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    private Tool buildBookTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("roomId", "会议室ID（不是会议室名称），如 A701，需取自空闲查询结果")
                        .title("会议室ID")
                        .options(ROOMS.stream().map(Room::id).toList()))
                .required(string("date", "预订日期，格式 yyyy-MM-dd")
                        .title("日期"))
                .required(string("startTime", "开始时间，格式 HH:mm，可预订时段为 09:00 至 21:00")
                        .title("开始时间"))
                .required(string("endTime", "结束时间，格式 HH:mm")
                        .title("结束时间"))
                .required(string("topic", "会议主题，据实填写用户说明的内容，不要代为编造")
                        .title("会议主题"))
                .build();

        return Tool.builder()
                .name(BOOK_TOOL_ID)
                .description("为当前登录员工预订会议室，预订成功后会占用该时段并通知参会人。"
                        + "本工具会产生真实业务副作用，会议室ID与时段必须先经空闲查询确认")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleQuery(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String date = MapUtil.getStr(args, "date");
            String startTime = MapUtil.getStr(args, "startTime");
            String endTime = MapUtil.getStr(args, "endTime");
            Integer capacity = MapUtil.getInt(args, "capacity");

            LocalDate day = McpToolResults.parseDate(date);
            if (day == null) {
                return McpToolResults.error("查询日期缺失或格式不正确，需要 yyyy-MM-dd 格式");
            }
            Integer from = parseMinute(startTime);
            Integer to = parseMinute(endTime);
            if ((startTime != null && from == null) || (endTime != null && to == null)) {
                return McpToolResults.error("时段格式不正确，需要 HH:mm 格式的开始时间与结束时间");
            }
            if (from != null && to != null && to <= from) {
                return McpToolResults.error("结束时间不晚于开始时间，请确认后重新查询");
            }

            String result = buildQueryResult(day, from, to, capacity);
            log.info("MCP 工具调用完成, toolId={}, date={}, startTime={}, endTime={}, capacity={}, elapsed={}ms",
                    QUERY_TOOL_ID, date, startTime, endTime, capacity, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    QUERY_TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("查询", e);
        }
    }

    private CallToolResult handleBook(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String roomId = MapUtil.getStr(args, "roomId");
            String date = MapUtil.getStr(args, "date");
            String startTime = MapUtil.getStr(args, "startTime");
            String endTime = MapUtil.getStr(args, "endTime");
            String topic = MapUtil.getStr(args, "topic");

            Room room = findRoom(roomId);
            LocalDate day = McpToolResults.parseDate(date);
            Integer from = parseMinute(startTime);
            Integer to = parseMinute(endTime);

            String rejection = validateBooking(room, roomId, day, from, to, topic);
            if (rejection != null) {
                log.info("MCP 工具调用被拒, toolId={}, reason={}, elapsed={}ms",
                        BOOK_TOOL_ID, rejection, System.currentTimeMillis() - startMs);
                return McpToolResults.error(rejection);
            }

            String bookingNo = nextBookingNo();
            log.info("预订会议室, toolId={}, bookingNo={}, employee={}, roomId={}, date={}, "
                            + "startTime={}, endTime={}, topic={}, elapsed={}ms",
                    BOOK_TOOL_ID, bookingNo, CURRENT_EMPLOYEE, room.id(), date, startTime, endTime, topic,
                    System.currentTimeMillis() - startMs);
            return McpToolResults.success(buildBookReceipt(bookingNo, room, day, from, to, topic));
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    BOOK_TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("预订", e);
        }
    }

    /**
     * 校验不通过返回给模型的中文说明，冲突时连同已占用时段一起给，让它换时段而不是硬试
     */
    private String validateBooking(Room room, String roomId, LocalDate day, Integer from, Integer to, String topic) {
        if (room == null) {
            return "会议室ID " + roomId + " 不存在，请先用空闲查询确认可用的会议室ID";
        }
        if (day == null) {
            return "预订日期缺失或格式不正确，需要 yyyy-MM-dd 格式";
        }
        if (from == null || to == null) {
            return "预订时段缺失或格式不正确，需要 HH:mm 格式的开始时间与结束时间";
        }
        if (to <= from) {
            return "结束时间不晚于开始时间，请确认后重新提交";
        }
        if (from < OPEN_MINUTE || to > CLOSE_MINUTE) {
            return String.format("预订时段需落在 %s 至 %s 之间", minute(OPEN_MINUTE), minute(CLOSE_MINUTE));
        }
        if (to - from > MAX_DURATION_MINUTES) {
            return String.format("单次预订时长 %s，已超过 %d 小时上限，请向用户确认实际时段",
                    describeDuration(to - from), MAX_DURATION_MINUTES / 60);
        }
        if (StrUtil.isBlank(topic)) {
            return "会议主题为必填项，请向用户确认后再提交";
        }
        List<int[]> conflicts = occupiedSlots(room, day).stream()
                .filter(slot -> overlaps(from, to, slot))
                .toList();
        if (!conflicts.isEmpty()) {
            return String.format("%s（ID: %s）在 %s 的 %s 时段已被占用（%s），请改约其他时段或会议室",
                    room.name(), room.id(), day, minute(from) + "-" + minute(to), describeSlots(conflicts));
        }
        return null;
    }

    private String buildQueryResult(LocalDate day, Integer from, Integer to, Integer capacity) {
        List<Room> candidates = ROOMS.stream()
                .filter(room -> capacity == null || room.capacity() >= capacity)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 会议室空闲情况】%n%n", day));
        List<String> filters = new ArrayList<>();
        if (from != null && to != null) {
            filters.add("时段: " + minute(from) + "-" + minute(to));
        }
        if (capacity != null) {
            filters.add("最少容纳: " + capacity + " 人");
        }
        if (!filters.isEmpty()) {
            sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");
        }
        if (candidates.isEmpty()) {
            sb.append("没有符合容纳人数要求的会议室");
            return sb.toString();
        }

        int freeCount = 0;
        for (Room room : candidates) {
            List<int[]> slots = occupiedSlots(room, day);
            boolean free = from == null || to == null
                    || slots.stream().noneMatch(slot -> overlaps(from, to, slot));
            if (free) {
                freeCount++;
            }
            sb.append(String.format("%s | ID: %s | %s | 容纳 %d 人 | %s%n",
                    room.name(), room.id(), room.location(), room.capacity(), room.equipment()));
            sb.append(String.format("   已占用: %s%n", slots.isEmpty() ? "全天空闲" : describeSlots(slots)));
            if (from != null && to != null) {
                sb.append(String.format("   所选时段: %s%n", free ? "空闲，可预订" : "已被占用"));
            }
            sb.append("\n");
        }
        if (from != null && to != null) {
            sb.append(String.format("所选时段共 %d 间可预订", freeCount));
        } else {
            sb.append("可预订时段为 09:00 至 21:00，预订请提供会议室ID、日期、起止时间与会议主题");
        }
        return sb.toString().trim();
    }

    private String buildBookReceipt(String bookingNo, Room room, LocalDate day, int from, int to, String topic) {
        return "【会议室预订成功】\n\n" +
                String.format("预订号: %s%n", bookingNo) +
                String.format("预订人: %s%n", CURRENT_EMPLOYEE) +
                String.format("会议室: %s（ID: %s，%s，容纳 %d 人，%s）%n",
                        room.name(), room.id(), room.location(), room.capacity(), room.equipment()) +
                String.format("会议时间: %s %s-%s，时长 %s%n",
                        day, minute(from), minute(to), describeDuration(to - from)) +
                String.format("会议主题: %s%n", topic) +
                "该时段已锁定，如需取消请到办公工作台的我的预订中操作";
    }

    /**
     * 同一「会议室 + 日期」的占用固定不变，否则查完再订会撞上一份新随机结果
     */
    private List<int[]> occupiedSlots(Room room, LocalDate day) {
        Random random = new Random((room.id() + "_" + day).hashCode());
        int count = random.nextInt(3);
        List<int[]> slots = new ArrayList<>();
        int cursor = OPEN_MINUTE;
        for (int i = 0; i < count; i++) {
            int gap = 30 * random.nextInt(5);
            int duration = 60 * (1 + random.nextInt(2));
            int start = cursor + gap;
            if (start + duration > CLOSE_MINUTE) {
                break;
            }
            slots.add(new int[]{start, start + duration});
            cursor = start + duration;
        }
        return slots;
    }

    private Room findRoom(String roomId) {
        if (StrUtil.isBlank(roomId)) {
            return null;
        }
        String normalized = StrUtil.trim(roomId).toUpperCase();
        return ROOMS.stream().filter(room -> room.id().equals(normalized)).findFirst().orElse(null);
    }

    private String nextBookingNo() {
        return String.format("MR-%s-%04d", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                RandomUtil.randomInt(10000));
    }

    private static boolean overlaps(int from, int to, int[] slot) {
        return from < slot[1] && to > slot[0];
    }

    private static String describeSlots(List<int[]> slots) {
        return slots.stream()
                .map(slot -> minute(slot[0]) + "-" + minute(slot[1]))
                .collect(Collectors.joining("，"));
    }

    private static String describeDuration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " 分钟";
        }
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    private static String minute(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(HH_MM);
    }

    private static Integer parseMinute(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            LocalTime time = LocalTime.parse(StrUtil.trim(value), HH_MM);
            return time.getHour() * 60 + time.getMinute();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private record Room(String id, String name, String location, int capacity, String equipment) {
    }
}
