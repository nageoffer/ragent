import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Clock, Layers, MoreHorizontal, Pencil, Plus, RefreshCw, Settings2, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { OrchestrationMode, AgentProfile } from "@/services/agentProfileService";
import {
  activateAgentProfile,
  createAgentProfile,
  deleteAgentProfile,
  getAgentProfiles,
  updateAgentProfile
} from "@/services/agentProfileService";
import { getErrorMessage } from "@/utils/error";
import { RelativeTime } from "@/components/RelativeTime";
import { AGENT_AVATARS, AgentAvatar, randomAvatarKey } from "@/components/admin/AgentAvatar";
import { AgentModeLabel, OTHER_MODE_LABEL } from "@/components/admin/AgentModeLabel";
import { cn } from "@/lib/utils";

const emptyForm = { name: "", description: "", avatar: AGENT_AVATARS[0].key };

// 分母只数当前架构跑得到的槽位，另一模式的预写内容不进分子，免得"已自定义 1"其实一条都不会变
function coverage(agent: AgentProfile, mode: OrchestrationMode, total: number) {
  const effective = agent.effectiveSlots ?? 0;
  const pending = agent.inactiveSlots ?? 0;
  // 分数只在"改了一部分"时才有意义，全配和全默认直接说人话
  let label: string;
  if (agent.builtin) {
    // 内置助手本身就是默认，说它"自定义"了几条是错的
    label = `已配置 ${effective}/${total}`;
  } else if (effective === total) {
    label = "全部自定义";
  } else if (effective === 0 && pending === 0) {
    label = "全部沿用默认";
  } else {
    label = `已自定义 ${effective}/${total}`;
  }
  // 卡片一行只有两百来像素，跨模式那句话塞不下，压成 +N 徽标挂 title
  return {
    label,
    pending,
    hint: `另有 ${pending} 条已填写，仅 ${OTHER_MODE_LABEL[mode]} 架构下生效`
  };
}

export function AgentProfilePage() {
  const navigate = useNavigate();
  const [agents, setAgents] = useState<AgentProfile[]>([]);
  const [mode, setMode] = useState<OrchestrationMode>("WORKFLOW");
  const [slotTotal, setSlotTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [dialog, setDialog] = useState<{ open: boolean; target: AgentProfile | null }>({
    open: false,
    target: null
  });
  const [form, setForm] = useState(emptyForm);
  const [submitting, setSubmitting] = useState(false);
  const [activatingId, setActivatingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AgentProfile | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getAgentProfiles();
      setAgents(result.agents || []);
      setMode(result.mode);
      setSlotTotal(result.effectiveSlotTotal ?? 0);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载智能体列表失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    setForm({ ...emptyForm, avatar: randomAvatarKey() });
    setDialog({ open: true, target: null });
  };

  const openEdit = (agent: AgentProfile) => {
    setForm({
      name: agent.name,
      description: agent.description || "",
      avatar: agent.avatar || randomAvatarKey()
    });
    setDialog({ open: true, target: agent });
  };

  const handleSubmit = async () => {
    if (!form.name.trim()) {
      toast.error("请填写智能体名称");
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        name: form.name.trim(),
        description: form.description.trim() || null,
        avatar: form.avatar
      };
      if (dialog.target) {
        await updateAgentProfile(dialog.target.id, payload);
        toast.success("已更新");
      } else {
        await createAgentProfile(payload);
        toast.success("已创建，接下来配置提示词");
      }
      setDialog({ open: false, target: null });
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSubmitting(false);
    }
  };

  const handleActivate = async (agent: AgentProfile) => {
    setActivatingId(agent.id);
    try {
      await activateAgentProfile(agent.id);
      toast.success(`已激活「${agent.name}」，立即对全部会话生效`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "激活失败"));
    } finally {
      setActivatingId(null);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) {
      return;
    }
    try {
      await deleteAgentProfile(deleteTarget.id);
      toast.success("已删除");
      setDeleteTarget(null);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">智能体管理</h1>
          <p className="admin-page-subtitle">
            <AgentModeLabel mode={mode} />
            管理面向用户的智能体配置，同一时间仅一个智能体生效
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={cn("mr-2 h-4 w-4", loading && "animate-spin")} />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreate}>
            <Plus className="mr-2 h-4 w-4" />
            新建智能体
          </Button>
        </div>
      </div>

      <div className="agent-grid">
        {loading && agents.length === 0
          ? [0, 1, 2].map((index) => (
              <div key={index} className="agent-card agent-card--skeleton" />
            ))
          : agents.map((agent) => {
              const activating = activatingId === agent.id;
              const slots = coverage(agent, mode, slotTotal);
              return (
                <article
                  key={agent.id}
                  className={cn("agent-card", agent.active && "agent-card--active")}
                >
                  <header className="flex items-start gap-3">
                    <AgentAvatar avatar={agent.avatar} seed={agent.id} />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <h2 className="truncate text-[15px] font-semibold text-slate-900">
                          {agent.name}
                        </h2>
                        {agent.builtin ? (
                          <Badge variant="secondary" className="shrink-0 text-[11px] font-normal">
                            内置
                          </Badge>
                        ) : null}
                      </div>
                      <p className="agent-card__desc">{agent.description || "未填写描述"}</p>
                    </div>
                    {agent.active ? (
                      <span className="agent-card__live">
                        <span className="agent-card__live-dot" />
                        生效中
                      </span>
                    ) : null}
                  </header>

                  <div className="agent-card__meta">
                    {slotTotal ? (
                      <span className="agent-card__chip">
                        <Layers className="h-3 w-3 shrink-0 text-slate-400" />
                        {slots.label}
                        {slots.pending ? (
                          <span className="agent-card__pending" title={slots.hint}>
                            +{slots.pending}
                          </span>
                        ) : null}
                      </span>
                    ) : null}
                    <span className="agent-card__chip">
                      {/* 时钟图标已经说明这是时间，再写「更新 · 」白占 32px，跨年时间戳会把胶囊挤换行 */}
                      <Clock className="h-3 w-3 shrink-0 text-slate-400" />
                      <RelativeTime value={agent.updateTime} className="text-[11px]" />
                    </span>
                  </div>

                  <footer className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      className="flex-1"
                      onClick={() => navigate(`/admin/agents/${agent.id}`)}
                    >
                      <Settings2 className="mr-1.5 h-3.5 w-3.5" />
                      配置提示词
                    </Button>
                    {agent.active ? null : (
                      <Button
                        size="sm"
                        className="admin-primary-gradient"
                        disabled={activating}
                        onClick={() => void handleActivate(agent)}
                      >
                        {activating ? "激活中…" : "设为生效"}
                      </Button>
                    )}
                    {agent.builtin ? null : (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="sm" className="px-2" aria-label="更多操作">
                            <MoreHorizontal className="h-4 w-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onClick={() => openEdit(agent)}>
                            <Pencil className="mr-2 h-3.5 w-3.5" />
                            编辑名称描述
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            className="text-destructive focus:text-destructive"
                            onClick={() => setDeleteTarget(agent)}
                          >
                            <Trash2 className="mr-2 h-3.5 w-3.5" />
                            删除
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )}
                  </footer>
                </article>
              );
            })}

        {loading && agents.length === 0 ? null : (
          <button type="button" className="agent-card agent-card--add" onClick={openCreate}>
            <span className="agent-card__add-icon">
              <Plus className="h-5 w-5" />
            </span>
            <span className="text-sm font-medium text-slate-600">新建智能体</span>
            <span className="text-xs text-slate-400">先整套沿用「默认助手」，你改哪条生效哪条</span>
          </button>
        )}
      </div>

      <Dialog open={dialog.open} onOpenChange={(open) => setDialog((prev) => ({ ...prev, open }))}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{dialog.target ? "编辑智能体" : "新建智能体"}</DialogTitle>
            {/* 「知识库不随人设走」是刻意设计，放这儿——用户真正会踩坑的是建号那一刻，不是每次进列表页 */}
            <DialogDescription>
              新建后整套沿用「默认助手」，你改哪条生效哪条；知识库与工具边界仍由意图树决定，不随人设走
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">头像</label>
              <div className="flex flex-wrap gap-1.5">
                {AGENT_AVATARS.map((preset) => (
                  <button
                    key={preset.key}
                    type="button"
                    aria-label={preset.key}
                    aria-pressed={form.avatar === preset.key}
                    onClick={() => setForm((prev) => ({ ...prev, avatar: preset.key }))}
                    className={cn(
                      "inline-flex rounded-xl border-2 border-transparent p-0.5 transition-transform hover:scale-105",
                      form.avatar === preset.key && "border-indigo-500"
                    )}
                  >
                    <AgentAvatar
                      avatar={preset.key}
                      className="h-8 w-8 rounded-[10px]"
                      iconClassName="h-4 w-4"
                    />
                  </button>
                ))}
              </div>
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">名称</label>
              <Input
                value={form.name}
                onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
                placeholder="如：智能客服 / 购物助手"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Textarea
                value={form.description}
                onChange={(event) =>
                  setForm((prev) => ({ ...prev, description: event.target.value }))
                }
                placeholder="这个智能体面向什么场景"
                className="agent-form-textarea min-h-[96px]"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog({ open: false, target: null })}>
              取消
            </Button>
            <Button
              className="admin-primary-gradient"
              onClick={() => void handleSubmit()}
              disabled={submitting}
            >
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除智能体</AlertDialogTitle>
            <AlertDialogDescription>
              将删除「{deleteTarget?.name}」及其全部提示词，该操作不可撤销
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleDelete()}>删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
