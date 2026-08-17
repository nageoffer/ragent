import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Copy, Lock, RotateCcw, Save } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import type { OrchestrationMode, AgentPromptSlot, SlotGroup } from "@/services/agentProfileService";
import {
  getAgentPrompts,
  getDefaultPrompt,
  saveAgentPrompt
} from "@/services/agentProfileService";
import { getErrorMessage } from "@/utils/error";
import { AgentModeLabel } from "@/components/admin/AgentModeLabel";
import { cn } from "@/lib/utils";

const GROUP_ORDER: SlotGroup[] = ["WORKFLOW", "AGENT", "COMMON"];

const GROUP_HINT: Record<SlotGroup, string> = {
  WORKFLOW: "仅 WorkFlow 架构下生效",
  AGENT: "仅 Agent 架构下生效，v2 落地前填写的内容暂不参与运行",
  COMMON: "两种架构下都生效"
};

export function AgentPromptPage() {
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();

  const [agentName, setAgentName] = useState("");
  const [defaultName, setDefaultName] = useState("默认助手");
  const [mode, setMode] = useState<OrchestrationMode>("WORKFLOW");
  const [builtin, setBuiltin] = useState(false);
  const [slots, setSlots] = useState<AgentPromptSlot[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!agentId) {
      return;
    }
    setLoading(true);
    try {
      const config = await getAgentPrompts(agentId);
      setAgentName(config.agentName || "");
      setDefaultName(config.defaultAgentName || "默认助手");
      setMode(config.mode);
      setBuiltin(config.builtin);
      setSlots(config.slots || []);
      setDrafts(
        Object.fromEntries((config.slots || []).map((slot) => [slot.slotKey, slot.content || ""]))
      );
      setActiveKey(
        (config.slots || []).find((slot) => slot.effective)?.slotKey ||
          config.slots?.[0]?.slotKey ||
          null
      );
    } catch (error) {
      toast.error(getErrorMessage(error, "加载提示词配置失败"));
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    void load();
  }, [load]);

  const grouped = useMemo(
    () =>
      GROUP_ORDER.map((group) => ({
        group,
        slots: slots.filter((slot) => slot.group === group)
      })).filter((entry) => entry.slots.length > 0),
    [slots]
  );

  const activeSlot = useMemo(
    () => slots.find((slot) => slot.slotKey === activeKey) || null,
    [slots, activeKey]
  );

  const draft = activeSlot ? drafts[activeSlot.slotKey] ?? "" : "";
  const dirty = Boolean(activeSlot) && draft !== (activeSlot?.content || "");
  const isDirty = useCallback(
    (slot: AgentPromptSlot) => (drafts[slot.slotKey] ?? "") !== (slot.content || ""),
    [drafts]
  );

  // 占位符缺失后端会拒绝保存，这里边打边标出来，省一次失败往返
  const missingPlaceholders = useMemo(() => {
    if (!activeSlot || !draft.trim()) {
      return [];
    }
    return activeSlot.requiredPlaceholders.filter((placeholder) => !draft.includes(placeholder));
  }, [activeSlot, draft]);

  const handleSave = useCallback(async () => {
    if (!agentId || !activeSlot || builtin) {
      return;
    }
    const content = drafts[activeSlot.slotKey] ?? "";
    setSaving(true);
    try {
      await saveAgentPrompt(agentId, activeSlot.slotKey, content);
      // 只回填当前槽位，整页重载会把其他槽位的草稿冲掉
      setSlots((prev) =>
        prev.map((slot) => (slot.slotKey === activeSlot.slotKey ? { ...slot, content } : slot))
      );
      toast.success(`「${activeSlot.displayName}」已保存`);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  }, [agentId, activeSlot, builtin, drafts]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (dirty && !saving) {
          void handleSave();
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [dirty, saving, handleSave]);

  const handleCopyDefault = async () => {
    if (!activeSlot) {
      return;
    }
    try {
      const content = await getDefaultPrompt(activeSlot.slotKey);
      if (!content) {
        toast.error(`「${defaultName}」没有配置这条，无可填入的内容`);
        return;
      }
      setDrafts((prev) => ({ ...prev, [activeSlot.slotKey]: content }));
      toast.success(`已填入「${defaultName}」的内容，确认后点击保存`);
    } catch (error) {
      toast.error(getErrorMessage(error, "读取默认内容失败"));
    }
  };

  const handleRevert = () => {
    if (!activeSlot) {
      return;
    }
    setDrafts((prev) => ({ ...prev, [activeSlot.slotKey]: activeSlot.content || "" }));
  };

  const stats = useMemo(() => {
    const chars = draft.length;
    const lines = draft ? draft.split("\n").length : 0;
    return `${chars.toLocaleString("zh-CN")} 字 · ${lines} 行`;
  }, [draft]);

  return (
    <div className="agent-prompt-page">
      <div className="admin-page-header agent-prompt-page__header">
        <div className="min-w-0">
          <h1 className="admin-page-title truncate">{agentName || "提示词配置"}</h1>
          <p className="admin-page-subtitle truncate">
            <AgentModeLabel mode={mode} />
            {builtin
              ? "这里是所有智能体的默认内容，不可编辑；如需调整请复制一份新建"
              : `清空即恢复为「${defaultName}」的内容，⌘/Ctrl + S 保存当前这条`}
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate("/admin/agents")}>
            <ArrowLeft className="mr-2 h-4 w-4" />
            返回列表
          </Button>
        </div>
      </div>

      <div className="agent-prompt-layout">
        <aside className="agent-prompt-rail">
          {grouped.map(({ group, slots: groupSlots }) => (
            <div key={group} className="agent-prompt-rail__group">
              <div className="agent-prompt-rail__group-title" title={GROUP_HINT[group]}>
                {groupSlots[0].groupName}
              </div>
              {groupSlots.map((slot) => {
                const covered = Boolean((drafts[slot.slotKey] ?? slot.content ?? "").trim());
                return (
                  <button
                    key={slot.slotKey}
                    type="button"
                    onClick={() => setActiveKey(slot.slotKey)}
                    className={cn(
                      "agent-prompt-rail__item",
                      slot.slotKey === activeKey && "agent-prompt-rail__item--active"
                    )}
                  >
                    <span
                      className={cn(
                        "agent-prompt-dot",
                        slot.effective ? "agent-prompt-dot--on" : "agent-prompt-dot--off"
                      )}
                      title={slot.effective ? "当前架构下生效" : slot.inactiveReason || "当前架构下不生效"}
                    />
                    <span className="flex-1 truncate">{slot.displayName}</span>
                    {isDirty(slot) ? (
                      <span className="agent-prompt-rail__flag" title="有未保存的改动">
                        未保存
                      </span>
                    ) : covered ? null : (
                      <span
                        className="agent-prompt-rail__fallback"
                        title={`未自定义，沿用「${defaultName}」`}
                      >
                        默认
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          ))}

          <div className="agent-prompt-rail__legend">
            <span>
              <span className="agent-prompt-dot agent-prompt-dot--on" />
              当前架构下生效
            </span>
            <span>
              <span className="agent-prompt-dot agent-prompt-dot--off" />
              当前架构下不生效
            </span>
          </div>
        </aside>

        <section className="agent-prompt-pane">
          {activeSlot ? (
            <>
              <header className="agent-prompt-pane__header">
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <h2 className="text-sm font-semibold text-slate-900">{activeSlot.displayName}</h2>
                  {activeSlot.effective ? (
                    <Badge className="text-[11px] font-normal">当前生效</Badge>
                  ) : (
                    <Badge variant="secondary" className="text-[11px] font-normal">
                      当前不生效{activeSlot.inactiveReason ? `·${activeSlot.inactiveReason}` : ""}
                    </Badge>
                  )}
                  {draft.trim() ? null : (
                    <Badge variant="outline" className="text-[11px] font-normal">
                      沿用{defaultName}
                    </Badge>
                  )}
                  {builtin ? (
                    <span className="inline-flex items-center gap-1 text-xs text-slate-400">
                      <Lock className="h-3 w-3" />
                      只读
                    </span>
                  ) : null}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Button variant="outline" size="sm" disabled={builtin} onClick={() => void handleCopyDefault()}>
                    <Copy className="mr-1.5 h-3.5 w-3.5" />
                    填入默认内容
                  </Button>
                  <Button variant="outline" size="sm" disabled={builtin || !dirty} onClick={handleRevert}>
                    <RotateCcw className="mr-1.5 h-3.5 w-3.5" />
                    撤销改动
                  </Button>
                  <Button
                    size="sm"
                    className="admin-primary-gradient"
                    disabled={builtin || !dirty || saving}
                    onClick={() => void handleSave()}
                  >
                    <Save className="mr-1.5 h-3.5 w-3.5" />
                    {saving ? "保存中…" : "保存"}
                  </Button>
                </div>
              </header>

              {activeSlot.requiredPlaceholders.length > 0 ? (
                <div className="agent-prompt-pane__placeholders">
                  <span className="text-slate-400">必需占位符</span>
                  {activeSlot.requiredPlaceholders.map((placeholder) => (
                    <code
                      key={placeholder}
                      className={cn(
                        "agent-prompt-placeholder",
                        missingPlaceholders.includes(placeholder) && "agent-prompt-placeholder--missing"
                      )}
                    >
                      {placeholder}
                    </code>
                  ))}
                  {missingPlaceholders.length > 0 ? (
                    <span className="text-rose-500">缺失的占位符会导致保存被拒绝</span>
                  ) : null}
                </div>
              ) : null}

              <Textarea
                value={draft}
                readOnly={builtin}
                spellCheck={false}
                onChange={(event) =>
                  setDrafts((prev) => ({ ...prev, [activeSlot.slotKey]: event.target.value }))
                }
                placeholder={
                  builtin ? "内置智能体不可编辑" : `留空则沿用「${defaultName}」的这条内容`
                }
                className="agent-prompt-textarea flex-1 resize-none rounded-none border-0 bg-transparent font-mono text-xs leading-relaxed shadow-none focus-visible:ring-0"
              />

              <footer className="agent-prompt-pane__footer">
                <span>{stats}</span>
                <span className={cn(dirty ? "text-amber-600" : "text-slate-400")}>
                  {dirty ? "有未保存的改动" : "已与服务端一致"}
                </span>
              </footer>
            </>
          ) : (
            <div className="flex flex-1 items-center justify-center text-sm text-slate-400">
              {loading ? "加载中…" : "暂无可配置的提示词"}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
