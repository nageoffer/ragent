import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { FileUp, FolderOpen, PlayCircle, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

import type { KnowledgeBase, KnowledgeDocument, PageResult } from "@/services/knowledgeService";
import {
  deleteDocument,
  enableDocument,
  getKnowledgeBase,
  getDocumentsPage,
  startDocumentChunk,
  uploadDocument
} from "@/services/knowledgeService";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "failed", label: "failed" },
  { value: "success", label: "success" }
];

const statusBadgeVariant = (status?: string | null) => {
  if (!status) return "outline";
  const normalized = status.toLowerCase();
  if (normalized === "success") return "default";
  if (normalized === "failed") return "destructive";
  if (normalized === "running") return "secondary";
  return "outline";
};

const formatDate = (value?: string | null) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN");
};

const formatSize = (size?: number | null) => {
  if (!size && size !== 0) return "-";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`;
};

export function KnowledgeDocumentsPage() {
  const { kbId } = useParams();
  const navigate = useNavigate();
  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [pageData, setPageData] = useState<PageResult<KnowledgeDocument> | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [uploadOpen, setUploadOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeDocument | null>(null);
  const [chunkTarget, setChunkTarget] = useState<KnowledgeDocument | null>(null);

  const documents = pageData?.records || [];

  const loadKnowledgeBase = async () => {
    if (!kbId) return;
    try {
      const data = await getKnowledgeBase(kbId);
      setKb(data);
    } catch (error) {
      toast.error("加载知识库失败");
      console.error(error);
    }
  };

  const loadDocuments = async (current = pageNo, status = statusFilter, keywordValue = keyword) => {
    if (!kbId) return;
    setLoading(true);
    try {
      const data = await getDocumentsPage(kbId, {
        pageNo: current,
        pageSize: PAGE_SIZE,
        status,
        keyword: keywordValue || undefined
      });
      setPageData(data);
    } catch (error) {
      toast.error("加载文档失败");
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadKnowledgeBase();
  }, [kbId]);

  useEffect(() => {
    loadDocuments();
  }, [kbId, pageNo, statusFilter, keyword]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchInput.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadDocuments(1, statusFilter, keyword);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDocument(String(deleteTarget.id));
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadDocuments(1, statusFilter, keyword);
    } catch (error) {
      toast.error("删除失败");
      console.error(error);
    }
  };

  const handleChunk = async () => {
    if (!chunkTarget) return;
    try {
      await startDocumentChunk(String(chunkTarget.id));
      toast.success("已开始分块");
      setChunkTarget(null);
      await loadDocuments(pageNo, statusFilter, keyword);
    } catch (error) {
      toast.error("分块失败");
      console.error(error);
    }
  };

  const handleToggleEnabled = async (doc: KnowledgeDocument) => {
    const enabled = Boolean(doc.enabled);
    try {
      await enableDocument(String(doc.id), !enabled);
      toast.success(!enabled ? "已启用" : "已禁用");
      await loadDocuments(pageNo, statusFilter, keyword);
    } catch (error) {
      toast.error("操作失败");
      console.error(error);
    }
  };

  return (
    <div className="p-8">
      <div className="mb-6 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">文档管理</h1>
          <p className="text-sm text-muted-foreground">
            {kb ? `${kb.name}（${kb.collectionName}）` : kbId}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => navigate("/admin/knowledge")}>
            返回知识库
          </Button>
          <Button onClick={() => setUploadOpen(true)}>
            <FileUp className="mr-2 h-4 w-4" />
            上传文档
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <CardTitle>文档列表</CardTitle>
              <CardDescription>支持筛选与分块管理</CardDescription>
            </div>
            <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
              <Input
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder="搜索文档名称"
                className="max-w-xs"
              />
              <Button variant="outline" onClick={handleSearch}>
                搜索
              </Button>
              <Select
                value={statusFilter || "all"}
                onValueChange={(value) => {
                  setPageNo(1);
                  setStatusFilter(value === "all" ? undefined : value);
                }}
              >
                <SelectTrigger className="w-[160px]">
                  <SelectValue placeholder="状态" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">全部状态</SelectItem>
                  {STATUS_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button variant="outline" onClick={handleRefresh}>
                <RefreshCw className="mr-2 h-4 w-4" />
                刷新
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : documents.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无文档</div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>文档</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>启用</TableHead>
                  <TableHead>分块数</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead>大小</TableHead>
                  <TableHead>更新时间</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {documents.map((doc) => (
                  <TableRow key={doc.id}>
                    <TableCell className="font-medium">
                      <div className="flex items-center gap-2">
                        <FolderOpen className="h-4 w-4 text-muted-foreground" />
                        <span>{doc.docName || "-"}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={statusBadgeVariant(doc.status)}>{doc.status || "-"}</Badge>
                    </TableCell>
                    <TableCell>{Boolean(doc.enabled) ? "启用" : "禁用"}</TableCell>
                    <TableCell>{doc.chunkCount ?? "-"}</TableCell>
                    <TableCell>{doc.fileType || "-"}</TableCell>
                    <TableCell>{formatSize(doc.fileSize)}</TableCell>
                    <TableCell>{formatDate(doc.updateTime)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => navigate(`/admin/knowledge/${kbId}/docs/${doc.id}`)}
                        >
                          管理分块
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setChunkTarget(doc)}
                        >
                          <PlayCircle className="mr-2 h-4 w-4" />
                          分块
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleToggleEnabled(doc)}
                        >
                          {Boolean(doc.enabled) ? "禁用" : "启用"}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(doc)}
                        >
                          <Trash2 className="mr-2 h-4 w-4" />
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

      <UploadDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        onSubmit={async (file) => {
          if (!kbId) return;
          await uploadDocument(kbId, file);
          toast.success("上传成功");
          setUploadOpen(false);
          setPageNo(1);
          await loadDocuments(1, statusFilter, keyword);
        }}
      />

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => (!open ? setDeleteTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除文档？</AlertDialogTitle>
            <AlertDialogDescription>
              文档 [{deleteTarget?.docName}] 将被删除，且向量数据会清理。
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

      <AlertDialog open={Boolean(chunkTarget)} onOpenChange={(open) => (!open ? setChunkTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>开始分块？</AlertDialogTitle>
            <AlertDialogDescription>
              文档 [{chunkTarget?.docName}] 将开始分块并写入向量库。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleChunk}>开始</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

interface UploadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (file: File) => Promise<void>;
}

function UploadDialog({ open, onOpenChange, onSubmit }: UploadDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) {
      setFile(null);
    }
  }, [open]);

  const handleSubmit = async () => {
    if (!file) {
      toast.error("请选择文件");
      return;
    }
    setSaving(true);
    try {
      await onSubmit(file);
    } catch (error) {
      toast.error("上传失败");
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle>上传文档</DialogTitle>
          <DialogDescription>上传文档后可执行分块流程</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <label className="text-sm font-medium">文件</label>
          <Input
            type="file"
            className="mt-1"
            onChange={(event) => setFile(event.target.files?.[0] || null)}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={saving}>
            {saving ? "上传中..." : "上传"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
