# 比特严选 Agent 数据集

本目录是 `--agent-type-dir` 可以指向的一套初始化数据集，内容是「比特严选」这个虚构商家的知识库、意图树与业务库演示数据。

**比特严选的设定是 Apple 授权经销商**：在售商品是 Apple 全线产品与精选第三方配件，不做自有品牌产品。这个设定决定了商品数据取自 Apple 官网快照、保修政策要区分 Apple 有限保修与本店三包义务，以及越界提问的边界落在「本店不经营的品牌」而不是「别家品牌」——问 Apple 产品是范围内的。

通用说明（各入口类的用法、公共参数、安全边界、常见错误）在上一级的 `resources/initializer/README.md`，本文只写这套数据集特有的部分。

## 一、目录结构

```
bit-selection/
├── initializer.properties      # 数据集配置，额外指向 mcp-server 的 application.yml
├── knowledge-bases.properties  # 四个知识库的声明
├── questions.properties        # 15 条示例问题，同时是预热题目
├── checksums.sha256            # 除自身与 *.java 外所有文件的校验和
├── docs/knowledge/             # 69 篇知识文档
│   ├── product/  detail 31 + guide 7
│   ├── manual/   product 7 + app 2 + network 2
│   ├── policy/   warranty 5 + return 4 + logistics 3 + invoice-vip 3 + promotion 1
│   └── faq/      trouble 3 + error-code 1
├── intents/                    # 意图树 42 个节点，按文件名前缀排序加载
├── skills/                     # 5 份技能手册及其声明
├── prompts/                    # 人设提示词正文
├── agent-profile.properties    # 人设声明，指向 prompts/ 下的正文
├── cleanup.sql                 # 平台库清理白名单
├── biz-data/                   # 业务库 ragent_bit 的演示数据
└── biz-cleanup.sql             # 业务库数据清空脚本
```

知识库按「问的是什么」切分，不按品类切分：`product` 是买之前的选型，`manual` 是买回去怎么用，`policy` 是平台规则判定口径，`faq` 是出问题以后怎么自查。品类切分会让「iPhone 保修多久」这类跨层问题落不进任何一个库。

完整初始化用 `InitializeMain`，确认词是 `RESET-BIT-SELECTION`：

```bash
java -cp /tmp/ragent-initializer-classes \
  com.nageoffer.ai.ragent.initializer.InitializeMain \
  --agent-type-dir resources/initializer/bit-selection \
  --confirm RESET-BIT-SELECTION
```

各入口的用法与公共参数见上一级 README，本数据集另有两个入口：`BizDataInitMain` 灌业务库演示数据，`AgentProfileInitMain` 新建并激活人设。

## 二、文档来源

**政策、说明书、故障排查类**：判定口径与流程结构沿用独立仓库 `ragenteval` 的 `knowledge_base`，但正文按 Apple 产品重写。原稿以另一个品牌的品类（扫地机、门锁、空气净化器）为素材，换成 Apple 之后配网走 iOS、耗材换成耳塞与表带、错误提示换成 Apple 的文字提示，原稿的正文没有可直接复用的部分。

**商品详情**：参数取自 Apple 中国官网 2026-09-13 的技术规格页快照，照抄的是规格事实；一句话定位、适合谁、怎么挑三处全部自撰，不使用官方营销文案。

**配件导购**：数据（款数、价格区间、品牌归属）由业务库数据生成，选购建议自撰。

三条一致的边界：规格参数是事实性数据可以照抄；营销文案与政策原文不整段复制；价格标注快照日期。

两边是两份独立副本，不做跨仓引用。initializer 本来就要求数据集自包含，`checksums.sha256` 就是为此设计的。

## 三、商品文档与业务库的对应关系

商品在业务库里分两层，文档只对应上面那一层：

| 层 | 表 | 数量 | 有没有文档 |
| --- | --- | --- | --- |
| 商品款（SPU） | `t_product` | 695（设备 31 + 配件 664） | 设备款一款一篇 |
| 可下单配置（SKU） | `t_product_sku` | 1420 | 不单独成篇 |

`docs/knowledge/product/detail/` 下的 31 篇与 31 个设备款互为镜像，**文件名即 `spu_code`**：

```bash
# 两侧应当完全一致，输出为空即通过
diff <(ls docs/knowledge/product/detail/*.md | xargs -n1 basename | sed 's|\.md$||' | LC_ALL=C sort) \
     <(sed -n '/INSERT INTO t_product /,/;$/p' biz-data/01-product.sql \
       | grep -oE "'[A-Z][A-Z0-9-]+', '[^']+', '(手机|平板|电脑|智能手表|耳机音频|智能家居|空间计算)'" \
       | grep -oE "^'[A-Z][A-Z0-9-]+'" | tr -d "'" | LC_ALL=C sort -u)
```

**配件款（664 个）不要求一款一篇**，它们按二级品类合写成 `product/guide/` 下的 7 篇导购。一件一篇既写不完，也会让 664 篇近似文档把检索池灌满——这是刻意的不对称，`VerifyMain` 的跨库校验也只对 `category <> '配件'` 的款生效。

这条约束的意义在于：商品筛选工具按款返回时，`search_knowledge` 对每一款设备都查得到详情；配件则由品类导购篇兜底。任何一侧增删设备款，另一侧要同步改。

商品详情正文中的价格标注为快照参考价，实时价格与库存以业务库查询结果为准，文档内已逐篇说明。人设里也写了一条硬约束：价格参数一律以工具返回为准，不用模型自己记得的型号参数——真实商品的参数化知识会与库里数据打架。

## 四、技能与人设

`skills/` 下五份手册对应五类多步骤事务：申请售后、改单取消、故障诊断、加购下单、多设备搭配。其中故障诊断与多设备搭配的 `tool-ids` 是空的，它们解锁的不是工具而是流程；另外三份声明的工具在加载手册之前不出现在模型的工具清单里，这也是这一层的验收点。

手册里的判定口径以服务端执行器为准，手册只负责让模型走对流程。例如七天无理由是否成立由 `apply_after_sale` 现算，手册明确要求模型不要自己拿签收时间推天数——凡是能决定钱和状态的判据，服务端都会独立重算，手册讲错不会导致放行。

`agent-profile.properties` 走的是「新建一份人设并激活」，内置智能体一个字不动，切回企业助手只要把 active 切回内置。人设由 `AgentProfileInitMain` 单独执行，也包含在整套初始化流程里，顺序上必须排在 cleanup 之后——cleanup 按 `builtin = 0` 删行，先建的人设会被它删掉。

十个槽位只填五个，其余留空即回落内置那份：

| 槽位 | 生效档位 | 为什么填 |
| --- | --- | --- |
| `AGENT_MAIN` | agent | 本数据集的主人设，身份、工具选择与结果处理都在这里 |
| `CONVERSATION_SUMMARY` | workflow | 内置那份拿年假、报销举例，换成电商语料 |
| `SYSTEM_CHAT` | workflow | 内置那份整段写着「企业内部知识助手」，兜底话术是「建议联系 HR」 |
| `AGENT_MEMORY_EXTRACTION` | agent | 规则与内置一致，「什么该记、什么不该记」的例子换成售后排查、维修进度、比较中的商品 |
| `AGENT_CONTEXT_COMPACTION` | agent | 按商城的事整理：一副耳机、一笔订单、一款在考虑的商品各占一条；价格库存只记查过不抄数值；分清用户自述、工具查得、助手判断 |

`CONVERSATION_SUMMARY` 与 `SYSTEM_CHAT` 在 `agent` 档下没有调用方，填它们是为了这套数据集切到 `workflow` 档也不自报企业助手。`AGENT_MEMORY_EXTRACTION` 与 `AGENT_CONTEXT_COMPACTION` 都是整份覆盖，内置那份改了通用规则（围栏、原话保留、暂缓与放弃的写法）要同步过来。上下文压缩单独填，是因为内置那份按通用办事写，在商城会话里会把每次追问记成一条「了解 X」、把价格库存逐个抄进摘要，摘要随轮数越续越长。剩下的知识与记忆工具声明、长期记忆合并本就与领域无关，填了反而要跟着内置那份一起维护。

## 五、示例问题的选题口径

`questions.properties` 只放知识型问法，不放「我的订单到哪了」这类要查业务数据的问法。两条理由各自都足够：

- 示例问题会渲染成欢迎页卡片，**点一下就直接发出去**。办事类问法会当场触发写操作确认卡，不该由一次误点发起。
- 预热逐题调用 `/agent/v1/chat`。查订单等实时数据虽能由 Agent 调工具得到，但结果依赖演示业务库的当前状态，不适合作为欢迎页固定问题

还有一条与商品数据相关的：**问法里的预算要落在真实价格带内**。在售手机最低是 5299 元，写「3000 以内买台手机」会得到一个空结果，而那不是检索的问题。

查订单、查物流、下单改单这些能力由对话页的 Agent 链路承担。`enterprise-knowledge-base` 同样跑在 `agent` 档，但它的欢迎页包含只读的资产、假期和工单数据问题，所以预热会调用 MCP 工具。

## 六、改动之后要做的事

改动本目录下任何文件后，先重算校验和：

```bash
(cd resources/initializer/bit-selection && \
  find . -type f \( -name '*.md' -o -name '*.sql' -o -name '*.properties' -o -name '*.txt' \) \
  -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 | sed 's|  \./|  |' > checksums.sha256)
```

`-print0` 与 `xargs -0` 不能省：文件名里一旦有空格，按空格分词的写法会把那个文件整个漏掉，
而校验和少一行不会报错——它只校验清单里列出的文件。文件名本身也建议不带空格。

改了文档、意图树或技能之后，`initializer.properties` 里的 `verification.*` 计数要跟着回填，否则 `VerifyMain` 会以「数量不符」失败：

```
verification.document-count / intent-count / question-count / skill-count
verification.biz-spu-count / biz-sku-count / biz-order-count / biz-coupon-count
```

再做一次离线校验，它会检查校验和、知识库声明、文档非空、意图树引用，以及技能声明的工具是否落在已启用的 MCP 意图节点上：

```bash
rm -rf /tmp/ragent-initializer-classes && mkdir -p /tmp/ragent-initializer-classes
javac -encoding UTF-8 -d /tmp/ragent-initializer-classes \
  resources/initializer/common/*.java resources/initializer/bit-selection/*.java
java -cp /tmp/ragent-initializer-classes \
  com.nageoffer.ai.ragent.initializer.ValidateDatasetMain \
  --agent-type-dir resources/initializer/bit-selection
```

**编译目录里可能留着上一次的 class。** `common/` 下的代码改过而 class 没重编时，跑出来的是旧逻辑，报错会出现在离原因很远的地方（比如校验阶段报某个字段不存在，而那时数据已经灌完）。上面第一行的 `rm -rf` 不要省。

## 七、业务库

`biz-data/` 与 `biz-cleanup.sql` 面向业务库 `ragent_bit`，由 `BizDataInitMain` 灌入。该库的**表结构归 mcp-server 启动期创建**，本数据集只负责数据，清空脚本也只清数据不动结构。

因此执行 `BizDataInitMain` 之前，mcp-server 至少要成功启动过一次。连接身份取自 `initializer.properties` 里的 `application.mcp-config`，指向 mcp-server 的 `application.yml`；两边各写一遍迟早对不上，对不上的表现是「灌完数据一条也查不到」。

种子脚本里有两处与表结构直接相关，改数据时要一起顾到：

- **建表脚本不给任何列默认值**，主键与时间由应用侧生成（MyBatis-Plus 的 `ASSIGN_ID` 与 `BitMetaObjectHandler`）。批量灌数据这条路不经过 MyBatis，所以 `id`、`create_time`、`update_time` 都要在 SQL 里显式给
- **`${USER_ID}` 由 `BizDataInitMain` 替换**成平台库里初始化账号的真实 ID，替换值已经过 SQL 字面量转义，所以脚本里不带引号。挂错人的后果不是报错而是全部演示都回「未找到」——工具一律按登录态圈数据

数据里刻意留了几处边界，删掉它们会让对应的演示问不出东西：

| 位置 | 边界 | 演示的是 |
| --- | --- | --- |
| `01-product.sql` | iPhone 16 最低配已下架 | 这款还能不能买 |
| `01-product.sql` | AirPods Pro 3 零库存 | 加购的东西没货了怎么办 |
| `01-product.sql` | Vision Pro 256GB 只剩 3 件 | 库存够不够 |
| `02-coupon.sql` | 平板券、手机券门槛、过期券各一张 | 能用的券与看着能用的券 |
| `03-order.sql` | 五种订单状态各一单，签收时间拉开三档 | 订单变更四分支、七天无理由三档 |
| `06-cart.sql` | 车里含零库存与仅剩 3 件的商品 | 一键下单会先撞上什么 |
