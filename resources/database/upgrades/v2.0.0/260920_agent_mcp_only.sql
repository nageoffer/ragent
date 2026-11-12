-- MCP 工具只由 Agent 调用，删除废弃的参数提取列和回答槽位

ALTER TABLE t_intent_node
    DROP COLUMN IF EXISTS param_prompt_template;

DELETE FROM t_agent_prompt
WHERE slot_key IN ('MCP_ANSWER', 'MIXED_ANSWER');
