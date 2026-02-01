import { useEffect, useState } from "react";
import { Pencil, Plus, RefreshCw, Trash2, UserPlus } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { PageResult, UserItem, UserCreatePayload, UserUpdatePayload } from "@/services/userService";
import { createUser, deleteUser, getUsersPage, updateUser } from "@/services/userService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const roleOptions = [
  { value: "admin", label: "管理员" },
  { value: "user", label: "成员" }
];

const buildEmptyForm = () => ({
  username: "",
  password: "",
  role: "user",
  avatar: ""
});

export function UserListPage() {
  const [pageData, setPageData] = useState<PageResult<UserItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [deleteTarget, setDeleteTarget] = useState<UserItem | null>(null);
  const [dialogState, setDialogState] = useState<{ open: boolean; mode: "create" | "edit"; user: UserItem | null }>({
    open: false,
    mode: "create",
    user: null
  });
  const [form, setForm] = useState(buildEmptyForm());

  const users = pageData?.records || [];

  const loadUsers = async (current = pageNo, name = keyword) => {
    try {
      setLoading(true);
      const data = await getUsersPage(current, PAGE_SIZE, name || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载用户列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, [pageNo, keyword]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchInput.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadUsers(1, keyword);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteUser(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadUsers(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const openCreateDialog = () => {
    setForm(buildEmptyForm());
    setDialogState({ open: true, mode: "create", user: null });
  };

  const openEditDialog = (user: UserItem) => {
    setForm({
      username: user.username || "",
      password: "",
      role: user.role || "user",
      avatar: user.avatar || ""
    });
    setDialogState({ open: true, mode: "edit", user });
  };

  const handleSave = async () => {
    const trimmedUsername = form.username.trim();
    const trimmedPassword = form.password.trim();
    if (!trimmedUsername) {
      toast.error("请输入用户名");
      return;
    }

    try {
      if (dialogState.mode === "create") {
        if (!trimmedPassword) {
          toast.error("请输入初始密码");
          return;
        }
        const payload: UserCreatePayload = {
          username: trimmedUsername,
          password: trimmedPassword,
          role: form.role || "user",
          avatar: form.avatar?.trim() || undefined
        };
        await createUser(payload);
        toast.success("创建成功");
        setPageNo(1);
        await loadUsers(1, keyword);
      } else if (dialogState.user) {
        const payload: UserUpdatePayload = {
          username: trimmedUsername,
          role: form.role || "user",
          avatar: form.avatar?.trim() || undefined,
          password: trimmedPassword || undefined
        };
        await updateUser(dialogState.user.id, payload);
        toast.success("更新成功");
        await loadUsers(pageNo, keyword);
      }
      setDialogState({ open: false, mode: "create", user: null });
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      console.error(error);
    }
  };

  const formatDate = (dateStr?: string | null) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const isProtectedAdmin = (user: UserItem) => user.username === "admin";

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">用户管理</h1>
          <p className="admin-page-subtitle">管理后台账号与角色权限</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="搜索用户名或角色"
            className="w-[220px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateDialog}>
            <UserPlus className="w-4 h-4 mr-2" />
            新增用户
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : users.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">暂无用户</div>
          ) : (
            <Table className="min-w-[860px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[240px]">用户</TableHead>
                  <TableHead className="w-[140px]">角色</TableHead>
                  <TableHead className="w-[180px]">创建时间</TableHead>
                  <TableHead className="w-[180px]">更新时间</TableHead>
                  <TableHead className="w-[160px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.map((user) => {
                  const isProtected = isProtectedAdmin(user);
                  const roleLabel = user.role === "admin" ? "管理员" : "成员";
                  return (
                    <TableRow key={user.id}>
                      <TableCell>
                        <div className="flex items-center gap-3">
                          <div className="flex h-9 w-9 items-center justify-center rounded-full bg-indigo-50 text-xs font-semibold text-indigo-600">
                            {(user.username || "U").slice(0, 1).toUpperCase()}
                          </div>
                          <div>
                            <div className="font-medium text-slate-900">{user.username || "-"}</div>
                            {isProtected ? (
                              <div className="text-xs text-slate-400">默认管理员</div>
                            ) : null}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={user.role === "admin" ? "default" : "secondary"}>{roleLabel}</Badge>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{formatDate(user.createTime)}</TableCell>
                      <TableCell className="text-muted-foreground">{formatDate(user.updateTime)}</TableCell>
                      <TableCell className="text-center">
                        <div className="flex justify-center gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={isProtected}
                            onClick={() => openEditDialog(user)}
                          >
                            <Pencil className="w-4 h-4 mr-0.5" />
                            编辑
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="text-destructive hover:text-destructive"
                            disabled={isProtected}
                            onClick={() => setDeleteTarget(user)}
                          >
                            <Trash2 className="w-4 h-4 mr-0.5" />
                            删除
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              此操作将永久删除该用户，无法恢复。确定要继续吗？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={dialogState.open} onOpenChange={(open) => setDialogState((prev) => ({ ...prev, open }))}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新增用户" : "编辑用户"}</DialogTitle>
            <DialogDescription>
              {dialogState.mode === "create" ? "配置账号基本信息" : "更新账号信息，密码留空则不修改"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium">用户名</label>
              <Input
                value={form.username}
                onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                placeholder="请输入用户名"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">密码</label>
              <Input
                type="password"
                value={form.password}
                onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
                placeholder={dialogState.mode === "create" ? "设置初始密码" : "留空则不修改"}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">角色</label>
              <Select value={form.role} onValueChange={(value) => setForm((prev) => ({ ...prev, role: value }))}>
                <SelectTrigger>
                  <SelectValue placeholder="请选择角色" />
                </SelectTrigger>
                <SelectContent>
                  {roleOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">头像</label>
              <Input
                value={form.avatar}
                onChange={(event) => setForm((prev) => ({ ...prev, avatar: event.target.value }))}
                placeholder="可选，填写头像 URL"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogState({ open: false, mode: "create", user: null })}>
              取消
            </Button>
            <Button onClick={handleSave}>
              {dialogState.mode === "create" ? (
                <>
                  <Plus className="mr-2 h-4 w-4" />
                  创建
                </>
              ) : (
                <>
                  <Pencil className="mr-2 h-4 w-4" />
                  保存
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={pageData.current <= 1}
            >
              上一页
            </Button>
            <span>
              {pageData.current} / {pageData.pages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(pageData.pages || 1, prev + 1))}
              disabled={pageData.current >= pageData.pages}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
