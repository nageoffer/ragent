import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import { Database, GitBranch, Settings, Upload, MessageSquare, LogOut } from "lucide-react";
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
      label: "数据摄入",
      icon: Upload
    },
    {
      path: "/admin/settings",
      label: "系统设置",
      icon: Settings
    }
  ];

  return (
    <div className="flex h-screen bg-background">
      {/* 侧边栏 */}
      <aside className="w-64 border-r bg-card flex flex-col">
        {/* Logo */}
        <div className="p-6">
          <h1 className="text-2xl font-bold">Ragent 管理后台</h1>
          <p className="text-sm text-muted-foreground mt-1">
            {user?.username} (管理员)
          </p>
        </div>

        <Separator />

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 space-y-2">
          {menuItems.map((item) => {
            const Icon = item.icon;
            const isActive = location.pathname.startsWith(item.path);

            return (
              <Link
                key={item.path}
                to={item.path}
                className={`flex items-center gap-3 px-4 py-3 rounded-lg transition-colors ${
                  isActive
                    ? "bg-primary text-primary-foreground"
                    : "hover:bg-accent hover:text-accent-foreground"
                }`}
              >
                <Icon className="w-5 h-5" />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>

        <Separator />

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
            className="w-full justify-start text-destructive hover:text-destructive"
            onClick={handleLogout}
          >
            <LogOut className="w-4 h-4 mr-2" />
            退出登录
          </Button>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
