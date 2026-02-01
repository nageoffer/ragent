import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import { Database, GitBranch, Lightbulb, Settings, Upload, MessageSquare, LogOut } from "lucide-react";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

export function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  const menuItems = [
    {
      path: "/admin/knowledge",
      label: "知识库管理",
      icon: Database
    },
    {
      path: "/admin/intent-tree",
      label: "意图树配置",
      icon: GitBranch
    },
    {
      path: "/admin/ingestion",
      label: "数据通道",
      icon: Upload
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
    }
  ];

  return (
    <div className="admin-layout flex h-screen bg-slate-50 text-slate-900">
      {/* 侧边栏 */}
      <aside className="flex w-[260px] flex-col border-r border-slate-200/70 bg-white">
        <div className="px-6 pb-4 pt-6">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-600/10 text-xs font-semibold text-blue-700">
              R
            </div>
            <div>
              <h1 className="text-base font-semibold text-slate-900">管理后台</h1>
              <p className="text-xs text-slate-500">Ragent Console</p>
            </div>
          </div>
          <div className="mt-3 flex items-center gap-2 text-xs text-slate-500">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-500/70" />
            <span className="truncate">{user?.username || "管理员"}</span>
            <span className="text-slate-300">·</span>
            <span>管理员</span>
          </div>
        </div>

        <div className="px-4">
          <Separator className="bg-slate-100" />
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 px-3 py-4">
          <p className="px-3 pb-2 text-[11px] font-medium uppercase tracking-[0.2em] text-slate-400">
            导航
          </p>
          <div className="space-y-1">
            {menuItems.map((item) => {
              const Icon = item.icon;
              const isActive = location.pathname.startsWith(item.path);

              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`group flex items-center gap-3 rounded-lg border border-transparent px-3 py-2 text-sm font-medium transition ${
                    isActive
                      ? "border-blue-100 bg-blue-50 text-blue-700"
                      : "text-slate-600 hover:bg-slate-50 hover:text-slate-900"
                  }`}
                >
                  <Icon className={`h-4 w-4 ${isActive ? "text-blue-600" : "text-slate-400 group-hover:text-slate-700"}`} />
                  <span>{item.label}</span>
                </Link>
              );
            })}
          </div>
        </nav>

        <div className="px-4">
          <Separator className="bg-slate-100" />
        </div>

        {/* 底部操作 */}
        <div className="p-4 space-y-2">
          <Button
            variant="outline"
            className="w-full justify-start"
            onClick={() => navigate("/chat")}
          >
            <MessageSquare className="w-4 h-4 mr-2" />
            返回聊天
          </Button>
          <Button
            variant="ghost"
            className="w-full justify-start"
            onClick={handleLogout}
          >
            <LogOut className="w-4 h-4 mr-2" />
            退出登录
          </Button>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 overflow-auto">
        <div className="mx-auto w-full max-w-[1440px] px-8 py-10 animate-in fade-in-0 slide-in-from-bottom-2 duration-300 lg:px-10 2xl:max-w-[1520px]">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
