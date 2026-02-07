import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  ClipboardList,
  Database,
  GitBranch,
  Github,
  Layers,
  Lightbulb,
  LogOut,
  Menu,
  MessageSquare,
  KeyRound,
  Search,
  Settings,
  Upload,
  Users,
  FolderKanban
} from "lucide-react";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { changePassword } from "@/services/userService";
import {
  getKnowledgeBases,
  searchKnowledgeDocuments,
  type KnowledgeBase,
  type KnowledgeDocumentSearchItem
} from "@/services/knowledgeService";
import { Avatar } from "@/components/common/Avatar";

type MenuChild = {
  path: string;
  label: string;
  icon: any;
  search?: string;
};

type MenuItem = {
  id?: string;
  path: string;
  label: string;
  icon: any;
  search?: string;
  children?: MenuChild[];
};

type MenuGroup = {
  title: string;
  items: MenuItem[];
};

const menuGroups: MenuGroup[] = [
  {
    title: "导航",
    items: [
      {
        path: "/admin/knowledge",
        label: "知识库管理",
        icon: Database
      },
      {
        id: "intent",
        path: "/admin/intent-tree",
        label: "意图管理",
        icon: Layers,
        children: [
          {
            path: "/admin/intent-tree",
            label: "意图树配置",
            icon: GitBranch
          },
          {
            path: "/admin/intent-list",
            label: "意图列表",
            icon: ClipboardList
          }
        ]
      },
      {
        id: "ingestion",
        path: "/admin/ingestion",
        label: "数据通道",
        icon: Upload,
        children: [
          {
            path: "/admin/ingestion",
            label: "流水线管理",
            icon: FolderKanban,
            search: "?tab=pipelines"
          },
          {
            path: "/admin/ingestion",
            label: "流水线任务",
            icon: ClipboardList,
            search: "?tab=tasks"
          }
        ]
      },
    ]
  },
  {
    title: "设置",
    items: [
      {
        path: "/admin/users",
        label: "用户管理",
        icon: Users
      },
      {
        path: "/admin/sample-questions",
        label: "示例问题",
        icon: Lightbulb
      },
      {
        path: "/admin/settings",
        label: "系统设置",
        icon: Settings
      },
    ]
  }
];

const breadcrumbMap: Record<string, string> = {
  knowledge: "知识库管理",
  "intent-tree": "意图树配置",
  "intent-list": "意图列表",
  ingestion: "数据通道",
  "sample-questions": "示例问题",
  settings: "系统设置",
  users: "用户管理"
};

export function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [collapsed, setCollapsed] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [passwordForm, setPasswordForm] = useState({
    currentPassword: "",
    newPassword: "",
    confirmPassword: ""
  });
  const [starCount, setStarCount] = useState<number | null>(null);
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>({ ingestion: true, intent: true });
  const [kbQuery, setKbQuery] = useState("");
  const [kbOptions, setKbOptions] = useState<KnowledgeBase[]>([]);
  const [docOptions, setDocOptions] = useState<KnowledgeDocumentSearchItem[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchFocused, setSearchFocused] = useState(false);
  const blurTimeoutRef = useRef<number | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  useEffect(() => {
    let active = true;
    fetch("https://api.github.com/repos/nageoffer/ragent")
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!active) return;
        const count = typeof data?.stargazers_count === "number" ? data.stargazers_count : null;
        setStarCount(count);
      })
      .catch(() => {
        if (active) {
          setStarCount(null);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!searchFocused) return;
    const keyword = kbQuery.trim();
    if (!keyword) {
      setKbOptions([]);
      setDocOptions([]);
      setSearchLoading(false);
      return;
    }

    let active = true;
    const handle = window.setTimeout(() => {
      setSearchLoading(true);
      Promise.all([
        getKnowledgeBases(1, 6, keyword),
        searchKnowledgeDocuments(keyword, 6)
      ])
        .then(([kbData, docData]) => {
          if (!active) return;
          setKbOptions(kbData || []);
          setDocOptions(docData || []);
        })
        .catch(() => {
          if (active) {
            setKbOptions([]);
            setDocOptions([]);
          }
        })
        .finally(() => {
          if (active) {
            setSearchLoading(false);
          }
        });
    }, 200);

    return () => {
      active = false;
      window.clearTimeout(handle);
    };
  }, [kbQuery, searchFocused]);

  const breadcrumbs = useMemo(() => {
    const segments = location.pathname.split("/").filter(Boolean);
    const items: { label: string; to?: string }[] = [
      { label: "首页", to: "/admin/knowledge" }
    ];

    if (segments[0] !== "admin") return items;
    const section = segments[1];
    if (section) {
      if (section === "intent-tree" || section === "intent-list") {
        items.push({
          label: "意图管理",
          to: "/admin/intent-tree"
        });
        if (section === "intent-list" && segments.includes("edit")) {
          items.push({
            label: breadcrumbMap[section] || section,
            to: "/admin/intent-list"
          });
          items.push({
            label: "编辑节点"
          });
        } else {
          items.push({
            label: breadcrumbMap[section] || section
          });
        }
      } else {
        items.push({
          label: breadcrumbMap[section] || section,
          to: `/admin/${section}`
        });
      }
    }

    if (section === "ingestion") {
      const searchParams = new URLSearchParams(location.search);
      const tab = searchParams.get("tab");
      if (tab === "tasks") {
        items.push({ label: "流水线任务" });
      } else if (tab === "pipelines") {
        items.push({ label: "流水线管理" });
      }
    }

    if (section === "knowledge" && segments.length > 2) {
      items.push({ label: "文档管理" });
    }

    if (section === "knowledge" && segments.includes("docs")) {
      items.push({ label: "切片管理" });
    }

    return items;
  }, [location.pathname, location.search]);

  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl);
  const roleLabel = user?.role === "admin" ? "管理员" : "成员";
  const starLabel = useMemo(() => {
    if (starCount === null) return "--";
    if (starCount < 1000) return String(starCount);
    const rounded = Math.round((starCount / 1000) * 10) / 10;
    const text = String(rounded).replace(/\.0$/, "");
    return `${text}k`;
  }, [starCount]);
  const isIngestionActive = location.pathname.startsWith("/admin/ingestion");
  const isIntentActive =
    location.pathname.startsWith("/admin/intent-tree") || location.pathname.startsWith("/admin/intent-list");

  useEffect(() => {
    setOpenGroups((prev) => ({
      ...prev,
      ingestion: prev.ingestion || isIngestionActive,
      intent: prev.intent || isIntentActive
    }));
  }, [isIngestionActive, isIntentActive]);

  const handlePasswordSubmit = async () => {
    if (!passwordForm.currentPassword || !passwordForm.newPassword) {
      toast.error("请输入当前密码和新密码");
      return;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error("两次输入的新密码不一致");
      return;
    }
    try {
      setPasswordSubmitting(true);
      await changePassword({
        currentPassword: passwordForm.currentPassword,
        newPassword: passwordForm.newPassword
      });
      toast.success("密码已更新");
      setPasswordOpen(false);
      setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
    } catch (error) {
      toast.error((error as Error).message || "修改密码失败");
    } finally {
      setPasswordSubmitting(false);
    }
  };

  const handleSearchSelect = (kb: KnowledgeBase) => {
    searchInputRef.current?.blur();
    navigate(`/admin/knowledge/${kb.id}`);
    setSearchFocused(false);
    setKbQuery("");
    setKbOptions([]);
    setDocOptions([]);
  };

  const handleDocumentSelect = (doc: KnowledgeDocumentSearchItem) => {
    searchInputRef.current?.blur();
    navigate(`/admin/knowledge/${doc.kbId}/docs/${doc.id}`);
    setSearchFocused(false);
    setKbQuery("");
    setKbOptions([]);
    setDocOptions([]);
  };

  const handleSearchFocus = () => {
    if (blurTimeoutRef.current) {
      window.clearTimeout(blurTimeoutRef.current);
      blurTimeoutRef.current = null;
    }
    setSearchFocused(true);
  };

  const handleSearchBlur = () => {
    if (blurTimeoutRef.current) {
      window.clearTimeout(blurTimeoutRef.current);
    }
    blurTimeoutRef.current = window.setTimeout(() => {
      setSearchFocused(false);
    }, 150);
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      const keyword = kbQuery.trim();
      if (kbOptions.length > 0) {
        handleSearchSelect(kbOptions[0]);
        return;
      }
      if (docOptions.length > 0) {
        handleDocumentSelect(docOptions[0]);
        return;
      }
      if (keyword) {
        searchInputRef.current?.blur();
        navigate(`/admin/knowledge?name=${encodeURIComponent(keyword)}`);
        setSearchFocused(false);
        return;
      }
    }
    if (event.key === "Escape") {
      searchInputRef.current?.blur();
      setSearchFocused(false);
    }
  };

  const isLeafActive = (path: string, search?: string) => {
    if (location.pathname !== path && !location.pathname.startsWith(`${path}/`)) {
      return false;
    }
    if (search) {
      return location.search === search;
    }
    return true;
  };

  const hasQuery = kbQuery.trim().length > 0;
  const showSuggest = searchFocused && hasQuery;

  return (
    <div className="admin-layout flex h-screen">
      <aside className={cn("admin-sidebar", collapsed && "admin-sidebar--collapsed")}>
        <div className="admin-sidebar__brand">
          <div className={cn("flex items-center gap-3", collapsed && "justify-center")}>
            <div className="admin-sidebar__logo">R</div>
            {!collapsed && (
              <div className="min-w-0">
                <h1 className="admin-sidebar__title">Ragent 管理后台</h1>
                <p className="admin-sidebar__subtitle">Knowledge Console</p>
              </div>
            )}
          </div>
        </div>

        <nav className="flex-1 space-y-4 px-2 pb-4">
          {menuGroups.map((group) => (
            <div key={group.title} className="space-y-2">
              {!collapsed && (
                <p className="admin-sidebar__group-title">{group.title}</p>
              )}
              <div className="space-y-1">
                {group.items.flatMap((item) => {
                  if (!item.children || item.children.length === 0) {
                    const Icon = item.icon;
                    const isActive = isLeafActive(item.path, item.search);
                    return (
                      <Link
                        key={item.path}
                        to={item.path}
                        title={collapsed ? item.label : undefined}
                        className={cn(
                          "admin-sidebar__item",
                          isActive && "admin-sidebar__item--active",
                          collapsed && "justify-center"
                        )}
                      >
                        <span
                          className={cn(
                            "admin-sidebar__item-indicator",
                            isActive && "is-active"
                          )}
                        />
                        <Icon className="admin-sidebar__item-icon" />
                        {collapsed ? <span className="sr-only">{item.label}</span> : <span>{item.label}</span>}
                      </Link>
                    );
                  }

                  const isGroupActive = item.children.some((child) => isLeafActive(child.path, child.search));
                  const groupId = item.id as string;
                  const isOpen = openGroups[groupId];

                  if (collapsed) {
                    return item.children.map((child) => {
                      const ChildIcon = child.icon;
                      const isActive = isLeafActive(child.path, child.search);
                      return (
                        <Link
                          key={child.label}
                          to={`${child.path}${child.search || ""}`}
                          title={child.label}
                          className={cn(
                            "admin-sidebar__item",
                            isActive && "admin-sidebar__item--active",
                            "justify-center"
                          )}
                        >
                          <span
                            className={cn(
                              "admin-sidebar__item-indicator",
                              isActive && "is-active"
                            )}
                          />
                          <ChildIcon className="admin-sidebar__item-icon" />
                          <span className="sr-only">{child.label}</span>
                        </Link>
                      );
                    });
                  }

                      return (
                        <div key={item.label} className="space-y-1">
                          <button
                            type="button"
                            onClick={() => setOpenGroups((prev) => ({ ...prev, [groupId]: !prev[groupId] }))}
                            className={cn(
                              "admin-sidebar__item admin-sidebar__item--group w-full text-white/60",
                              isGroupActive && "admin-sidebar__item--group-active text-white"
                            )}
                          >
                            <span
                              className={cn(
                                "admin-sidebar__item-indicator",
                                isGroupActive && "is-group-active"
                              )}
                            />
                        <item.icon className="admin-sidebar__item-icon" />
                        <span className="flex-1 text-left">{item.label}</span>
                        {isOpen ? (
                          <ChevronDown className="h-4 w-4 text-white/60" />
                        ) : (
                          <ChevronRight className="h-4 w-4 text-white/60" />
                        )}
                      </button>
                      {isOpen ? (
                        <div className="ml-6 space-y-1">
                          {item.children.map((child) => {
                            const ChildIcon = child.icon;
                            const isActive = isLeafActive(child.path, child.search);
                            return (
                              <Link
                                key={child.label}
                                to={`${child.path}${child.search || ""}`}
                                className={cn(
                                  "admin-sidebar__item text-[13px]",
                                  isActive && "admin-sidebar__item--active"
                                )}
                              >
                                <span
                                  className={cn(
                                    "admin-sidebar__item-indicator",
                                    isActive && "is-active"
                                  )}
                                />
                                <ChildIcon className="admin-sidebar__item-icon" />
                                <span>{child.label}</span>
                              </Link>
                            );
                          })}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="admin-sidebar__footer space-y-2">
          <button
            type="button"
            className="admin-sidebar__collapse"
            onClick={() => setCollapsed((prev) => !prev)}
          >
            {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
            {!collapsed && <span>收起侧边栏</span>}
          </button>
        </div>
      </aside>

      <div className="admin-main flex min-h-screen flex-1 flex-col overflow-auto">
        <header className="admin-topbar">
          <div className="admin-topbar-inner">
            <div className="flex items-center gap-3">
              <Button
                variant="ghost"
                size="icon"
                className="lg:hidden"
                onClick={() => setCollapsed((prev) => !prev)}
                aria-label="切换侧边栏"
              >
                <Menu className="h-5 w-5" />
              </Button>
              <div className="admin-topbar-search">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <Input
                  ref={searchInputRef}
                  value={kbQuery}
                  onChange={(event) => {
                    setKbQuery(event.target.value);
                  }}
                  onFocus={handleSearchFocus}
                  onBlur={handleSearchBlur}
                  onKeyDown={handleSearchKeyDown}
                  name="kb-search"
                  autoComplete="off"
                  autoCorrect="off"
                  autoCapitalize="off"
                  spellCheck={false}
                  placeholder="筛选知识库..."
                  className="pl-10 pr-16"
                />
                <span className="admin-topbar-kbd">Ctrl K</span>
                {showSuggest ? (
                  <div
                    className="admin-topbar-suggest"
                    onMouseDown={(event) => event.preventDefault()}
                  >
                    {searchLoading && kbOptions.length === 0 && docOptions.length === 0 ? (
                      <div className="admin-topbar-suggest-item text-slate-400">搜索中...</div>
                    ) : null}
                    {kbOptions.length > 0 ? (
                      <div className="admin-topbar-suggest-section">
                        <div className="admin-topbar-suggest-group">知识库</div>
                        {kbOptions.map((kb) => (
                          <button
                            key={kb.id}
                            type="button"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              handleSearchSelect(kb);
                            }}
                            className="admin-topbar-suggest-item"
                          >
                            <span className="font-medium text-slate-900">{kb.name}</span>
                            <span className="text-xs text-slate-400">
                              {kb.collectionName || "未设置 Collection"}
                            </span>
                          </button>
                        ))}
                      </div>
                    ) : null}
                    {docOptions.length > 0 ? (
                      <div className="admin-topbar-suggest-section">
                        <div className="admin-topbar-suggest-group">文档</div>
                        {docOptions.map((doc) => (
                          <button
                            key={doc.id}
                            type="button"
                            onMouseDown={(event) => {
                              event.preventDefault();
                              handleDocumentSelect(doc);
                            }}
                            className="admin-topbar-suggest-item"
                          >
                            <span className="font-medium text-slate-900">{doc.docName}</span>
                            <span className="text-xs text-slate-400">
                              {doc.kbName || `知识库 ${doc.kbId}`}
                            </span>
                          </button>
                        ))}
                      </div>
                    ) : null}
                    {!searchLoading && kbOptions.length === 0 && docOptions.length === 0 ? (
                      <div className="admin-topbar-suggest-item text-slate-400">暂无匹配结果</div>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                className="hidden items-center gap-2 sm:inline-flex"
                onClick={() => navigate("/chat")}
              >
                <MessageSquare className="h-4 w-4" />
                返回聊天
              </Button>
              <a
                href="https://github.com/nageoffer/ragent"
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 transition hover:bg-slate-100 hover:text-slate-900"
                aria-label="打开 GitHub 仓库"
              >
                <Github className="h-4 w-4" />
                <span className="font-medium">Star</span>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
                  {starLabel}
                </span>
              </a>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-600 shadow-sm"
                    aria-label="用户菜单"
                  >
                    <Avatar
                      name={user?.username || "管理员"}
                      src={showAvatar ? avatarUrl : undefined}
                      className="h-8 w-8 border-slate-200 bg-indigo-50 text-xs font-semibold text-indigo-600"
                    />
                    <span className="hidden sm:inline">{user?.username || "管理员"}</span>
                    <ChevronDown className="h-4 w-4 text-slate-400" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" sideOffset={8} className="w-44">
                  <div className="px-3 py-2 text-xs text-slate-500">
                    {user?.username || "管理员"} · {roleLabel}
                  </div>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => setPasswordOpen(true)}>
                    <KeyRound className="mr-2 h-4 w-4" />
                    修改密码
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleLogout} className="text-rose-600 focus:text-rose-600">
                    <LogOut className="mr-2 h-4 w-4" />
                    退出登录
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>
        </header>

        <div className="admin-content">
          <nav className="admin-breadcrumbs" aria-label="面包屑">
            {breadcrumbs.map((item, index) => {
              const isLast = index === breadcrumbs.length - 1;
              return (
                <span key={`${item.label}-${index}`} className="flex items-center gap-2">
                  {item.to && !isLast ? (
                    <Link to={item.to}>{item.label}</Link>
                  ) : (
                    <span className={isLast ? "text-slate-700" : undefined}>{item.label}</span>
                  )}
                  {!isLast && <span>/</span>}
                </span>
              );
            })}
          </nav>
          <Outlet />
        </div>
      </div>

      <Dialog
        open={passwordOpen}
        onOpenChange={(open) => {
          setPasswordOpen(open);
          if (!open) {
            setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
          }
        }}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>修改密码</DialogTitle>
            <DialogDescription>请输入当前密码与新密码</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">当前密码</label>
              <Input
                type="password"
                value={passwordForm.currentPassword}
                onChange={(event) => setPasswordForm((prev) => ({ ...prev, currentPassword: event.target.value }))}
                placeholder="请输入当前密码"
                name="current-password"
                autoComplete="current-password"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">新密码</label>
              <Input
                type="password"
                value={passwordForm.newPassword}
                onChange={(event) => setPasswordForm((prev) => ({ ...prev, newPassword: event.target.value }))}
                placeholder="请输入新密码"
                name="new-password"
                autoComplete="new-password"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">确认新密码</label>
              <Input
                type="password"
                value={passwordForm.confirmPassword}
                onChange={(event) => setPasswordForm((prev) => ({ ...prev, confirmPassword: event.target.value }))}
                placeholder="再次输入新密码"
                name="confirm-new-password"
                autoComplete="new-password"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPasswordOpen(false)}>
              取消
            </Button>
            <Button onClick={handlePasswordSubmit} disabled={passwordSubmitting}>
              {passwordSubmitting ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
