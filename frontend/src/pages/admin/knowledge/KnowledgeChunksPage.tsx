import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { PenSquare, Plus, RefreshCw, ShieldCheck, ShieldX, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

import type { KnowledgeChunk, KnowledgeDocument, PageResult } from "@/services/knowledgeService";
import {
  batchDisableChunks,
  batchEnableChunks,
  createChunk,
  deleteChunk,
  disableChunk,
  enableChunk,
  getChunksPage,
  getDocument,
  rebuildChunks,
  updateChunk
} from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 12;

const truncateText = (value?: string | null, max = 120) => {
  if (!value) return "-";
  if (value.length <= max) return value;
  return `${value.slice(0, max)}...`;
};

const formatDate = (value?: string | null) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN");
};

const enabledLabel = (enabled?: number | null) => (enabled === 1 ? "启用" : "禁用");

export function KnowledgeChunksPage() {
  const { kbId, docId } = useParams();
  const navigate = useNavigate();
  const [doc, setDoc] = useState<KnowledgeDocument | null>(null);
  const [pageData, setPageData] = useState<PageResult<KnowledgeChunk> | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [loading, setLoading] = useState(false);
  const [enabledFilter, setEnabledFilter] = useState<number | undefined>();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [createOpen, setCreateOpen] = useState(false);
  const [editDialog, setEditDialog] = useState<{ open: boolean; chunk: KnowledgeChunk | null }>({
    open: false,
    chunk: null
  });
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeChunk | null>(null);
  const [batchAllAction, setBatchAllAction] = useState<"enable" | "disable" | null>(null);
  const [rebuildOpen, setRebuildOpen] = useState(false);

  const chunks = pageData?.records || [];

  const selectedList = useMemo(() => Array.from(selectedIds), [selectedIds]);

  const loadDocument = async () => {
    if (!docId) return;
    try {
      const data = await getDocument(docId);
      setDoc(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载文档失败"));
      console.error(error);
    }
  };

  const loadChunks = async (current = pageNo, enabled = enabledFilter) => {
    if (!docId) return;
    setLoading(true);
    try {
      const data = await getChunksPage(docId, {
        current,
        size: PAGE_SIZE,
        enabled
      });
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载分块失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDocument();
  }, [docId]);

  useEffect(() => {
    loadChunks();
  }, [docId, pageNo, enabledFilter]);

  useEffect(() => {
    setSelectedIds(new Set());
  }, [docId, pageNo, enabledFilter]);

  const allSelected = chunks.length > 0 && chunks.every((chunk) => selectedIds.has(String(chunk.id)));

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelectedIds(new Set());
      return;
    }
    const next = new Set(selectedIds);
    chunks.forEach((chunk) => next.add(String(chunk.id)));
    setSelectedIds(next);
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const handleBatchEnable = async () => {
    if (!docId) return;
    if (selectedList.length === 0) {
      toast.error("请选择需要操作的分块");
      return;
    }
    try {
      await batchEnableChunks(docId, selectedList);
      toast.success("批量启用成功");
      setSelectedIds(new Set());
      await loadChunks(pageNo, enabledFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "批量启用失败"));
      console.error(error);
    }
  };

  const handleBatchDisable = async () => {
    if (!docId) return;
    if (selectedList.length === 0) {
      toast.error("请选择需要操作的分块");
      return;
    }
    try {
      await batchDisableChunks(docId, selectedList);
      toast.success("批量禁用成功");
      setSelectedIds(new Set());
      await loadChunks(pageNo, enabledFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "批量禁用失败"));
      console.error(error);
    }
  };

  const handleBatchAll = async () => {
    if (!docId || !batchAllAction) return;
    try {
      if (batchAllAction === "enable") {
        await batchEnableChunks(docId);
      } else {
        await batchDisableChunks(docId);
      }
      toast.success(batchAllAction === "enable" ? "全量启用完成" : "全量禁用完成");
      setBatchAllAction(null);
      setPageNo(1);
      await loadChunks(1, enabledFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "批量操作失败"));
      console.error(error);
    }
  };

  const handleDelete = async () => {
    if (!docId || !deleteTarget) return;
    try {
      await deleteChunk(docId, String(deleteTarget.id));
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadChunks(pageNo, enabledFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    }
  };

  const handleRebuild = async () => {
    if (!docId) return;
    try {
      await rebuildChunks(docId);
      toast.success("重建完成");
      setRebuildOpen(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "重建失败"));
      console.error(error);
    }
  };

  const handleToggleEnabled = async (chunk: KnowledgeChunk) => {
    if (!docId) return;
    try {
      if (chunk.enabled === 1) {
        await disableChunk(docId, String(chunk.id));
        toast.success("已禁用");
      } else {
        await enableChunk(docId, String(chunk.id));
        toast.success("已启用");
      }
      await loadChunks(pageNo, enabledFilter);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
      console.error(error);
    }
  };

  return (
    <div className="p-8">
      <div className="mb-6 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">分块管理</h1>
          <p className="text-sm text-muted-foreground">
            {doc?.docName || docId} {kbId ? `（知识库: ${kbId}）` : ""}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={() => navigate(`/admin/knowledge/${kbId}`)}>
            返回文档
          </Button>
          <Button variant="outline" onClick={() => setRebuildOpen(true)}>
            重建向量
          </Button>
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            新建分块
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <CardTitle>Chunk 列表</CardTitle>
              <CardDescription>支持编辑、启停、批量操作</CardDescription>
            </div>
            <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
              <Select
                value={enabledFilter === undefined ? "all" : String(enabledFilter)}
                onValueChange={(value) => {
                  setPageNo(1);
                  setEnabledFilter(value === "all" ? undefined : Number(value));
                }}
              >
                <SelectTrigger className="w-[160px]">
                  <SelectValue placeholder="启用状态" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部状态</SelectItem>
                  <SelectItem value="1">启用</SelectItem>
                  <SelectItem value="0">禁用</SelectItem>
                </SelectContent>
              </Select>
              <Button
                variant="outline"
                onClick={() => {
                  setPageNo(1);
                  loadChunks(1, enabledFilter);
                }}
              >
                <RefreshCw className="mr-2 h-4 w-4" />
                刷新
              </Button>
              <Button variant="outline" onClick={handleBatchEnable} disabled={selectedList.length === 0}>
                <ShieldCheck className="mr-2 h-4 w-4" />
                批量启用
              </Button>
              <Button variant="outline" onClick={handleBatchDisable} disabled={selectedList.length === 0}>
                <ShieldX className="mr-2 h-4 w-4" />
                批量禁用
              </Button>
              <Button variant="outline" onClick={() => setBatchAllAction("enable")}>全量启用</Button>
              <Button variant="outline" onClick={() => setBatchAllAction("disable")}>全量禁用</Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : chunks.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无分块</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[48px]">
                    <input type="checkbox" checked={allSelected} onChange={toggleSelectAll} />
                  </TableHead>
                  <TableHead>序号</TableHead>
                  <TableHead>内容</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>字符数</TableHead>
                  <TableHead>Token数</TableHead>
                  <TableHead>更新时间</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {chunks.map((chunk) => (
                  <TableRow key={chunk.id}>
                    <TableCell>
                      <input
                        type="checkbox"
                        checked={selectedIds.has(String(chunk.id))}
                        onChange={() => toggleSelect(String(chunk.id))}
                      />
                    </TableCell>
                    <TableCell>{chunk.chunkIndex ?? "-"}</TableCell>
                    <TableCell className="max-w-[360px] text-sm text-muted-foreground">
                      {truncateText(chunk.content)}
                    </TableCell>
                    <TableCell>
                      <Badge variant={chunk.enabled === 1 ? "default" : "outline"}>
                        {enabledLabel(chunk.enabled)}
                      </Badge>
                    </TableCell>
                    <TableCell>{chunk.charCount ?? "-"}</TableCell>
                    <TableCell>{chunk.tokenCount ?? "-"}</TableCell>
                    <TableCell>{formatDate(chunk.updateTime)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button size="sm" variant="outline" onClick={() => setEditDialog({ open: true, chunk })}>
                          <PenSquare className="mr-0.1 h-4 w-4" />
                          编辑
                        </Button>
                        <Button size="sm" variant="outline" onClick={() => handleToggleEnabled(chunk)}>
                          {chunk.enabled === 1 ? "禁用" : "启用"}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(chunk)}
                        >
                          <Trash2 className="mr-0.1 h-4 w-4" />
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          {pageData ? (
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
              <span>共 {pageData.total} 条</span>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => setPageNo((prev) => Math.max(1, prev - 1))} disabled={pageData.current <= 1}>
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
        </CardContent>
      </Card>

      <ChunkDialog
        mode="create"
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSubmit={async (payload) => {
          if (!docId) return;
          await createChunk(docId, payload);
          toast.success("创建成功");
          setCreateOpen(false);
          await loadChunks(pageNo, enabledFilter);
        }}
      />

      <ChunkDialog
        mode="edit"
        open={editDialog.open}
        chunk={editDialog.chunk}
        onOpenChange={(open) => setEditDialog({ open, chunk: open ? editDialog.chunk : null })}
        onSubmit={async (payload) => {
          if (!docId || !editDialog.chunk) return;
          await updateChunk(docId, String(editDialog.chunk.id), { content: payload.content });
          toast.success("更新成功");
          setEditDialog({ open: false, chunk: null });
          await loadChunks(pageNo, enabledFilter);
        }}
      />

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => (!open ? setDeleteTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除分块？</AlertDialogTitle>
            <AlertDialogDescription>该分块将被删除且向量会清理。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={Boolean(batchAllAction)} onOpenChange={(open) => (!open ? setBatchAllAction(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认全量操作？</AlertDialogTitle>
            <AlertDialogDescription>
              将对该文档下所有分块执行{batchAllAction === "enable" ? "启用" : "禁用"}操作。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleBatchAll}>确认</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={rebuildOpen} onOpenChange={setRebuildOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>重建向量索引？</AlertDialogTitle>
            <AlertDialogDescription>会基于当前启用的分块重新生成向量。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleRebuild}>确认</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

interface ChunkDialogProps {
  mode: "create" | "edit";
  open: boolean;
  chunk?: KnowledgeChunk | null;
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: { content: string; index?: number | null; chunkId?: string }) => Promise<void>;
}

function ChunkDialog({ mode, open, chunk, onOpenChange, onSubmit }: ChunkDialogProps) {
  const [content, setContent] = useState("");
  const [indexValue, setIndexValue] = useState<string>("");
  const [chunkId, setChunkId] = useState<string>("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (mode === "edit") {
      setContent(chunk?.content || "");
      setIndexValue("");
      setChunkId("");
      return;
    }
    const generatedId = `${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, "0")}`;
    setContent("");
    setIndexValue("");
    setChunkId(generatedId);
  }, [open, mode, chunk]);

  const handleSubmit = async () => {
    const trimmed = content.trim();
    if (!trimmed) {
      toast.error("请输入内容");
      return;
    }

    const payload = {
      content: trimmed,
      index: indexValue.trim() ? Number(indexValue) : undefined,
      chunkId: mode === "create" ? chunkId.trim() : undefined
    };

    if (payload.index !== undefined && Number.isNaN(payload.index)) {
      toast.error("索引必须是数字");
      return;
    }

    if (mode === "create" && !payload.chunkId) {
      toast.error("Chunk ID 不能为空");
      return;
    }

    setSaving(true);
    try {
      await onSubmit(payload);
    } catch (error) {
      toast.error(getErrorMessage(error, mode === "create" ? "创建失败" : "更新失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[720px] overflow-hidden flex flex-col max-h-[90vh]" onOpenAutoFocus={(e) => e.preventDefault()}>
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建分块" : "编辑分块"}</DialogTitle>
          <DialogDescription>手动维护分块内容</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 overflow-y-auto flex-1 px-1 sidebar-scroll">
          {mode === "create" ? (
            <>
              <div>
                <label className="text-sm font-medium">Chunk ID（数字）</label>
                <Input value={chunkId} onChange={(event) => setChunkId(event.target.value)} />
              </div>
              <div>
                <label className="text-sm font-medium">序号（可选）</label>
                <Input value={indexValue} onChange={(event) => setIndexValue(event.target.value)} placeholder="例如：0" />
              </div>
            </>
          ) : null}
          <div>
            <label className="text-sm font-medium">内容</label>
            <Textarea className="resize-none" rows={8} value={content} onChange={(event) => setContent(event.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={saving}>
            {saving ? "保存中..." : "保存"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
