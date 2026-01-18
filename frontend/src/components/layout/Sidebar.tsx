import * as React from "react";
import { differenceInCalendarDays, format, isValid } from "date-fns";
import {
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Settings,
  Sparkles,
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
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const {
    sessions,
    currentSessionId,
    createSession,
    deleteSession,
    renameSession,
    selectSession,
    fetchSessions
  } = useChatStore();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const [isScrolling, setIsScrolling] = React.useState(false);
  const scrollTimeoutRef = React.useRef<number | null>(null);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7天内";
      return format(date, "yyyy-MM");
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  const handleScroll = () => {
    setIsScrolling(true);
    if (scrollTimeoutRef.current) {
      window.clearTimeout(scrollTimeoutRef.current);
    }
    scrollTimeoutRef.current = window.setTimeout(() => {
      setIsScrolling(false);
    }, 800);
  };

  React.useEffect(() => {
    return () => {
      if (scrollTimeoutRef.current) {
        window.clearTimeout(scrollTimeoutRef.current);
      }
    };
  }, []);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle === currentTitle) {
      cancelRename();
      return;
    }
    await renameSession(renamingId, nextTitle);
    cancelRename();
  };

  return (
    <>
      <div
        className={cn(
          "fixed inset-0 z-30 bg-slate-900/30 backdrop-blur-sm transition-opacity lg:hidden",
          isOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-screen w-72 flex-shrink-0 flex-col border-r border-gray-200 bg-white transition-transform lg:static lg:h-screen lg:translate-x-0",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div className="border-b border-gray-100 p-5">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-purple-500 shadow-md shadow-indigo-500/20">
              <Sparkles className="h-5 w-5 text-white" />
            </div>
            <div>
              <p className="text-base font-semibold text-gray-900">RAG 智能问答</p>
              <p className="text-xs text-gray-400">Powered by AI</p>
            </div>
          </div>
        </div>
        <div className="p-4">
          <button
            type="button"
            className="w-full"
            onClick={() => {
              createSession().catch(() => null);
              navigate("/chat");
              onClose();
            }}
          >
            <span className="flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-500 to-purple-500 px-4 py-3 text-sm font-medium text-white shadow-md shadow-indigo-500/25 transition hover:shadow-lg hover:shadow-indigo-500/30">
              <Plus className="h-4 w-4" />
              新建对话
            </span>
          </button>
        </div>
        <div className="px-4 pb-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索对话..."
              className="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 pl-9 pr-3 text-sm text-gray-700 placeholder:text-gray-400 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 focus:outline-none"
            />
          </div>
        </div>
        <div
          className={cn(
            "flex-1 overflow-y-auto px-3 scrollbar-auto",
            isScrolling && "is-scrolling"
          )}
          onScroll={handleScroll}
        >
          {filteredSessions.length === 0 ? (
            <p className="px-3 py-2 text-sm text-gray-400">暂无会话</p>
          ) : (
            <div className="space-y-4">
              {groupedSessions.map((group) => (
                <div key={group.label} className="space-y-1">
                  <p className="px-3 py-2 text-xs font-medium text-gray-400">
                    {group.label}
                  </p>
                  {group.items.map((session) => (
                    <div
                      key={session.id}
                      className={cn(
                        "group flex items-center gap-3 rounded-xl px-4 py-3 text-sm transition-all",
                        currentSessionId === session.id
                          ? "bg-indigo-50 text-indigo-700 border border-indigo-100"
                          : "text-gray-600 hover:bg-gray-50"
                      )}
                      role="button"
                      tabIndex={0}
                      onClick={() => {
                        if (renamingId === session.id) return;
                        if (renamingId) {
                          cancelRename();
                        }
                        selectSession(session.id).catch(() => null);
                        navigate(`/chat/${session.id}`);
                        onClose();
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          selectSession(session.id).catch(() => null);
                          navigate(`/chat/${session.id}`);
                          onClose();
                        }
                      }}
                    >
                      <MessageSquare
                        className={cn(
                          "h-4 w-4 shrink-0",
                          currentSessionId === session.id ? "text-indigo-500" : "text-gray-400"
                        )}
                      />
                      {renamingId === session.id ? (
                        <input
                          ref={renameInputRef}
                          value={renameValue}
                          onChange={(event) => setRenameValue(event.target.value)}
                          onClick={(event) => event.stopPropagation()}
                          onKeyDown={(event) => {
                            if (event.key === "Enter") {
                              event.preventDefault();
                              commitRename().catch(() => null);
                            }
                            if (event.key === "Escape") {
                              event.preventDefault();
                              cancelRename();
                            }
                          }}
                          onBlur={() => {
                            commitRename().catch(() => null);
                          }}
                          className="h-8 flex-1 rounded-lg border border-indigo-200 bg-white px-2 text-sm text-gray-700 focus:border-indigo-500 focus:outline-none"
                        />
                      ) : (
                        <span className="flex-1 truncate text-sm font-medium">
                          {session.title || "新对话"}
                        </span>
                      )}
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button
                            type="button"
                            className="flex h-7 w-7 items-center justify-center rounded-lg text-gray-400 opacity-0 transition-opacity hover:bg-gray-200 group-hover:opacity-100"
                            onClick={(event) => event.stopPropagation()}
                            aria-label="会话操作"
                          >
                            <MoreHorizontal className="h-4 w-4" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-32">
                          <DropdownMenuItem
                            onClick={(event) => {
                              event.stopPropagation();
                              startRename(session.id, session.title || "新对话");
                            }}
                          >
                            <Pencil className="mr-2 h-4 w-4" />
                            重命名
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={(event) => {
                              event.stopPropagation();
                              setDeleteTarget({
                                id: session.id,
                                title: session.title || "新对话"
                              });
                            }}
                            className="text-rose-600 focus:text-rose-600"
                          >
                            <Trash2 className="mr-2 h-4 w-4" />
                            删除
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="mt-auto border-t border-gray-100 p-4">
          <div className="flex items-center gap-3 rounded-xl p-3 transition hover:bg-gray-50">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-purple-500 text-white shadow-md">
              <span className="text-sm font-medium">
                {(user?.userId || "A").slice(0, 1).toUpperCase()}
              </span>
            </div>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-900">{user?.userId || "管理员"}</p>
              <p className="text-xs text-gray-400">{user?.role || "成员"}</p>
            </div>
            <Settings className="h-4 w-4 text-gray-400" />
          </div>
        </div>
      </aside>
      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => {
        if (!open) {
          setDeleteTarget(null);
        }
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              {deleteTarget?.title || "该会话"}将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!deleteTarget) return;
                deleteSession(deleteTarget.id).catch(() => null);
                if (currentSessionId === deleteTarget.id) {
                  navigate("/chat");
                }
                setDeleteTarget(null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
