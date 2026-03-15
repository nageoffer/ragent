import { useEffect, useState } from "react";
import { Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
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
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import type { PageResult, QueryTermMapping } from "@/services/queryTermMappingService";
import {
  createQueryTermMapping,
  deleteQueryTermMapping,
  getQueryTermMappingsPage,
  updateQueryTermMapping
} from "@/services/queryTermMappingService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const MATCH_TYPE_OPTIONS = [
  { value: 1, label: "精确匹配" },
  { value: 2, label: "前缀匹配" },
  { value: 3, label: "正则匹配" },
  { value: 4, label: "整词匹配" }
];

const matchTypeLabel = (type: number) => {
  return MATCH_TYPE_OPTIONS.find((o) => o.value === type)?.label || `类型${type}`;
};

const emptyForm = {
  sourceTerm: "",
  targetTerm: "",
  matchType: 1,
  priority: 0,
  enabled: true,
  remark: ""
};

export function QueryTermMappingPage() {
  const [pageData, setPageData] = useState<PageResult<QueryTermMapping> | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<QueryTermMapping | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [keyword, setKeyword] = useState("");
  const [dialogState, setDialogState] = useState<{
    open: boolean;
    mode: "create" | "edit";
    item: QueryTermMapping | null;
  }>({ open: false, mode: "create", item: null });
  const [form, setForm] = useState(emptyForm);

  const loadData = async (current = pageNo, keywordValue = keyword) => {
    try {
      setLoading(true);
      const data = await getQueryTermMappingsPage(current, PAGE_SIZE, keywordValue || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载映射规则失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNo, keyword]);

  useEffect(() => {
    if (!dialogState.open) {
      setForm(emptyForm);
      return;
    }
    if (dialogState.mode === "edit" && dialogState.item) {
      setForm({
        sourceTerm: dialogState.item.sourceTerm || "",
        targetTerm: dialogState.item.targetTerm || "",
        matchType: dialogState.item.matchType ?? 1,
        priority: dialogState.item.priority ?? 0,
        enabled: dialogState.item.enabled ?? true,
        remark: dialogState.item.remark || ""
      });
      return;
    }
    setForm(emptyForm);
  }, [dialogState]);

  const formatDate = (dateStr?: string | null) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchKeyword.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadData(1, keyword);
  };

  const openCreateDialog = () => {
    setDialogState({ open: true, mode: "create", item: null });
  };

  const openEditDialog = (item: QueryTermMapping) => {
    setDialogState({ open: true, mode: "edit", item });
  };

  const handleSubmit = async () => {
    const payload = {
      sourceTerm: form.sourceTerm.trim(),
      targetTerm: form.targetTerm.trim(),
      matchType: form.matchType,
      priority: form.priority,
      enabled: form.enabled,
      remark: form.remark.trim() || null
    };

    if (!payload.sourceTerm) {
      toast.error("请输入原始词");
      return;
    }
    if (!payload.targetTerm) {
      toast.error("请输入目标词");
      return;
    }

    try {
      if (dialogState.mode === "create") {
        await createQueryTermMapping(payload);
        toast.success("创建成功");
        setPageNo(1);
        await loadData(1, keyword);
      } else if (dialogState.item) {
        await updateQueryTermMapping(dialogState.item.id, payload);
        toast.success("更新成功");
        await loadData(pageNo, keyword);
      }
      setDialogState({ open: false, mode: "create", item: null });
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      console.error(error);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;

    try {
      await deleteQueryTermMapping(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadData(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const records = pageData?.records || [];

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">关键词映射管理</h1>
          <p className="admin-page-subtitle">配置查询归一化的关键词映射规则</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchKeyword}
            onChange={(event) => setSearchKeyword(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && handleSearch()}
            placeholder="搜索原始词/目标词"
            className="w-[240px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateDialog}>
            <Plus className="w-4 h-4 mr-2" />
            新增映射
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无映射规则，点击上方按钮新增
            </div>
          ) : (
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[170px]">原始词</TableHead>
                  <TableHead className="w-[170px]">目标词</TableHead>
                  <TableHead className="w-[100px]">匹配类型</TableHead>
                  <TableHead className="w-[80px]">优先级</TableHead>
                  <TableHead className="w-[80px]">状态</TableHead>
                  <TableHead className="w-[180px]">备注</TableHead>
                  <TableHead className="w-[160px]">创建时间</TableHead>
                  <TableHead className="w-[160px]">更新时间</TableHead>
                  <TableHead className="w-[150px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="font-medium max-w-[160px] truncate" title={item.sourceTerm}>
                      {item.sourceTerm}
                    </TableCell>
                    <TableCell className="max-w-[160px] truncate" title={item.targetTerm}>
                      {item.targetTerm}
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">{matchTypeLabel(item.matchType)}</Badge>
                    </TableCell>
                    <TableCell>{item.priority}</TableCell>
                    <TableCell>
                      <Badge variant={item.enabled ? "default" : "outline"}>
                        {item.enabled ? "启用" : "禁用"}
                      </Badge>
                    </TableCell>
                    <TableCell className="max-w-[160px] truncate text-muted-foreground" title={item.remark || ""}>
                      {item.remark || "-"}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(item.createTime)}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(item.updateTime)}
                    </TableCell>
                    <TableCell className="text-center">
                      <div className="flex justify-center gap-2">
                        <Button variant="outline" size="sm" onClick={() => openEditDialog(item)}>
                          <Pencil className="w-4 h-4 mr-0.5" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(item)}
                        >
                          <Trash2 className="w-4 h-4 mr-0.5" />
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

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

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              删除后该映射规则将不再生效，是否继续？
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

      <Dialog
        open={dialogState.open}
        onOpenChange={(open) =>
          setDialogState((prev) => ({ ...prev, open, item: open ? prev.item : null }))
        }
      >
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新增映射规则" : "编辑映射规则"}</DialogTitle>
            <DialogDescription>配置查询归一化的关键词映射</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">原始词 *</label>
              <Input
                value={form.sourceTerm}
                onChange={(event) => setForm((prev) => ({ ...prev, sourceTerm: event.target.value }))}
                placeholder="用户输入的原始关键词"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">目标词 *</label>
              <Input
                value={form.targetTerm}
                onChange={(event) => setForm((prev) => ({ ...prev, targetTerm: event.target.value }))}
                placeholder="归一化后的目标关键词"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-sm font-medium">匹配类型</label>
                <Select
                  value={String(form.matchType)}
                  onValueChange={(value) => setForm((prev) => ({ ...prev, matchType: Number(value) }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {MATCH_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={String(opt.value)}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">优先级</label>
                <Input
                  type="number"
                  value={form.priority}
                  onChange={(event) => setForm((prev) => ({ ...prev, priority: Number(event.target.value) }))}
                  placeholder="数值越小优先级越高"
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-sm font-medium">启用状态</label>
                <Select
                  value={form.enabled ? "true" : "false"}
                  onValueChange={(value) => setForm((prev) => ({ ...prev, enabled: value === "true" }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="true">启用</SelectItem>
                    <SelectItem value="false">禁用</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">备注</label>
              <Input
                value={form.remark}
                onChange={(event) => setForm((prev) => ({ ...prev, remark: event.target.value }))}
                placeholder="可选备注信息"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDialogState({ open: false, mode: "create", item: null })}
            >
              取消
            </Button>
            <Button onClick={handleSubmit}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
