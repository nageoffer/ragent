-- v2.0.0 261108 后台记忆抽取材料改为 conversationId 到有序消息列表的映射
-- 前置脚本：261019_agent_memory_cursor.sql
-- 停止旧版本的记忆抽取后执行，再部署匹配的新代码；旧版 LearnInput 与新版提示词不可混用
-- 仅替换可识别的协议段落，保留内置与自定义提示词的其余内容，可重复执行
-- 未识别的非空自定义抽取提示词会中止整个事务，需人工按新协议调整后重试
-- 执行后删除 Redis key ragent:agent:resolved-prompts:v2，再启动新版本；重启进程不会清除此缓存
BEGIN;

DO $migration$
DECLARE
    prompt_row RECORD;
    updated_content TEXT;
BEGIN
    FOR prompt_row IN
        SELECT id, content
        FROM t_agent_prompt
        WHERE slot_key = 'AGENT_MEMORY_EXTRACTION'
          AND deleted = 0
          AND btrim(coalesce(content, '')) <> ''
        FOR UPDATE
    LOOP
        IF position('- 后台整理时给出 conversations：' IN prompt_row.content) > 0 THEN
            CONTINUE;
        END IF;

        updated_content := regexp_replace(prompt_row.content,
            $old$[ \t]*- 后台整理时给出 messages、background：\r?\n[ \t]*- messages：[^\r\n]*\r?\n[ \t]*- background：[^\r\n]*$old$,
            $new$- 后台整理时给出 conversations：
  - conversations 是以 conversationId 为 key 的对象，每个 value 是该会话按原始消息顺序排列的列表，背景和本批发言已交错放回原位；每条消息另带 source
  - source=candidate：本次要整理的用户发言，speaker=user，决策的 sourceMessageId 只能写这些消息的 messageId
  - source=background：该会话的其他消息，包括之前已整理过的用户发言和助手的话，只用来理解对象和指代，不能直接作为决策来源
  - 「这个」「对，就是这样」等指代只在同一个 key 对应的会话中理解，不能借用其他会话的上下文
  - 会话 key 的排列不代表全局时间顺序；同一事实在不同会话中发生更正、删除或清空时，仍比较所有相关用户发言的 time 与已有记忆的 expressedAt，以表达更晚的为准$new$);

        IF updated_content = prompt_row.content THEN
            RAISE EXCEPTION '无法识别长期记忆抽取提示词的旧协议，请人工调整后重试，prompt id=%', prompt_row.id;
        END IF;

        updated_content := replace(updated_content,
            '事实只能来自可引用的用户发言（request 或 messages）。',
            '事实只能来自可引用的用户发言（主动整理的 request，或后台 conversations 中 source=candidate 的用户消息）。');
        updated_content := replace(updated_content, 'background 里用户以前说过的话', '背景中用户以前说过的话');
        updated_content := replace(updated_content, 'background 被再读到一次', '背景被再读到一次');
        updated_content := replace(updated_content, '（在 later 或更晚的 messages 里）',
            '（主动整理的 later 或后台各会话中 time 更晚的 candidate 用户消息）');

        UPDATE t_agent_prompt
        SET content = updated_content, update_time = CURRENT_TIMESTAMP
        WHERE id = prompt_row.id;
    END LOOP;
END;
$migration$;

COMMIT;
