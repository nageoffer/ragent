import * as React from "react";
import {
  BookOpen,
  Check,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  PlayCircle,
  Plus,
  Search,
  Settings,
  Trash2
} from "lucide-react";
import { useNavigate } from "react-router-dom";

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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { useAuthStore } from "@/stores/authStore";
import { useAgentChatStore } from "@/stores/agentChatStore";
import type { AgentSession } from "@/types/agent";

interface SessionGroup {
  label: string;
  items: AgentSession[];
}

/** 待确认的删除动作 单会话与批量共用一个弹窗 */
type DeleteTarget = { kind: "one"; id: string; title: string } | { kind: "batch"; ids: string[] };

/** 会话按最近程度分桶 桶内保持列表原序 无时间的落更早 */
function groupSessions(sessions: AgentSession[]): SessionGroup[] {
  const buckets: Record<string, AgentSession[]> = { 今天: [], 昨天: [], "7 天内": [], 更早: [] };
  const now = new Date();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  for (const session of sessions) {
    const t = session.lastTime ? new Date(session.lastTime).getTime() : NaN;
    const label = Number.isNaN(t)
      ? "更早"
      : t >= dayStart
        ? "今天"
        : t >= dayStart - 864e5
          ? "昨天"
          : t >= dayStart - 7 * 864e5
            ? "7 天内"
            : "更早";
    buckets[label].push(session);
  }
  return Object.entries(buckets)
    .filter(([, items]) => items.length > 0)
    .map(([label, items]) => ({ label, items }));
}

export function AgentSidebar() {
  const {
    sessions,
    currentSessionId,
    sessionsLoaded,
    isLoadingSessions: isLoading,
    startNewChat,
    loadMessages,
    loadSessions,
    renameSession,
    deleteSession,
    batchDeleteSessions
  } = useAgentChatStore();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();

  const [selectMode, setSelectMode] = React.useState(false);
  const [picked, setPicked] = React.useState<Set<string>>(new Set());
  const [editingId, setEditingId] = React.useState<string | null>(null);
  const [draft, setDraft] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<DeleteTarget | null>(null);
  /** 哪一行的「…」菜单开着 菜单一开指针就挪到菜单上 行自己收不到 hover 只能记在这 */
  const [menuId, setMenuId] = React.useState<string | null>(null);
  const [query, setQuery] = React.useState("");
  const [avatarFailed, setAvatarFailed] = React.useState(false);
  const searchRef = React.useRef<HTMLInputElement>(null);

  React.useEffect(() => {
    if (sessions.length === 0 && !sessionsLoaded) {
      loadSessions().catch(() => null);
    }
  }, [loadSessions, sessions.length, sessionsLoaded]);

  React.useEffect(() => {
    setAvatarFailed(false);
  }, [user?.avatar, user?.userId]);

  // ⌘K / Ctrl+K 全局聚焦搜索
  React.useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const kbdHint = React.useMemo(() => (/mac/i.test(navigator.platform) ? "⌘K" : "Ctrl K"), []);

  const exitSelect = () => {
    setSelectMode(false);
    setPicked(new Set());
  };

  const togglePick = (id: string) => {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const openSession = (sessionId: string) => {
    if (sessionId === currentSessionId) return;
    loadMessages(sessionId).catch(() => null);
    navigate(`/chat/${sessionId}`);
  };

  const startRename = (session: AgentSession) => {
    setEditingId(session.id);
    setDraft(session.title || "新会话");
  };

  const commitRename = (session: AgentSession) => {
    const next = draft.trim();
    if (next && next !== (session.title || "")) {
      renameSession(session.id, next).catch(() => null);
    }
    setEditingId(null);
  };

  // 单删与批量删都在这落地 删到当前会话就退回空白页
  const runDelete = () => {
    if (!deleteTarget) return;
    const ids = deleteTarget.kind === "one" ? [deleteTarget.id] : deleteTarget.ids;
    const hitCurrent = Boolean(currentSessionId && ids.includes(currentSessionId));
    const task =
      deleteTarget.kind === "one" ? deleteSession(deleteTarget.id) : batchDeleteSessions(ids);
    setDeleteTarget(null);
    exitSelect();
    task
      .then(() => {
        if (hitCurrent) navigate("/chat");
      })
      .catch(() => null);
  };

  const username = user?.username || user?.userId || "用户";
  const displayName = /^\d+$/.test(username) ? "用户" : username;
  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl) && !avatarFailed;
  const keyword = query.trim().toLowerCase();
  const shown = keyword
    ? sessions.filter((session) => (session.title || "新会话").toLowerCase().includes(keyword))
    : sessions;
  const groups = groupSessions(shown);

  return (
    <aside className="agent-rail">
      {/* 栏首：主动作卡本身就是卡 外面不再套一层「快速开始」壳 顶栏字标就在正上方 */}
      <div className="agent-quick">
        <button
          type="button"
          className="agent-new-btn"
          onClick={() => {
            exitSelect();
            startNewChat();
            navigate("/chat");
          }}
        >
          <span className="agent-new-icon" aria-hidden="true">
            <Plus className="h-4 w-4" strokeWidth={2.25} />
          </span>
          <span className="agent-new-text">
            <span className="agent-new-title">新建会话</span>
            <span className="agent-new-sub">从空白开始</span>
          </span>
        </button>
        {user?.role === "admin" ? (
          <button
            type="button"
            className="agent-admin-btn"
            onClick={() => window.open("/admin", "_blank")}
          >
            <Settings className="h-3.5 w-3.5" strokeWidth={2} />
            管理后台
          </button>
        ) : null}
      </div>

      {/* 搜索卡：输入即过滤 ⌘K 聚焦 批量入口并在标题行右端 */}
      <div className="agent-search-card">
        <div className="agent-search-head">
          <span className="agent-search-label">搜索会话</span>
          {selectMode ? (
            <button type="button" className="agent-mini-btn" onClick={exitSelect}>
              取消
            </button>
          ) : sessions.length > 0 ? (
            <button type="button" className="agent-mini-btn" onClick={() => setSelectMode(true)}>
              选择
            </button>
          ) : null}
        </div>
        <div className="agent-search-box">
          <Search className="agent-search-icon h-4 w-4" strokeWidth={2} aria-hidden="true" />
          <input
            ref={searchRef}
            className="agent-search-input"
            value={query}
            placeholder="搜索会话..."
            spellCheck={false}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setQuery("");
                event.currentTarget.blur();
              }
            }}
            aria-label="搜索会话"
          />
          {query ? null : <span className="agent-search-kbd">{kbdHint}</span>}
        </div>
      </div>

      <section className="agent-sessions">
        <div className="agent-session-wrap">
          <div className="agent-session-list">
            {sessions.length === 0 ? (
              <div className="agent-rail-empty">
                <MessageSquare className="h-10 w-10" strokeWidth={1.25} aria-hidden="true" />
                <p>{!sessionsLoaded || isLoading ? "加载会话中" : "暂无会话记录"}</p>
              </div>
            ) : shown.length === 0 ? (
              <div className="agent-rail-empty">
                <Search className="h-10 w-10" strokeWidth={1.25} aria-hidden="true" />
                <p>无匹配会话</p>
              </div>
            ) : (
              groups.map((group) => (
                <React.Fragment key={group.label}>
                  <div className="agent-session-group">{group.label}</div>
                  {group.items.map((session) => {
                    const active = session.id === currentSessionId;
                    const isEditing = editingId === session.id;
                    const checked = picked.has(session.id);
                    return (
                      <div
                        key={session.id}
                        className="agent-session-item"
                        data-active={active && !selectMode}
                        data-picked={checked}
                        data-menu={menuId === session.id}
                      >
                        {selectMode ? (
                          <button
                            type="button"
                            className="agent-session-btn"
                            onClick={() => togglePick(session.id)}
                            aria-pressed={checked}
                          >
                            <span className="agent-checkbox" data-on={checked} aria-hidden="true">
                              {checked ? <Check className="h-3 w-3" strokeWidth={3} /> : null}
                            </span>
                            <span className="agent-session-title">{session.title || "新会话"}</span>
                          </button>
                        ) : isEditing ? (
                          <input
                            className="agent-rename-input"
                            autoFocus
                            value={draft}
                            spellCheck={false}
                            onChange={(event) => setDraft(event.target.value)}
                            onBlur={() => setEditingId(null)}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") commitRename(session);
                              else if (event.key === "Escape") setEditingId(null);
                            }}
                            aria-label="会话标题"
                          />
                        ) : (
                          <>
                            <button
                              type="button"
                              className="agent-session-btn"
                              onClick={() => openSession(session.id)}
                              title={sessionTip(session)}
                            >
                              <span className="agent-session-title agent-session-title--fade">
                                {session.title || "新会话"}
                              </span>
                            </button>
                            <DropdownMenu
                              onOpenChange={(open) => setMenuId(open ? session.id : null)}
                            >
                              <DropdownMenuTrigger asChild>
                                <button
                                  type="button"
                                  className="agent-item-btn"
                                  aria-label="会话操作"
                                >
                                  <MoreHorizontal className="h-4 w-4" />
                                </button>
                              </DropdownMenuTrigger>
                              {/* 菜单走 portal 落在 .agent-app 外 样式只能内联给 */}
                              <DropdownMenuContent
                                align="start"
                                className="min-w-[128px] rounded-xl p-1"
                              >
                                <DropdownMenuItem
                                  className="rounded-lg px-3 py-2 text-[13px]"
                                  onClick={() => startRename(session)}
                                >
                                  <Pencil className="mr-2 h-4 w-4" />
                                  重命名
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                  className="rounded-lg px-3 py-2 text-[13px] text-rose-600 focus:text-rose-600 data-[highlighted]:text-rose-600"
                                  onClick={() =>
                                    setDeleteTarget({
                                      kind: "one",
                                      id: session.id,
                                      title: session.title || "新会话"
                                    })
                                  }
                                >
                                  <Trash2 className="mr-2 h-4 w-4" />
                                  删除
                                </DropdownMenuItem>
                              </DropdownMenuContent>
                            </DropdownMenu>
                          </>
                        )}
                      </div>
                    );
                  })}
                </React.Fragment>
              ))
            )}
          </div>
          <span className="agent-session-fade" aria-hidden="true" />
        </div>

        {selectMode ? (
          <div className="agent-select-bar">
            <span className="agent-select-count">已选 {picked.size}</span>
            <button
              type="button"
              className="agent-select-del"
              disabled={picked.size === 0}
              onClick={() => setDeleteTarget({ kind: "batch", ids: [...picked] })}
            >
              删除选中
            </button>
          </div>
        ) : null}
      </section>

      {/* 栏底基座：只留身份 外链与退出收进账号菜单 */}
      <div className="agent-railbase">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button type="button" className="agent-account" aria-label="用户菜单">
              <span className="agent-avatar">
                {showAvatar ? (
                  <img src={avatarUrl} alt={displayName} onError={() => setAvatarFailed(true)} />
                ) : (
                  displayName.slice(0, 1).toUpperCase()
                )}
              </span>
              <span className="agent-account-name">{displayName}</span>
              {user?.role === "admin" ? <span className="agent-account-role">管理员</span> : null}
              <MoreHorizontal className="agent-account-more h-4 w-4" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="start"
            side="top"
            sideOffset={8}
            className="w-48 rounded-xl p-1"
          >
            <DropdownMenuItem asChild className="rounded-lg px-3 py-2 text-[13px]">
              <a href="https://nageoffer.com/ragent" target="_blank" rel="noreferrer">
                <BookOpen className="mr-2 h-4 w-4" />
                官方文档
              </a>
            </DropdownMenuItem>
            <DropdownMenuItem asChild className="rounded-lg px-3 py-2 text-[13px]">
              <a href="https://space.bilibili.com/352177376" target="_blank" rel="noreferrer">
                <PlayCircle className="mr-2 h-4 w-4" />
                哔哩哔哩
              </a>
            </DropdownMenuItem>
            <DropdownMenuItem
              className="rounded-lg px-3 py-2 text-[13px] text-rose-600 focus:text-rose-600 data-[highlighted]:text-rose-600"
              onClick={() => logout()}
            >
              <LogOut className="mr-2 h-4 w-4" />
              退出登录
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {deleteTarget?.kind === "batch"
                ? `删除选中的 ${deleteTarget.ids.length} 个会话？`
                : "删除该会话？"}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {deleteTarget?.kind === "batch"
                ? "选中的会话及其全部轨迹将被永久删除，无法恢复。"
                : `[${deleteTarget?.kind === "one" ? deleteTarget.title : "该会话"}] 将被永久删除，无法恢复。`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            {/* 弹窗走 portal 落在 .agent-app 外 焦点圈会吃 RAG 的紫 显式压成橙（token 也够不着 只能写字面值） */}
            <AlertDialogCancel className="focus-visible:ring-[#d97757]">取消</AlertDialogCancel>
            {/* 确认钮吃 shadcn 默认 primary 会是 RAG 的紫 这里按删除语义显式压成红 */}
            <AlertDialogAction
              onClick={runDelete}
              className="bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-rose-600"
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </aside>
  );
}

/** 行上只留标题 全标题/相对时间/轮数收进悬停 tooltip */
function sessionTip(session: AgentSession): string {
  const turns =
    typeof session.turns === "number" && session.turns > 0 ? `×${session.turns} 轮` : "";
  return [session.title || "新会话", relTime(session.lastTime), turns].filter(Boolean).join(" · ");
}

function relTime(iso?: string): string {
  if (!iso) return "";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "";
  const m = Math.floor((Date.now() - t) / 60000);
  if (m < 1) return "刚刚";
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时前`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d} 天前`;
  return new Date(iso).toLocaleDateString();
}
