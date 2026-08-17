import type { ComponentType, ReactNode } from "react";
import { useEffect, useState } from "react";
import {
  AlertCircle,
  Check,
  ChevronDown,
  ChevronRight,
  Cpu,
  Database,
  Globe,
  HardDrive,
  KeyRound,
  Minus,
  RefreshCw,
  Share2
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import type { ModelGroup, RetrievalChannel, SystemSettings } from "@/services/settingsService";
import { getSystemSettings } from "@/services/settingsService";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";

type Tone = "cyan" | "emerald" | "indigo" | "amber" | "sky" | "slate";

// 选型值到展示文案的映射，未知取值原样透出
const ENGINE_CAPTIONS: Record<string, string> = {
  workflow: "工作流编排管线",
  agent: "ReAct 主智能体"
};

const VECTOR_CAPTIONS: Record<string, string> = {
  pg: "PostgreSQL · pgvector",
  milvus: "Milvus"
};

// 通道行空间紧凑，用短名
const VECTOR_SHORT_CAPTIONS: Record<string, string> = {
  pg: "pgvector",
  milvus: "Milvus"
};

const KEYWORD_CAPTIONS: Record<string, string> = {
  none: "未接入",
  es: "Elasticsearch"
};

const GRAPH_CAPTIONS: Record<string, string> = {
  none: "未接入",
  lightrag: "LightRAG"
};

const STORAGE_CAPTIONS: Record<string, string> = {
  s3: "S3 兼容（rustfs / minio）",
  oss: "阿里云 OSS"
};

function formatBytes(bytes: number): string {
  const mb = bytes / 1024 / 1024;
  if (mb >= 1024) {
    return `${(mb / 1024).toFixed(mb % 1024 === 0 ? 0 : 1)} GB`;
  }
  return `${Math.round(mb)} MB`;
}

function formatDurationMs(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms % 60000 === 0) return `${ms / 60000} min`;
  const seconds = ms / 1000;
  return `${Number.isInteger(seconds) ? seconds : seconds.toFixed(1)} s`;
}

function formatPercent(ratio: number): string {
  return `${Math.round(ratio * 100)}%`;
}

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <section className="settings-section">
      <div className="settings-section-head">
        <h2 className="settings-section-title">{title}</h2>
        {hint ? <span className="settings-section-hint">{hint}</span> : null}
      </div>
      {children}
    </section>
  );
}

function StateTag({
  on,
  onText = "启用",
  offText = "未启用",
  tone
}: {
  on: boolean;
  onText?: string;
  offText?: string;
  tone?: "indigo" | "violet";
}) {
  const toneClass = on ? (tone ? `is-${tone}` : "is-on") : "is-off";
  return <span className={cn("settings-tag", toneClass)}>{on ? onText : offText}</span>;
}

function KV({ label, value, mono }: { label: string; value: ReactNode; mono?: boolean }) {
  return (
    <div className="settings-kv">
      <span className="settings-kv-label">{label}</span>
      <div className={cn("settings-kv-value", mono && "font-mono")}>{value}</div>
    </div>
  );
}

function ArchCard({
  icon: Icon,
  tone,
  label,
  value,
  caption,
  off
}: {
  icon: ComponentType<{ className?: string }>;
  tone: Tone;
  label: string;
  value: string;
  caption: string;
  off?: boolean;
}) {
  return (
    <div className={cn("settings-arch-card", off && "is-off")}>
      <span className={cn("settings-icon", off ? "is-slate" : `is-${tone}`)}>
        <Icon className="h-[18px] w-[18px]" />
      </span>
      <div className="min-w-0">
        <div className="settings-arch-label">{label}</div>
        <div className="settings-arch-value">{value}</div>
        <div className="settings-arch-caption">{caption}</div>
      </div>
    </div>
  );
}

function ChannelRow({
  icon: Icon,
  tone,
  name,
  meta,
  channel,
  backendMissing
}: {
  icon: ComponentType<{ className?: string }>;
  tone: Tone;
  name: string;
  meta: string;
  channel: RetrievalChannel;
  backendMissing?: boolean;
}) {
  return (
    <div className={cn("settings-channel", !channel.enabled && "is-off")}>
      <span className={cn("settings-icon h-8 w-8", channel.enabled ? `is-${tone}` : "is-slate")}>
        <Icon className="h-4 w-4" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="settings-channel-name">{name}</div>
        <div className="settings-channel-meta truncate" title={meta}>
          {meta}
        </div>
      </div>
      <span className="settings-channel-weight">权重 {channel.weight.toFixed(1)}</span>
      {channel.enabled && backendMissing ? (
        <span className="settings-tag shrink-0 bg-amber-50 text-amber-600">后端未接入</span>
      ) : (
        <StateTag on={channel.enabled} />
      )}
    </div>
  );
}

function FunnelStage({
  step,
  value,
  unit,
  keyName,
  ratio,
  final
}: {
  step: ReactNode;
  value: string;
  unit?: string;
  keyName: string;
  ratio: number;
  final?: boolean;
}) {
  return (
    <div className={cn("settings-funnel-stage", final && "is-final")}>
      <div className="settings-funnel-step">{step}</div>
      <div className="settings-funnel-value">
        {value}
        {unit ? <span className="settings-funnel-unit">{unit}</span> : null}
      </div>
      <div className="settings-funnel-track">
        <div
          className="settings-funnel-bar"
          style={{ width: `${Math.round(Math.min(1, Math.max(0.08, ratio)) * 100)}%` }}
        />
      </div>
      <code className="settings-funnel-key">{keyName}</code>
    </div>
  );
}

function FunnelArrow() {
  return (
    <div className="settings-funnel-arrow">
      <ChevronRight className="hidden h-4 w-4 md:block" />
      <ChevronDown className="h-4 w-4 md:hidden" />
    </div>
  );
}

function FeaturePill({ label, keyName, on }: { label: string; keyName: string; on: boolean }) {
  return (
    <span className={cn("settings-feature", !on && "is-off")}>
      {on ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Minus className="h-3.5 w-3.5" />}
      {label}
      <code className="settings-feature-key">{keyName}</code>
    </span>
  );
}

function TierChain({ candidates, timeoutMs }: { candidates: string[]; timeoutMs?: number | null }) {
  return (
    <>
      <div className="settings-tier-chain">
        {candidates.map((id, index) => (
          <span key={id} className="contents">
            {index > 0 ? <ChevronRight className="h-3 w-3 text-slate-300" /> : null}
            <span className={cn("settings-chip", index === 0 && "is-primary")}>{id}</span>
          </span>
        ))}
      </div>
      <span className="settings-tier-timeout">
        {timeoutMs != null ? `超时 ${formatDurationMs(timeoutMs)} / 候选` : "不限时"}
      </span>
    </>
  );
}

// embedding/rerank/vlm 共用的候选表，按需展示维度与优先级列
function ModelCandidatesCard({
  title,
  group,
  showDimension,
  showPriority
}: {
  title: string;
  group: ModelGroup;
  showDimension?: boolean;
  showPriority?: boolean;
}) {
  return (
    <div className="settings-card">
      <div className="settings-card-title">
        {title}
        {group.defaultModel ? (
          <span className="settings-card-title-hint">
            默认 <code className="font-mono text-slate-500">{group.defaultModel}</code>
          </span>
        ) : null}
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ID</TableHead>
            <TableHead>Provider</TableHead>
            <TableHead>Model</TableHead>
            {showDimension ? <TableHead className="w-[80px] whitespace-nowrap">维度</TableHead> : null}
            {showPriority ? <TableHead className="w-[80px] whitespace-nowrap">优先级</TableHead> : null}
          </TableRow>
        </TableHeader>
        <TableBody>
          {(group.candidates ?? []).map((item) => (
            <TableRow key={item.id}>
              <TableCell className="whitespace-nowrap font-mono text-xs font-medium">
                <span className="inline-flex items-center gap-1.5">
                  {item.id}
                  {item.id === group.defaultModel ? <StateTag on tone="indigo" onText="默认" /> : null}
                </span>
              </TableCell>
              <TableCell className="text-xs">{item.provider}</TableCell>
              <TableCell className="font-mono text-xs text-slate-600">{item.model}</TableCell>
              {showDimension ? <TableCell className="text-xs tabular-nums">{item.dimension ?? "—"}</TableCell> : null}
              {showPriority ? <TableCell className="text-xs tabular-nums">{item.priority ?? "—"}</TableCell> : null}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">系统配置</h1>
          <p className="admin-page-subtitle">application.yaml 运行时生效配置的只读视图</p>
        </div>
      </div>
      <div className="settings-arch-grid">
        {Array.from({ length: 5 }).map((_, index) => (
          <div key={index} className="h-[88px] animate-pulse rounded-xl border border-slate-200 bg-slate-100/70" />
        ))}
      </div>
      <div className="h-72 animate-pulse rounded-xl border border-slate-200 bg-slate-100/70" />
      <div className="h-56 animate-pulse rounded-xl border border-slate-200 bg-slate-100/70" />
    </div>
  );
}

export function SystemSettingsPage() {
  const [settings, setSettings] = useState<SystemSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const loadSettings = async (silent = false) => {
    const setBusy = silent ? setRefreshing : setLoading;
    try {
      setBusy(true);
      const data = await getSystemSettings();
      setSettings(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载系统配置失败"));
      console.error(error);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    loadSettings();
  }, []);

  if (loading) {
    return <LoadingSkeleton />;
  }

  if (!settings) {
    return (
      <div className="admin-page">
        <div className="settings-card flex flex-col items-center gap-3 py-12 text-center">
          <AlertCircle className="h-8 w-8 text-slate-300" />
          <p className="text-sm text-slate-500">配置加载失败，请检查后端服务是否可用</p>
          <Button variant="outline" size="sm" onClick={() => loadSettings()}>
            重新加载
          </Button>
        </div>
      </div>
    );
  }

  const { engine, backends, rag, ai, upload } = settings;
  const { search, features } = rag;
  const { channels, fusion, scope } = search;

  const keywordMissing = backends.keyword.type === "none";
  const graphMissing = backends.graph.type === "none";
  const enabledChannelCount = [
    channels.vector.enabled,
    channels.keyword.enabled,
    channels.graph.enabled,
    channels.webSearch.enabled
  ].filter(Boolean).length;

  // 漏斗条宽按「相对融合池总量」计算：池 = 启用通道数 × 每通道召回
  const fusionPool = Math.max(1, enabledChannelCount) * search.recallBudget;
  const rerankLimit = fusion.rerankCandidateLimit;

  const providers = Object.entries(ai.providers ?? {});
  const tiers = Object.entries(ai.chat.tiers ?? {});
  const vlm = ai.vlm && ai.vlm.candidates?.length ? ai.vlm : null;

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">系统配置</h1>
          <p className="admin-page-subtitle">application.yaml 运行时生效配置的只读视图，修改后需重启服务</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" size="sm" onClick={() => loadSettings(true)} disabled={refreshing}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", refreshing && "animate-spin")} />
            刷新
          </Button>
        </div>
      </div>

      <Section title="架构选型" hint="引擎与四类后端组件，切换需修改 yaml 并重启">
        <div className="settings-arch-grid">
          <ArchCard
            icon={Cpu}
            tone="cyan"
            label="执行引擎"
            value={engine.type}
            caption={ENGINE_CAPTIONS[engine.type] ?? "自定义档位"}
          />
          <ArchCard
            icon={Database}
            tone="indigo"
            label="向量库"
            value={backends.vector.type}
            caption={VECTOR_CAPTIONS[backends.vector.type] ?? backends.vector.type}
          />
          <ArchCard
            icon={KeyRound}
            tone="amber"
            label="关键词引擎"
            value={backends.keyword.type}
            caption={KEYWORD_CAPTIONS[backends.keyword.type] ?? backends.keyword.type}
            off={keywordMissing}
          />
          <ArchCard
            icon={Share2}
            tone="emerald"
            label="知识图谱"
            value={backends.graph.type}
            caption={GRAPH_CAPTIONS[backends.graph.type] ?? backends.graph.type}
            off={graphMissing}
          />
          <ArchCard
            icon={HardDrive}
            tone="sky"
            label="对象存储"
            value={backends.storage.type}
            caption={STORAGE_CAPTIONS[backends.storage.type] ?? backends.storage.type}
          />
        </div>
      </Section>

      <Section title="检索管线" hint={`${enabledChannelCount} / 4 通道启用 · 单通道超时 ${formatDurationMs(channels.timeoutMs)}`}>
        <div className="settings-card">
          <div className="settings-pipeline">
            <div>
              <div className="settings-subhead">
                检索通道
                <span className="settings-subhead-hint">rag.search.channels</span>
              </div>
              <div className="settings-channel-list">
                <ChannelRow
                  icon={Database}
                  tone="indigo"
                  name="向量检索"
                  meta={VECTOR_SHORT_CAPTIONS[backends.vector.type] ?? backends.vector.type}
                  channel={channels.vector}
                />
                <ChannelRow
                  icon={KeyRound}
                  tone="amber"
                  name="关键词检索"
                  meta={keywordMissing ? "后端未接入" : `Elasticsearch · ${backends.keyword.index}`}
                  channel={channels.keyword}
                  backendMissing={keywordMissing}
                />
                <ChannelRow
                  icon={Share2}
                  tone="emerald"
                  name="图谱检索"
                  meta={graphMissing ? "后端未接入" : `LightRAG · ${backends.graph.queryMode}`}
                  channel={channels.graph}
                  backendMissing={graphMissing}
                />
                <ChannelRow
                  icon={Globe}
                  tone="sky"
                  name="联网检索"
                  meta={
                    channels.webSearch.apiKeyConfigured
                      ? `You.com · 每次 ${channels.webSearch.count} 条`
                      : "API Key 未配置"
                  }
                  channel={channels.webSearch}
                />
              </div>
            </div>
            <div>
              <div className="settings-subhead">
                收窄漏斗（召回 → 融合 → 精排 → 上下文）
                <span className="settings-subhead-hint">单调收窄，启动时校验</span>
              </div>
              <div className="settings-funnel">
                <FunnelStage
                  step="多路召回"
                  value={String(search.recallBudget)}
                  unit="条 / 通道"
                  keyName="recall-budget"
                  ratio={1}
                />
                <FunnelArrow />
                <FunnelStage
                  step={
                    <span className="inline-flex items-center gap-1.5">
                      {fusion.strategy.toUpperCase()} 融合
                    </span>
                  }
                  value={`k=${fusion.rrfK}`}
                  keyName="fusion.rrf-k"
                  ratio={1}
                />
                <FunnelArrow />
                <FunnelStage
                  step={
                    <span className="inline-flex items-center gap-1.5">
                      Rerank 精排
                      {!features.rerank ? <StateTag on={false} /> : null}
                    </span>
                  }
                  value={rerankLimit > 0 ? `≤ ${rerankLimit}` : "全量"}
                  unit={rerankLimit > 0 ? "条候选" : undefined}
                  keyName="rerank-candidate-limit"
                  ratio={rerankLimit > 0 ? rerankLimit / fusionPool : 1}
                />
                <FunnelArrow />
                <FunnelStage
                  step="进入上下文"
                  value={String(search.defaultTopK)}
                  unit="条"
                  keyName="default-top-k"
                  ratio={search.defaultTopK / fusionPool}
                  final
                />
              </div>
              <div className="settings-scope">
                <KV label="意图分下限" value={scope.minIntentScore.toFixed(2)} mono />
                <KV label="收窄置信阈值" value={scope.confidenceThreshold.toFixed(2)} mono />
                <KV label="补充路配额" value={formatPercent(scope.supplementRatio)} mono />
                <p className="settings-scope-note">
                  检索作用域（rag.search.scope）：知识库意图最高分达到阈值时收窄到命中库，并按配额给未命中库留保底名额；低于阈值退化为全库检索
                </p>
              </div>
            </div>
          </div>
          <div className="settings-feature-row">
            <FeaturePill label="查询改写" keyName="query-rewrite" on={features.queryRewrite} />
            <FeaturePill label="Rerank 精排" keyName="rerank" on={features.rerank} />
            <FeaturePill label="行内引用" keyName="citation" on={features.citation} />
            <FeaturePill label="上下文富化" keyName="context-enrich" on={features.contextEnrich} />
            <FeaturePill label="链路追踪" keyName="trace" on={features.trace} />
          </div>
        </div>
      </Section>

      <Section
        title="模型服务"
        hint={`${providers.length} 个 Provider · ${ai.chat.candidates?.length ?? 0} 个 Chat 候选`}
      >
        <div className="settings-card">
          <div className="settings-card-title">
            Chat 档位路由
            <span className="settings-card-title-hint">
              默认 <code className="font-mono text-slate-500">{ai.chat.defaultTier ?? "—"}</code>
              <span className="mx-1.5 text-slate-200">|</span>
              深度思考 <code className="font-mono text-slate-500">{ai.chat.deepThinkingTier ?? "—"}</code>
            </span>
          </div>
          <div className="space-y-2">
            {tiers.map(([name, tier]) => (
              <div key={name} className="settings-tier">
                <span className="settings-tier-name">
                  {name}
                  {name === ai.chat.defaultTier ? <StateTag on tone="indigo" onText="默认" /> : null}
                  {name === ai.chat.deepThinkingTier ? <StateTag on tone="violet" onText="深度思考" /> : null}
                </span>
                <TierChain candidates={tier.candidates ?? []} timeoutMs={tier.timeoutMs} />
              </div>
            ))}
          </div>
          <div className="mt-5">
            <div className="settings-subhead">
              候选注册表
              <span className="settings-subhead-hint">档位内从左到右依次故障转移</span>
            </div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>Provider</TableHead>
                  <TableHead>Model</TableHead>
                  <TableHead className="w-[100px] whitespace-nowrap">深度思考</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(ai.chat.candidates ?? []).map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="whitespace-nowrap font-mono text-xs font-medium">{item.id}</TableCell>
                    <TableCell className="text-xs">{item.provider}</TableCell>
                    <TableCell className="font-mono text-xs text-slate-600">{item.model}</TableCell>
                    <TableCell>
                      {item.supportsThinking ? <StateTag on tone="violet" onText="支持" /> : <span className="text-xs text-slate-300">—</span>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>

        <div className="grid items-start gap-4 xl:grid-cols-2">
          <ModelCandidatesCard title="Embedding 模型" group={ai.embedding} showDimension showPriority />
          <div className="space-y-4">
            <ModelCandidatesCard title="Rerank 模型" group={ai.rerank} showPriority />
            {vlm ? <ModelCandidatesCard title="视觉模型（VLM）" group={vlm} /> : null}
          </div>
        </div>

        <div className="grid gap-4 xl:grid-cols-3">
          <div className="settings-card xl:col-span-2">
            <div className="settings-card-title">模型服务提供方</div>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Provider</TableHead>
                  <TableHead>URL</TableHead>
                  <TableHead>API Key</TableHead>
                  <TableHead>Endpoints</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {providers.map(([name, provider]) => (
                  <TableRow key={name}>
                    <TableCell className="text-xs font-medium">{name}</TableCell>
                    <TableCell className="font-mono text-xs text-slate-600">{provider.url}</TableCell>
                    <TableCell className="font-mono text-xs text-slate-500">{provider.apiKey ?? "—"}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {Object.keys(provider.endpoints ?? {}).map((key) => (
                          <span key={key} className="settings-chip">
                            {key}
                          </span>
                        ))}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="settings-card">
            <div className="settings-card-title">调度与流式</div>
            <div className="space-y-3">
              <KV label="熔断失败阈值" value={`${ai.selection.failureThreshold} 次`} />
              <KV label="熔断恢复时长" value={formatDurationMs(ai.selection.openDurationMs)} />
              <KV label="流式分片大小" value={`${ai.stream.messageChunkSize} 字符`} />
            </div>
          </div>
        </div>
      </Section>

      <Section title="运行时参数" hint="向量空间、会话记忆与资源限额">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <div className="settings-card">
            <div className="settings-card-title">向量空间</div>
            <div className="settings-kv-grid">
              <KV label="Collection" value={rag.default.collectionName} mono />
              <KV label="向量维度" value={rag.default.dimension} mono />
              <KV label="相似度度量" value={rag.default.metricType} mono />
              <KV label="SSE 超时" value={formatDurationMs(rag.default.sseTimeoutMs)} />
            </div>
          </div>
          <div className="settings-card">
            <div className="settings-card-title">
              会话记忆
              <span className="settings-card-title-hint">
                <StateTag on={rag.memory.summaryEnabled} onText="摘要压缩启用" offText="摘要压缩关闭" />
              </span>
            </div>
            <div className="settings-kv-grid">
              <KV label="历史保留轮数" value={`${rag.memory.historyKeepTurns} 轮`} />
              <KV label="摘要起始轮数" value={`第 ${rag.memory.summaryStartTurns} 轮`} />
              <KV label="摘要长度上限" value={`${rag.memory.summaryMaxChars} 字`} />
              <KV label="会话标题上限" value={`${rag.memory.titleMaxLength} 字`} />
            </div>
          </div>
          <div className="settings-card">
            <div className="settings-card-title">
              全局限流
              <span className="settings-card-title-hint">
                <StateTag on={rag.rateLimit.global.enabled} />
              </span>
            </div>
            <div className="settings-kv-grid">
              <KV label="最大并发" value={rag.rateLimit.global.maxConcurrent} mono />
              <KV label="最长等待" value={`${rag.rateLimit.global.maxWaitSeconds} s`} />
              <KV label="租约时长" value={`${rag.rateLimit.global.leaseSeconds} s`} />
              <KV label="轮询间隔" value={formatDurationMs(rag.rateLimit.global.pollIntervalMs)} />
            </div>
          </div>
          <div className="settings-card">
            <div className="settings-card-title">对象存储</div>
            <div className="settings-kv-grid">
              <div className="sm:col-span-2">
                <KV label="Endpoint" value={backends.storage.endpoint ?? "—"} mono />
              </div>
              <div className="sm:col-span-2">
                <KV label="公网基址" value={backends.storage.publicUrl ?? "—"} mono />
              </div>
              <KV label="知识库桶（私有）" value={backends.storage.kbBucket} mono />
              <KV label="资产桶（公共读）" value={backends.storage.assetBucket} mono />
            </div>
          </div>
          {!keywordMissing ? (
            <div className="settings-card">
              <div className="settings-card-title">Elasticsearch</div>
              <div className="settings-kv-grid">
                <KV label="地址" value={backends.keyword.uris ?? "—"} mono />
                <KV label="共享索引" value={backends.keyword.index ?? "—"} mono />
                <KV label="写入分词器" value={backends.keyword.analyzer ?? "—"} mono />
                <KV label="查询分词器" value={backends.keyword.searchAnalyzer ?? "—"} mono />
              </div>
            </div>
          ) : null}
          {!graphMissing ? (
            <div className="settings-card">
              <div className="settings-card-title">LightRAG</div>
              <div className="settings-kv-grid">
                <KV label="服务地址" value={backends.graph.baseUrl ?? "—"} mono />
                <KV label="查询模式" value={backends.graph.queryMode ?? "—"} mono />
                <KV label="Embedding 模型" value={backends.graph.embeddingModel || "—"} mono />
              </div>
            </div>
          ) : null}
          <div className="settings-card">
            <div className="settings-card-title">上传限额</div>
            <div className="settings-kv-grid">
              <KV label="单文件上限" value={formatBytes(upload.maxFileSize)} />
              <KV label="单请求上限" value={formatBytes(upload.maxRequestSize)} />
            </div>
          </div>
        </div>
      </Section>
    </div>
  );
}
