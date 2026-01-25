import { useEffect, useState } from "react";
import { FolderOpen, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";

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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

import type { KnowledgeBase, PageResult } from "@/services/knowledgeService";
import { deleteKnowledgeBase, getKnowledgeBasesPage, renameKnowledgeBase } from "@/services/knowledgeService";
import { CreateKnowledgeBaseDialog } from "@/components/admin/CreateKnowledgeBaseDialog";

const PAGE_SIZE = 10;

export function KnowledgeListPage() {
  const navigate = useNavigate();
  const [pageData, setPageData] = useState<PageResult<KnowledgeBase> | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeBase | null>(null);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [searchName, setSearchName] = useState("");
  const [keyword, setKeyword] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [renameDialog, setRenameDialog] = useState<{ open: boolean; kb: KnowledgeBase | null }>({
    open: false,
    kb: null
  });
  const [renameValue, setRenameValue] = useState("");

  const knowledgeBases = pageData?.records || [];

  const loadKnowledgeBases = async (current = pageNo, name = keyword) => {
    try {
      setLoading(true);
      const data = await getKnowledgeBasesPage(current, PAGE_SIZE, name || undefined);
      setPageData(data);
    } catch (error) {
      toast.error("加载知识库列表失败");
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadKnowledgeBases();
  }, [pageNo, keyword]);

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
      toast.error("删除失败");
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
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
      toast.error("重命名失败");
      console.error(error);
    }
  };

  return (
    <div className="p-8">
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <CardTitle>知识库管理</CardTitle>
              <CardDescription>管理所有知识库及其文档</CardDescription>
            </div>
            <div className="flex flex-1 items-center justify-end gap-2">
              <Input
                value={searchName}
                onChange={(event) => setSearchName(event.target.value)}
                placeholder="搜索知识库名称"
                className="max-w-xs"
              />
              <Button variant="outline" onClick={handleSearch}>
                搜索
              </Button>
              <Button variant="outline" onClick={handleRefresh}>
                <RefreshCw className="w-4 h-4 mr-2" />
                刷新
              </Button>
              <Button onClick={() => setCreateDialogOpen(true)}>
                <Plus className="w-4 h-4 mr-2" />
                新建知识库
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : knowledgeBases.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无知识库，点击上方按钮创建
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>Embedding模型</TableHead>
                  <TableHead>Collection</TableHead>
                  <TableHead>文档数</TableHead>
                  <TableHead>创建时间</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {knowledgeBases.map((kb) => (
                  <TableRow key={kb.id}>
                    <TableCell className="font-medium">{kb.name}</TableCell>
                    <TableCell>
                      <Badge variant="secondary">{kb.embeddingModel}</Badge>
                    </TableCell>
                    <TableCell>
                      <code className="text-sm bg-muted px-2 py-1 rounded">
                        {kb.collectionName}
                      </code>
                    </TableCell>
                    <TableCell>{kb.documentCount ?? "-"}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(kb.createTime)}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            navigate(`/admin/knowledge/${kb.id}`);
                          }}
                        >
                          <FolderOpen className="w-4 h-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            setRenameDialog({ open: true, kb });
                          }}
                        >
                          <Pencil className="w-4 h-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setDeleteTarget(kb)}
                        >
                          <Trash2 className="w-4 h-4 text-destructive" />
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
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
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
