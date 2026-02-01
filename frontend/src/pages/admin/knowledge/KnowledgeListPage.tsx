import { useEffect, useState } from "react";
import { Database, FileBarChart, FolderOpen, Layers, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useNavigate, useSearchParams } from "react-router-dom";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

import type { KnowledgeBase, PageResult } from "@/services/knowledgeService";
import { deleteKnowledgeBase, getKnowledgeBasesPage, renameKnowledgeBase } from "@/services/knowledgeService";
import { CreateKnowledgeBaseDialog } from "@/components/admin/CreateKnowledgeBaseDialog";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;

export function KnowledgeListPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const nameFromQuery = searchParams.get("name") || "";
  const [pageData, setPageData] = useState<PageResult<KnowledgeBase> | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeBase | null>(null);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [searchName, setSearchName] = useState(nameFromQuery);
  const [keyword, setKeyword] = useState(nameFromQuery);
  const [pageNo, setPageNo] = useState(1);
  const [renameDialog, setRenameDialog] = useState<{ open: boolean; kb: KnowledgeBase | null }>({
    open: false,
    kb: null
  });
  const [renameValue, setRenameValue] = useState("");

  const knowledgeBases = pageData?.records || [];
  const totalCount = pageData?.total ?? knowledgeBases.length;
  const documentCount = knowledgeBases.reduce((sum, kb) => sum + (kb.documentCount ?? 0), 0);
  const activeCount = knowledgeBases.filter((kb) => (kb.documentCount ?? 0) > 0).length;
  const collectionCount = new Set(
    knowledgeBases
      .map((kb) => kb.collectionName)
      .filter((name): name is string => Boolean(name))
  ).size;

  const loadKnowledgeBases = async (current = pageNo, name = keyword) => {
    try {
      setLoading(true);
      const data = await getKnowledgeBasesPage(current, PAGE_SIZE, name || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识库列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadKnowledgeBases();
  }, [pageNo, keyword]);

  useEffect(() => {
    const trimmed = nameFromQuery.trim();
    if (trimmed !== keyword) {
      setSearchName(trimmed);
      setKeyword(trimmed);
      setPageNo(1);
    }
  }, [nameFromQuery, keyword]);

  useEffect(() => {
    if (renameDialog.open) {
      setRenameValue(renameDialog.kb?.name || "");
    }
  }, [renameDialog]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchName.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadKnowledgeBases(1, keyword);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;

    try {
      await deleteKnowledgeBase(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadKnowledgeBases(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const formatStatValue = (value: number) => {
    if (loading) return "--";
    return value.toLocaleString("zh-CN");
  };

  const renderEmbeddingModel = (model?: string) => {
    if (!model) return "-";
    const parts = model.split("-");
    if (parts.length < 2) {
      return <span className="text-sm text-slate-700">{model}</span>;
    }
    const head = parts.slice(0, -1).join("-");
    const tail = parts[parts.length - 1];
    return (
      <div className="flex flex-col text-xs text-slate-500">
        <span className="font-medium text-slate-700">{head}</span>
        <span>{tail}</span>
      </div>
    );
  };

  const getCollectionBadgeClass = (name?: string) => {
    const value = (name || "").toLowerCase();
    if (value.includes("biz")) {
      return "border-blue-200 bg-blue-50 text-blue-700";
    }
    if (value.includes("group")) {
      return "border-purple-200 bg-purple-50 text-purple-700";
    }
    return "border-slate-200 bg-slate-100 text-slate-600";
  };

  const handleRename = async () => {
    if (!renameDialog.kb) return;
    const nextName = renameValue.trim();
    if (!nextName) {
      toast.error("请输入知识库名称");
      return;
    }
    if (nextName === renameDialog.kb.name) {
      setRenameDialog({ open: false, kb: null });
      return;
    }
    try {
      await renameKnowledgeBase(renameDialog.kb.id, nextName);
      toast.success("重命名成功");
      setRenameDialog({ open: false, kb: null });
      await loadKnowledgeBases(pageNo, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "重命名失败"));
      console.error(error);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">知识库管理</h1>
          <p className="admin-page-subtitle">管理所有知识库及其文档</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchName}
            onChange={(event) => setSearchName(event.target.value)}
            placeholder="搜索知识库名称"
            className="w-[220px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setCreateDialogOpen(true)}>
            <Plus className="w-4 h-4 mr-2" />
            新建知识库
          </Button>
        </div>
      </div>

      <div className="admin-stat-grid">
        {[
          { label: "知识库总数", value: totalCount, icon: Database, hint: "总计" },
          { label: "当前页文档", value: documentCount, icon: FileBarChart, hint: "当前页" },
          { label: "含文档知识库", value: activeCount, icon: FolderOpen, hint: "当前页" },
          { label: "Collection 数", value: collectionCount, icon: Layers, hint: "当前页" }
        ].map((item) => {
          const Icon = item.icon;
          return (
            <div key={item.label} className="admin-stat-card">
              <div className="flex items-center gap-3">
                <div className="admin-stat-icon">
                  <Icon className="h-5 w-5" />
                </div>
                <div>
                  <div className="admin-stat-label">{item.label}</div>
                  <div className="admin-stat-value">{formatStatValue(item.value)}</div>
                </div>
              </div>
              <span className="admin-stat-trend text-slate-400">{item.hint}</span>
            </div>
          );
        })}
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : knowledgeBases.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无知识库，点击上方按钮创建
            </div>
          ) : (
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[220px]">名称</TableHead>
                  <TableHead className="w-[160px]">Embedding模型</TableHead>
                  <TableHead className="w-[220px]">Collection</TableHead>
                  <TableHead className="w-[90px]">文档数</TableHead>
                  <TableHead className="w-[120px]">负责人</TableHead>
                  <TableHead className="w-[160px]">创建时间</TableHead>
                  <TableHead className="w-[160px]">修改时间</TableHead>
                  <TableHead className="w-[150px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {knowledgeBases.map((kb) => (
                  <TableRow key={kb.id}>
                    <TableCell className="font-medium">
                      <button
                        type="button"
                        className="admin-link max-w-[200px] truncate"
                        onClick={() => navigate(`/admin/knowledge/${kb.id}`)}
                      >
                        {kb.name}
                      </button>
                    </TableCell>
                    <TableCell>
                      {renderEmbeddingModel(kb.embeddingModel)}
                    </TableCell>
                    <TableCell>
                      {kb.collectionName ? (
                        <Badge
                          variant="outline"
                          className={cn("px-3 py-1", getCollectionBadgeClass(kb.collectionName))}
                        >
                          {kb.collectionName}
                        </Badge>
                      ) : (
                        "-"
                      )}
                    </TableCell>
                    <TableCell>{kb.documentCount ?? "-"}</TableCell>
                    <TableCell>{kb.createdBy || "-"}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(kb.createTime)}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(kb.updateTime)}
                    </TableCell>
                    <TableCell className="text-center">
                      <div className="flex justify-center gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setRenameDialog({ open: true, kb });
                          }}
                        >
                          <Pencil className="w-4 h-4 mr-0.1" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(kb)}
                        >
                          <Trash2 className="w-4 h-4 mr-0.1" />
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

      {/* 删除确认对话框 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              此操作将永久删除该知识库及其所有文档和数据，无法恢复。确定要继续吗？
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

      <Dialog open={renameDialog.open} onOpenChange={(open) => setRenameDialog({ open, kb: open ? renameDialog.kb : null })}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>重命名知识库</DialogTitle>
            <DialogDescription>修改知识库名称</DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <label className="text-sm font-medium">名称</label>
            <Input value={renameValue} onChange={(event) => setRenameValue(event.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameDialog({ open: false, kb: null })}>
              取消
            </Button>
            <Button onClick={handleRename}>保存</Button>
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

      {/* 创建知识库对话框 */}
      <CreateKnowledgeBaseDialog
        open={createDialogOpen}
        onOpenChange={setCreateDialogOpen}
        onSuccess={() => {
          setPageNo(1);
          loadKnowledgeBases(1, keyword);
        }}
      />
    </div>
  );
}
