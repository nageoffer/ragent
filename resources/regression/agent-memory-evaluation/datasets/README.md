# 比特严选测评数据

`bit-selection.json` 是标准 JSON 数组，基于 [bit-selection](../../../initializer/bit-selection/README.md) 已初始化的知识资料，共 **4 个用例、44 轮**。数组中每个用例开启独立会话，`turns` 按顺序提问。仅咨询知识和聊天中的事实，不请求购买或售后操作。

| 用例 ID | 轮数 | 用途 |
| --- | ---: | --- |
| `bit-quick` | 3 | 跑通检索和短距离回忆，`--limit 1` 选它 |
| `bit-airpods-lifecycle` | 22 | 长会话、跨话题回忆、故障事实更正 |
| `bit-family-selection` | 10 | 区分家人需求，记住更新后的预算 |
| `bit-policy-followup` | 9 | 优惠券、发票、物流咨询，检查未提供的信息 |

运行、选子集和看报告见[上一级 README](../README.md)。这套数据够做基础回归，不代表总体回答质量；默认预算下不保证触发裁剪或摘要，必须看报告是否实际发生。回忆答对只说明事实仍可用；只有确认原始事实已离开保留窗口，才能进一步评价摘要保真。

## 加自己的用例

复制下面内容保存为 UTF-8 `.json` 文件，再用 `--dataset 文件路径` 运行；多个用例放进同一个数组，`id` 不重复。

```json
[
  {
    "id": "my-budget",
    "tags": ["retention"],
    "turns": [
      {
        "id": "remember",
        "question": "这里只讨论选购，不执行购买。爸爸手机预算为 6500 元，请先记住。"
      },
      {
        "id": "recall",
        "question": "爸爸的手机预算是多少？只写阿拉伯数字，不查资料。",
        "expectAll": ["6500"],
        "forbidTools": ["search_knowledge"]
      }
    ]
  }
]
```

- `question` 是唯一发送的问题；每轮建议不超过 430 字，为首问的测试标记留空间。标准答案只放断言，不写进回忆问题。
- `expectAll` 必须全部包含；`expectAny` 至少包含一个；`forbidAny` 都不能包含。按原文子串检查，不等同于完整语义评分。
- `expectTools` / `forbidTools` 指定必须调用 / 禁止调用的工具；`tags` 用于选子集，`description` 可补充说明。
- 不写断言的轮次保留回答供抽查，不计入通过率。更正探针要求只答最新值，避免解释旧值被误判。

现有知识断言来自仓库内的《AirPods与AppleWatch常见故障排查》《促销活动与优惠券规则》《发票开具规则》，仅评价商家资料快照。其他知识轮用于推动检索并供人工抽查；价格是题设，机型推荐不设固定答案。
