import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Info, Save } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import type { AgentSkillToolOption } from "@/services/agentSkillService";
import {
  createAgentSkill,
  getAgentSkill,
  getAgentSkillToolOptions,
  updateAgentSkill
} from "@/services/agentSkillService";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";

const SKILL_CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

const CONTENT_PLACEHOLDER = `## 什么时候用
员工说「我要请假」「帮我请个假」时按本手册办

## 办事步骤
1. 先问清楚请假类型、起止日期、事由，缺一项就追问，不要替用户猜
2. 用 leave_submit 提交申请，提交前会弹确认卡片给用户核对
3. 提交成功后把申请单号回给用户

## 注意
- 事假和年假的审批人不同，事假报直属主管，年假报 HR
- 用户没说清日期时不要按今天默认`;

const emptyForm = {
  skillCode: "",
  name: "",
  description: "",
  content: "",
  toolIds: [] as string[],
  sortOrder: 0,
  enabled: true
};

export function AgentSkillEditPage() {
  const { skillId } = useParams<{ skillId: string }>();
  const navigate = useNavigate();
  const creating = !skillId || skillId === "new";

  const [form, setForm] = useState(emptyForm);
  const [toolOptions, setToolOptions] = useState<AgentSkillToolOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [options, skill] = await Promise.all([
        getAgentSkillToolOptions(),
        creating ? Promise.resolve(null) : getAgentSkill(skillId as string)
      ]);
      setToolOptions(options);
      if (skill) {
        setForm({
          skillCode: skill.skillCode,
          name: skill.name,
          description: skill.description,
          content: skill.content || "",
          toolIds: skill.toolIds || [],
          sortOrder: skill.sortOrder ?? 0,
          enabled: skill.enabled ?? true
        });
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "加载技能失败"));
    } finally {
      setLoading(false);
    }
  }, [creating, skillId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 工具已被别的技能收走时仍要能勾选：多个技能共用一个工具是允许的，这里只做提示
  const takenBy = useCallback(
    (option: AgentSkillToolOption) => {
      if (!option.referencedBySkillName) return null;
      if (!creating && option.referencedBySkillName === form.name) return null;
      return option.referencedBySkillName;
    },
    [creating, form.name]
  );

  const toggleTool = (toolId: string) => {
    setForm((prev) => ({
      ...prev,
      toolIds: prev.toolIds.includes(toolId)
        ? prev.toolIds.filter((id) => id !== toolId)
        : [...prev.toolIds, toolId]
    }));
  };

  const stats = useMemo(() => {
    const chars = form.content.length;
    const lines = form.content ? form.content.split("\n").length : 0;
    return `${chars.toLocaleString("zh-CN")} 字 · ${lines} 行`;
  }, [form.content]);

  const handleSave = useCallback(async () => {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim(),
      content: form.content.trim(),
      toolIds: form.toolIds,
      sortOrder: form.sortOrder,
      enabled: form.enabled
    };

    if (creating && !SKILL_CODE_PATTERN.test(form.skillCode.trim())) {
      toast.error("技能标识需为小写字母开头的字母、数字与下划线，长度 2~64");
      return;
    }
    if (!payload.name) {
      toast.error("请填写技能名称");
      return;
    }
    if (!payload.description) {
      toast.error("请填写适用场景，模型靠它判断何时该用这个技能");
      return;
    }
    if (!payload.content) {
      toast.error("请填写技能正文");
      return;
    }

    setSaving(true);
    try {
      if (creating) {
        await createAgentSkill({ ...payload, skillCode: form.skillCode.trim() });
        toast.success("创建成功");
        navigate("/admin/agent-skills");
        return;
      }
      await updateAgentSkill(skillId as string, payload);
      toast.success("保存成功");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  }, [creating, form, navigate, skillId]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (!saving) {
          void handleSave();
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [handleSave, saving]);

  return (
    <div className="agent-skill-page">
      <div className="admin-page-header agent-skill-page__header">
        <div className="min-w-0">
          <h1 className="admin-page-title truncate">{creating ? "新建技能" : form.name || "技能配置"}</h1>
          <p className="admin-page-subtitle truncate">
            清单里只放名称和适用场景，模型判断对得上才展开正文，⌘/Ctrl + S 保存
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate("/admin/agent-skills")}>
            <ArrowLeft className="mr-2 h-4 w-4" />
            返回列表
          </Button>
          <Button className="admin-primary-gradient" disabled={saving || loading} onClick={() => void handleSave()}>
            <Save className="mr-2 h-4 w-4" />
            {saving ? "保存中…" : "保存"}
          </Button>
        </div>
      </div>

      <div className="agent-skill-layout">
        <aside className="agent-skill-side">
          <div className="agent-skill-field">
            <label className="agent-skill-field__label">技能名称 *</label>
            <Input
              value={form.name}
              onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
              placeholder="如：请假办理"
            />
          </div>

          <div className="agent-skill-field">
            <label className="agent-skill-field__label">技能标识 *</label>
            <Input
              value={form.skillCode}
              disabled={!creating}
              onChange={(event) => setForm((prev) => ({ ...prev, skillCode: event.target.value }))}
              placeholder="如：leave_apply"
              className="font-mono text-xs"
            />
            <p className="agent-skill-field__hint">
              {creating
                ? "模型加载正文时报的名字，小写字母开头，只能用字母、数字和下划线"
                : "创建后不可修改，改名等于换了一个技能"}
            </p>
          </div>

          <div className="agent-skill-field">
            <label className="agent-skill-field__label">适用场景 *</label>
            <Textarea
              value={form.description}
              onChange={(event) => setForm((prev) => ({ ...prev, description: event.target.value }))}
              placeholder="如：员工要提交请假申请时用这个技能；只查假期余额不必加载，直接用 leave_query"
              className="h-20 resize-none text-sm"
            />
            <p className="agent-skill-field__hint">
              这句话会随技能清单一起交给模型，它只凭这句判断要不要展开正文，写具体点
            </p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="agent-skill-field">
              <label className="agent-skill-field__label">排序</label>
              <Input
                type="number"
                value={form.sortOrder}
                onChange={(event) => setForm((prev) => ({ ...prev, sortOrder: Number(event.target.value) }))}
              />
            </div>
            <div className="agent-skill-field">
              <label className="agent-skill-field__label">状态</label>
              <Select
                value={form.enabled ? "true" : "false"}
                onValueChange={(value) => setForm((prev) => ({ ...prev, enabled: value === "true" }))}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">启用</SelectItem>
                  <SelectItem value="false">停用</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="agent-skill-field">
            <label className="agent-skill-field__label">
              配置为加载后解锁的工具
              {form.toolIds.length > 0 ? (
                <span className="ml-2 text-[11px] font-normal text-slate-400">
                  已选 {form.toolIds.length} 个
                </span>
              ) : null}
            </label>
            <p className="agent-skill-field__hint">
              勾选的工具要先加载本手册才对模型可见。只勾不看手册就会调错的（写操作、参数要靠手册教怎么填的查询），
              手册会用到但自己就能调对的不要勾，正文按名字引用即可。例：leave_submit 勾，leave_query 不勾。
              列表来自意图树，工具是否挂载、调用前是否需要确认以 Agent 运行时为准
            </p>
            <div className="agent-skill-tools">
              {toolOptions.length === 0 ? (
                <p className="px-1 py-2 text-xs text-slate-400">
                  {loading ? "加载中…" : "意图树里还没有 MCP 节点，先去意图管理挂上工具"}
                </p>
              ) : (
                toolOptions.map((option) => {
                  const checked = form.toolIds.includes(option.toolId);
                  const owner = takenBy(option);
                  return (
                    <label
                      key={option.toolId}
                      className={cn("agent-skill-tool", checked && "agent-skill-tool--checked")}
                    >
                      <Checkbox
                        checked={checked}
                        onCheckedChange={() => toggleTool(option.toolId)}
                        className="mt-0.5"
                      />
                      <span className="min-w-0 flex-1">
                        <span className="text-[13px] font-medium text-slate-700">{option.name}</span>
                        <span className="mt-0.5 block font-mono text-[11px] text-slate-400">
                          {option.toolId}
                        </span>
                        {owner ? (
                          <span className="mt-0.5 block text-[11px] text-slate-400">
                            已由「{owner}」解锁
                          </span>
                        ) : null}
                      </span>
                    </label>
                  );
                })
              )}
            </div>
          </div>
        </aside>

        <section className="agent-skill-pane">
          <header className="agent-skill-pane__header">
            <h2 className="text-sm font-semibold text-slate-900">技能正文</h2>
            <span className="text-xs text-slate-400">Markdown</span>
          </header>

          <div className="agent-skill-pane__hint">
            <Info className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <span>
              写给模型看的办事手册：先说清楚什么情况下用，再按步骤写清先问什么、再调哪个工具、结果怎么回复
            </span>
          </div>

          <Textarea
            value={form.content}
            spellCheck={false}
            onChange={(event) => setForm((prev) => ({ ...prev, content: event.target.value }))}
            placeholder={CONTENT_PLACEHOLDER}
            className="agent-skill-textarea flex-1 resize-none rounded-none border-0 bg-transparent font-mono text-xs leading-relaxed shadow-none focus-visible:ring-0"
          />

          <footer className="agent-skill-pane__footer">
            <span>{stats}</span>
            <span className="text-slate-400">正文只在模型主动加载时才进上下文</span>
          </footer>
        </section>
      </div>
    </div>
  );
}
