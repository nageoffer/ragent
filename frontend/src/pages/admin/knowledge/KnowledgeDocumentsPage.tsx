import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Check, FileUp, FolderOpen, PlayCircle, RefreshCw, Trash2, Pencil, FileBarChart } from "lucide-react";
import { toast } from "sonner";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";

import { cn } from "@/lib/utils";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

import type { KnowledgeBase, KnowledgeDocument, KnowledgeDocumentUploadPayload, KnowledgeDocumentChunkLog, PageResult } from "@/services/knowledgeService";
import {
  deleteDocument,
  enableDocument,
  getKnowledgeBase,
  getDocumentsPage,
  getDocument,
  updateDocument,
  startDocumentChunk,
  uploadDocument,
  getChunkLogsPage
} from "@/services/knowledgeService";
import { getIngestionPipelines, type IngestionPipeline } from "@/services/ingestionService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "failed", label: "failed" },
  { value: "success", label: "success" }
];

const SOURCE_OPTIONS = [
  { value: "file", label: "Local File" },
  { value: "url", label: "URL" }
];

const CHUNK_STRATEGY_OPTIONS = [
  { value: "fixed_size", label: "fixed_size" },
  { value: "structure_aware", label: "structure_aware" }
];

const PROCESS_MODE_OPTIONS = [
  { value: "chunk", label: "分块策略" },
  { value: "pipeline", label: "数据通道" }
];

const INT_MAX = 2147483647;
const DEFAULT_CHUNK_SIZE = 512;
const DEFAULT_OVERLAP_SIZE = 128;
const DEFAULT_TARGET_CHARS = 1400;
const DEFAULT_MAX_CHARS = 1800;
const DEFAULT_MIN_CHARS = 600;
const DEFAULT_OVERLAP_CHARS = 0;

const parseChunkConfig = (raw?: string | null): Record<string, unknown> => {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object") {
      return parsed as Record<string, unknown>;
    }
    return {};
  } catch {
    return {};
  }
};

const getConfigNumber = (config: Record<string, unknown>, key: string, fallback: number) => {
  const value = config[key];
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  }
  return fallback;
};

const statusDotClass = (status?: string | null) => {
  if (!status) return "bg-muted-foreground/40";
  const normalized = status.toLowerCase();
  if (normalized === "success") return "bg-emerald-500";
  if (normalized === "failed") return "bg-red-500";
  if (normalized === "running") return "bg-amber-500";
  if (normalized === "pending") return "bg-slate-400";
  return "bg-muted-foreground/40";
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

const formatSourceLabel = (sourceType?: string | null) => {
  const normalized = sourceType?.toLowerCase();
  if (normalized === "url") return "URL";
  if (normalized === "file") return "Local File";
  return "-";
};

const formatProcessMode = (processMode?: string | null) => {
  const normalized = processMode?.toLowerCase();
  if (normalized === "pipeline") return "数据通道";
  if (normalized === "chunk") return "分块策略";
  return "分块策略"; // 默认值
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
  const [detailTarget, setDetailTarget] = useState<KnowledgeDocument | null>(null);
  const [detailName, setDetailName] = useState("");
  const [detailSaving, setDetailSaving] = useState(false);
  const [detailPipelineName, setDetailPipelineName] = useState<string>("");
  const [logTarget, setLogTarget] = useState<KnowledgeDocument | null>(null);
  const [logData, setLogData] = useState<PageResult<KnowledgeDocumentChunkLog> | null>(null);
  const [logLoading, setLogLoading] = useState(false);

  const documents = pageData?.records || [];

  const loadKnowledgeBase = async () => {
    if (!kbId) return;
    try {
      const data = await getKnowledgeBase(kbId);
      setKb(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载知识库失败"));
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
      toast.error(getErrorMessage(error, "加载文档失败"));
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

  useEffect(() => {
    if (detailTarget) {
      setDetailName(detailTarget.docName || "");
      // 如果是 pipeline 模式，加载 pipeline 名称
      if (detailTarget.processMode?.toLowerCase() === "pipeline" && detailTarget.pipelineId) {
        const loadPipelineName = async () => {
          try {
            const result = await getIngestionPipelines(1, 100);
            const pipeline = result.records?.find(p => p.id === String(detailTarget.pipelineId));
            setDetailPipelineName(pipeline?.name || String(detailTarget.pipelineId));
          } catch (error) {
            console.error("加载Pipeline失败", error);
            setDetailPipelineName(String(detailTarget.pipelineId));
          }
        };
        loadPipelineName();
      } else {
        setDetailPipelineName("");
      }
    } else {
      setDetailName("");
      setDetailPipelineName("");
    }
  }, [detailTarget]);

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
      toast.error(getErrorMessage(error, "删除失败"));
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
      toast.error(getErrorMessage(error, "分块失败"));
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
      toast.error(getErrorMessage(error, "操作失败"));
      console.error(error);
    }
  };

  const handleDetailSave = async () => {
    if (!detailTarget) return;
    const nextName = detailName.trim();
    if (!nextName) {
      toast.error("文档名称不能为空");
      return;
    }
    setDetailSaving(true);
    try {
      await updateDocument(String(detailTarget.id), { docName: nextName });
      toast.success("更新成功");
      await loadDocuments(pageNo, statusFilter, keyword);
      setDetailTarget(null);
    } catch (error) {
      toast.error(getErrorMessage(error, "更新失败"));
      console.error(error);
    } finally {
      setDetailSaving(false);
    }
  };

  const loadChunkLogs = async (docId: string) => {
    setLogLoading(true);
    try {
      const data = await getChunkLogsPage(docId, 1, 1);
      setLogData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载分块日志失败"));
      console.error(error);
    } finally {
      setLogLoading(false);
    }
  };

  const handleOpenChunkLogs = (doc: KnowledgeDocument) => {
    setLogTarget(doc);
    loadChunkLogs(String(doc.id));
  };

  const formatDuration = (ms?: number | null) => {
    if (!ms && ms !== 0) return "-";
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  };

  const formatLogStatus = (status?: string) => {
    if (status === "success") return "成功";
    if (status === "failed") return "失败";
    if (status === "running") return "进行中";
    return status || "-";
  };

  const detailSourceType = detailTarget?.sourceType?.toLowerCase();
  const detailIsUrlSource = detailSourceType === "url";
  const detailNameLabel = detailIsUrlSource ? "文档名称" : "本地文件";
  const detailNameHint = detailIsUrlSource ? "仅支持修改文档名称" : "仅支持修改文件名";
  const detailConfig = detailTarget ? parseChunkConfig(detailTarget.chunkConfig) : {};
  const detailChunkStrategy = (detailTarget?.chunkStrategy || "structure_aware").toLowerCase();
  const detailChunkSize =
    detailTarget?.chunkSize ?? getConfigNumber(detailConfig, "chunkSize", DEFAULT_CHUNK_SIZE);
  const detailOverlapSize =
    detailTarget?.overlapSize ?? getConfigNumber(detailConfig, "overlapSize", DEFAULT_OVERLAP_SIZE);
  const detailTargetChars =
    detailTarget?.targetChars ?? getConfigNumber(detailConfig, "targetChars", DEFAULT_TARGET_CHARS);
  const detailMaxChars =
    detailTarget?.maxChars ?? getConfigNumber(detailConfig, "maxChars", DEFAULT_MAX_CHARS);
  const detailMinChars =
    detailTarget?.minChars ?? getConfigNumber(detailConfig, "minChars", DEFAULT_MIN_CHARS);
  const detailOverlapChars =
    detailTarget?.overlapChars ?? getConfigNumber(detailConfig, "overlapChars", DEFAULT_OVERLAP_CHARS);
  const detailNameChanged = detailTarget ? detailName.trim() !== (detailTarget.docName || "") : false;
  const detailChunkSizeDisplay = detailChunkSize === INT_MAX ? "不分块" : detailChunkSize;

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">文档管理</h1>
          <p className="admin-page-subtitle">
            {kb ? `${kb.name}（${kb.collectionName}）` : kbId}
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate("/admin/knowledge")}>
            返回知识库
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setUploadOpen(true)}>
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
            <Table className="min-w-[1120px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[260px]">文档</TableHead>
                  <TableHead className="w-[120px]">来源</TableHead>
                  <TableHead className="w-[120px]">处理模式</TableHead>
                  <TableHead className="w-[120px]">状态</TableHead>
                  <TableHead className="w-[80px]">启用</TableHead>
                  <TableHead className="w-[90px]">分块数</TableHead>
                  <TableHead className="w-[90px]">类型</TableHead>
                  <TableHead className="w-[90px]">大小</TableHead>
                  <TableHead className="w-[170px]">更新时间</TableHead>
                  <TableHead className="w-[160px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {documents.map((doc) => (
                  <TableRow key={doc.id}>
                    <TableCell className="font-medium">
                      <div className="flex min-w-0 max-w-[280px] items-center gap-2">
                        <FolderOpen className="h-4 w-4 text-muted-foreground" />
                        <button
                          type="button"
                          className="admin-link flex-1 truncate text-left"
                          title={doc.docName || ""}
                          onClick={() => navigate(`/admin/knowledge/${kbId}/docs/${doc.id}`)}
                        >
                          {doc.docName || "-"}
                        </button>
                      </div>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs text-muted-foreground">
                        {formatSourceLabel(doc.sourceType)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs text-muted-foreground">
                        {doc.processMode || "-"}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="inline-flex items-center gap-2 text-xs text-muted-foreground">
                        <span className={cn("h-2 w-2 rounded-full", statusDotClass(doc.status))} />
                        <span>{doc.status || "-"}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      {(() => {
                        const enabled = Boolean(doc.enabled);
                        return (
                          <button
                            type="button"
                            role="switch"
                            aria-checked={enabled}
                            aria-label={enabled ? "已启用，点击禁用" : "已禁用，点击启用"}
                            onClick={() => handleToggleEnabled(doc)}
                            className={cn(
                              "relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:ring-offset-background",
                              enabled ? "bg-blue-600" : "bg-slate-200"
                            )}
                          >
                            <span
                              className={cn(
                                "inline-block h-4 w-4 transform rounded-full bg-background shadow transition-transform",
                                enabled ? "translate-x-4" : "translate-x-1"
                              )}
                            />
                          </button>
                        );
                      })()}
                    </TableCell>
                    <TableCell>{doc.chunkCount ?? "-"}</TableCell>
                    <TableCell>{doc.fileType || "-"}</TableCell>
                    <TableCell>{formatSize(doc.fileSize)}</TableCell>
                    <TableCell>{formatDate(doc.updateTime)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={async () => {
                            try {
                              const detail = await getDocument(String(doc.id));
                              setDetailTarget(detail);
                            } catch (error) {
                              toast.error(getErrorMessage(error, "加载文档详情失败"));
                            }
                          }}
                          title="编辑"
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={() => setChunkTarget(doc)}
                          title="分块"
                        >
                          <PlayCircle className="h-4 w-4" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={() => handleOpenChunkLogs(doc)}
                          title="分块详情"
                        >
                          <FileBarChart className="h-4 w-4" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(doc)}
                          title="删除"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          {pageData ? (
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
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
        onSubmit={async (payload) => {
          if (!kbId) return;
          await uploadDocument(kbId, payload);
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
            <AlertDialogTitle>{chunkTarget?.chunkCount ? "重新分块？" : "开始分块？"}</AlertDialogTitle>
            <AlertDialogDescription>
              {chunkTarget?.chunkCount ? (
                <>
                  文档 [{chunkTarget?.docName}] 已有 {chunkTarget.chunkCount} 个分块记录。
                  <br />
                  <span className="font-medium text-amber-600">重新分块会清空原有 Chunk 记录及向量数据。</span>
                </>
              ) : (
                <>文档 [{chunkTarget?.docName}] 将开始分块并写入向量库。</>
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleChunk}>
              {chunkTarget?.chunkCount ? "确认" : "开始"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={Boolean(detailTarget)} onOpenChange={(open) => (!open ? setDetailTarget(null) : null)}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sidebar-scroll sm:max-w-[620px]">
          <DialogHeader>
            <DialogTitle>编辑文档</DialogTitle>
            <DialogDescription>修改文档名称，查看文档配置信息</DialogDescription>
          </DialogHeader>
          {detailTarget ? (
            <div className="space-y-4">
              <div>
                <div className="text-sm font-medium mb-2">来源类型</div>
                <Input value={formatSourceLabel(detailTarget.sourceType)} disabled />
              </div>

              <div>
                <div className="text-sm font-medium mb-2">{detailNameLabel}</div>
                <Input value={detailName} onChange={(event) => setDetailName(event.target.value)} />
                <div className="text-sm text-muted-foreground mt-1">{detailNameHint}</div>
              </div>

              {detailIsUrlSource && detailTarget.sourceLocation ? (
                <>
                  <div>
                    <div className="text-sm font-medium mb-2">来源地址</div>
                    <Input value={detailTarget.sourceLocation} disabled />
                  </div>
                  {detailTarget.scheduleEnabled ? (
                    <>
                      <div className="space-y-3 rounded-lg border p-3">
                        <div className="flex items-center justify-between">
                          <div>
                            <div className="text-sm font-medium">开启定时拉取</div>
                            <div className="text-sm text-muted-foreground">开启后按频率自动更新文档</div>
                          </div>
                          <Checkbox checked={Boolean(detailTarget.scheduleEnabled)} disabled />
                        </div>
                        {detailTarget.scheduleCron ? (
                          <div>
                            <div className="text-sm font-medium mb-2">拉取频率</div>
                            <Input value={detailTarget.scheduleCron} disabled />
                            <div className="text-sm text-muted-foreground mt-1">支持 cron 表达式</div>
                          </div>
                        ) : null}
                      </div>
                    </>
                  ) : null}
                </>
              ) : null}

              <div>
                <div className="text-sm font-medium mb-2">处理模式</div>
                <Input value={formatProcessMode(detailTarget.processMode)} disabled />
                <div className="text-sm text-muted-foreground mt-1">
                  分块策略：直接分块；数据通道：使用Pipeline清洗
                </div>
              </div>

              {detailTarget.processMode?.toLowerCase() === "pipeline" ? (
                <div>
                  <div className="text-sm font-medium mb-2">数据通道名称</div>
                  <Input value={detailPipelineName || "-"} disabled />
                </div>
              ) : null}

              {(!detailTarget.processMode || detailTarget.processMode?.toLowerCase() === "chunk") ? (
                <div className="space-y-3 rounded-lg border p-3">
                  <div>
                    <div className="text-sm font-medium mb-2">分块策略</div>
                    <Input
                      value={detailChunkStrategy === "fixed_size" ? "fixed_size" : "structure_aware"}
                      disabled
                    />
                  </div>

                  {detailChunkStrategy === "fixed_size" ? (
                    <div className="grid gap-4 md:grid-cols-2">
                      <div>
                        <div className="text-sm font-medium mb-2">块大小</div>
                        <Input value={detailChunkSizeDisplay ?? "-"} disabled />
                        <div className="text-sm text-muted-foreground mt-1">字符数</div>
                      </div>
                      <div>
                        <div className="text-sm font-medium mb-2">重叠大小</div>
                        <Input value={detailOverlapSize ?? "-"} disabled />
                      </div>
                    </div>
                  ) : (
                    <div className="grid gap-4 md:grid-cols-2">
                      <div>
                        <div className="text-sm font-medium mb-2">理想块大小</div>
                        <Input value={detailTargetChars ?? "-"} disabled />
                      </div>
                      <div>
                        <div className="text-sm font-medium mb-2">块上限</div>
                        <Input value={detailMaxChars ?? "-"} disabled />
                      </div>
                      <div>
                        <div className="text-sm font-medium mb-2">块下限</div>
                        <Input value={detailMinChars ?? "-"} disabled />
                      </div>
                      <div>
                        <div className="text-sm font-medium mb-2">重叠大小</div>
                        <Input value={detailOverlapChars ?? "-"} disabled />
                      </div>
                    </div>
                  )}
                </div>
              ) : null}
            </div>
          ) : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => setDetailTarget(null)} disabled={detailSaving}>
              关闭
            </Button>
            <Button
              onClick={handleDetailSave}
              disabled={detailSaving || !detailName.trim() || !detailNameChanged}
            >
              {detailSaving ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(logTarget)} onOpenChange={(open) => (!open ? setLogTarget(null) : null)}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sidebar-scroll sm:max-w-[800px]">
          <DialogHeader>
            <DialogTitle>分块详情</DialogTitle>
            <DialogDescription>
              文档 [{logTarget?.docName}] 的分块执行日志
            </DialogDescription>
          </DialogHeader>
          {logLoading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : logData && logData.records.length > 0 ? (
            <div className="space-y-4">
              {logData.records.slice(0, 1).map((log) => {
                const isPipelineLog = log.processMode?.toLowerCase() === "pipeline";
                const chunkLabel = isPipelineLog ? "数据通道耗时" : "分块耗时";
                return (
                <div key={log.id} className="rounded-lg border p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium">执行状态:</span>
                      <span className={cn(
                        "text-sm font-medium",
                        log.status === "success" ? "text-emerald-600" :
                        log.status === "failed" ? "text-red-600" :
                        "text-amber-600"
                      )}>
                        {formatLogStatus(log.status)}
                      </span>
                    </div>
                    <span className="text-xs text-muted-foreground">
                      {formatDate(log.createTime)}
                    </span>
                  </div>

                  <div className="grid grid-cols-2 gap-4 text-sm">
                    <div>
                      <span className="text-muted-foreground">处理模式: </span>
                      <span>{log.processMode === "pipeline" ? "数据通道" : "分块策略"}</span>
                    </div>
                    {log.processMode === "chunk" && log.chunkStrategy && (
                      <div>
                        <span className="text-muted-foreground">分块策略: </span>
                        <span>{log.chunkStrategy}</span>
                      </div>
                    )}
                    {log.processMode === "pipeline" && log.pipelineId && (
                      <div>
                        <span className="text-muted-foreground">数据通道: </span>
                        <span>{log.pipelineName || log.pipelineId}</span>
                      </div>
                    )}
                    <div>
                      <span className="text-muted-foreground">分块数量: </span>
                      <span className="font-medium">{log.chunkCount ?? "-"}</span>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                    {!isPipelineLog && (
                      <div>
                        <span className="text-muted-foreground">文本提取: </span>
                        <span className="font-medium">{formatDuration(log.extractDuration)}</span>
                      </div>
                    )}
                    <div>
                      <span className="text-muted-foreground">{chunkLabel}: </span>
                      <span className="font-medium">{formatDuration(log.chunkDuration)}</span>
                    </div>
                    {!isPipelineLog && (
                      <div>
                        <span className="text-muted-foreground">向量化: </span>
                        <span className="font-medium">{formatDuration(log.embeddingDuration)}</span>
                      </div>
                    )}
                    <div>
                      <span className="text-muted-foreground">其他耗时: </span>
                      <span className="font-medium">{formatDuration(log.otherDuration)}</span>
                    </div>
                    <div>
                      <span className="text-muted-foreground">总耗时: </span>
                      <span className="font-medium text-slate-900">{formatDuration(log.totalDuration)}</span>
                    </div>
                  </div>

                  {log.errorMessage && (
                    <div className="rounded bg-red-50 p-3 text-sm text-red-600">
                      <div className="font-medium mb-1">错误信息:</div>
                      <div className="text-xs">{log.errorMessage}</div>
                    </div>
                  )}
                </div>
              )})}

            </div>
          ) : (
            <div className="py-8 text-center text-muted-foreground">暂无分块日志</div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setLogTarget(null)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

interface UploadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: KnowledgeDocumentUploadPayload) => Promise<void>;
}

const uploadSchema = z
  .object({
    sourceType: z.enum(["file", "url"]),
    sourceLocation: z.string().optional(),
    scheduleEnabled: z.boolean().default(false),
    scheduleCron: z.string().optional(),
    processMode: z.enum(["chunk", "pipeline"]).default("chunk"),
    chunkStrategy: z.enum(["fixed_size", "structure_aware"]).optional(),
    pipelineId: z.string().optional(),
    chunkSize: z.string().optional(),
    overlapSize: z.string().optional(),
    targetChars: z.string().optional(),
    maxChars: z.string().optional(),
    minChars: z.string().optional(),
    overlapChars: z.string().optional()
  })
  .superRefine((values, ctx) => {
    const isBlank = (value?: string) => !value || value.trim() === "";
    const requireNumber = (value: string | undefined, field: keyof typeof values, label: string) => {
      if (isBlank(value)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: [field],
          message: `请输入${label}`
        });
        return;
      }
      if (Number.isNaN(Number(value))) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: [field],
          message: `${label}必须是数字`
        });
      }
    };

    if (values.sourceType === "url" && isBlank(values.sourceLocation)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["sourceLocation"],
        message: "请输入来源地址"
      });
    }
    if (values.scheduleEnabled && isBlank(values.scheduleCron)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["scheduleCron"],
        message: "请输入定时频率"
      });
    }

    if (values.processMode === "chunk") {
      if (!values.chunkStrategy) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["chunkStrategy"],
          message: "请选择分块策略"
        });
        return;
      }
      if (values.chunkStrategy === "fixed_size") {
        requireNumber(values.chunkSize, "chunkSize", "块大小");
        requireNumber(values.overlapSize, "overlapSize", "重叠大小");
      } else {
        requireNumber(values.targetChars, "targetChars", "理想块大小");
        requireNumber(values.maxChars, "maxChars", "块上限");
        requireNumber(values.minChars, "minChars", "块下限");
        requireNumber(values.overlapChars, "overlapChars", "重叠大小");
      }
    } else if (values.processMode === "pipeline") {
      if (isBlank(values.pipelineId)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["pipelineId"],
          message: "请选择数据通道"
        });
      }
    }
  });

type UploadFormValues = z.infer<typeof uploadSchema>;

function UploadDialog({ open, onOpenChange, onSubmit }: UploadDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const [noChunk, setNoChunk] = useState(false);
  const [originalChunkSize, setOriginalChunkSize] = useState("512");
  const [pipelines, setPipelines] = useState<IngestionPipeline[]>([]);
  const [loadingPipelines, setLoadingPipelines] = useState(false);

  const form = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
    defaultValues: {
      sourceType: "file",
      sourceLocation: "",
      scheduleEnabled: false,
      scheduleCron: "",
      processMode: "chunk",
      chunkStrategy: "fixed_size",
      pipelineId: "",
      chunkSize: "512",
      overlapSize: "128",
      targetChars: "1400",
      maxChars: "1800",
      minChars: "600",
      overlapChars: "0"
    }
  });

  const sourceType = form.watch("sourceType");
  const processMode = form.watch("processMode");
  const chunkStrategy = form.watch("chunkStrategy");
  const scheduleEnabled = form.watch("scheduleEnabled");
  const chunkSize = form.watch("chunkSize");
  const isUrlSource = sourceType === "url";
  const isChunkMode = processMode === "chunk";
  const isPipelineMode = processMode === "pipeline";
  const isFixedSize = chunkStrategy === "fixed_size";

  const loadPipelines = async () => {
    setLoadingPipelines(true);
    try {
      const result = await getIngestionPipelines(1, 100);
      setPipelines(result.records || []);
    } catch (error) {
      console.error("加载Pipeline失败", error);
      toast.error("加载Pipeline失败");
    } finally {
      setLoadingPipelines(false);
    }
  };

  useEffect(() => {
    if (open) {
      setFile(null);
      form.reset({
        sourceType: "file",
        sourceLocation: "",
        scheduleEnabled: false,
        scheduleCron: "",
        processMode: "chunk",
        chunkStrategy: "fixed_size",
        pipelineId: "",
        chunkSize: "512",
        overlapSize: "128",
        targetChars: "1400",
        maxChars: "1800",
        minChars: "600",
        overlapChars: "0"
      });
      setNoChunk(false);
      setOriginalChunkSize("512");
      loadPipelines();
    }
  }, [open, form]);

  useEffect(() => {
    if (isUrlSource) {
      setFile(null);
    }
  }, [isUrlSource]);

  // 监听块大小变化，如果用户手动修改了值，取消"不分块"状态
  useEffect(() => {
    if (noChunk && chunkSize !== String(INT_MAX)) {
      setNoChunk(false);
    }
  }, [chunkSize, noChunk]);

  // 处理"不分块"按钮点击
  const handleNoChunkToggle = () => {
    if (noChunk) {
      // 取消选中，恢复原始值
      form.setValue("chunkSize", originalChunkSize);
      setNoChunk(false);
    } else {
      // 选中，保存当前值并设置为最大值
      setOriginalChunkSize(chunkSize || "512");
      form.setValue("chunkSize", String(INT_MAX));
      setNoChunk(true);
    }
  };

  const parseNumber = (value?: string) => {
    if (!value || !value.trim()) return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  };

  const handleSubmit = async (values: UploadFormValues) => {
    if (values.sourceType === "file" && !file) {
      toast.error("请选择文件");
      return;
    }
    const chunkSize = parseNumber(values.chunkSize);
    const overlapSize = parseNumber(values.overlapSize);
    const targetChars = parseNumber(values.targetChars);
    const maxChars = parseNumber(values.maxChars);
    const minChars = parseNumber(values.minChars);
    const overlapChars = parseNumber(values.overlapChars);

    setSaving(true);
    try {
      const payload: KnowledgeDocumentUploadPayload = {
        sourceType: values.sourceType,
        file: values.sourceType === "file" ? file : null,
        sourceLocation: values.sourceType === "url" ? values.sourceLocation.trim() : null,
        scheduleEnabled: values.sourceType === "url" ? values.scheduleEnabled : false,
        scheduleCron:
          values.sourceType === "url" && values.scheduleEnabled
            ? values.scheduleCron.trim()
            : null,
        processMode: values.processMode,
        chunkStrategy: values.processMode === "chunk" ? values.chunkStrategy : undefined,
        chunkSize: values.processMode === "chunk" && values.chunkStrategy === "fixed_size" ? chunkSize : null,
        overlapSize: values.processMode === "chunk" && values.chunkStrategy === "fixed_size" ? overlapSize : null,
        targetChars: values.processMode === "chunk" && values.chunkStrategy === "structure_aware" ? targetChars : null,
        maxChars: values.processMode === "chunk" && values.chunkStrategy === "structure_aware" ? maxChars : null,
        minChars: values.processMode === "chunk" && values.chunkStrategy === "structure_aware" ? minChars : null,
        overlapChars: values.processMode === "chunk" && values.chunkStrategy === "structure_aware" ? overlapChars : null,
        pipelineId: values.processMode === "pipeline" ? values.pipelineId : null
      };
      await onSubmit(payload);
    } catch (error) {
      toast.error(getErrorMessage(error, "上传失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-h-[90vh] overflow-y-auto sm:max-w-[620px]"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>上传文档</DialogTitle>
          <DialogDescription>支持本地文件或远程URL，并配置分块策略</DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form className="space-y-4" onSubmit={form.handleSubmit(handleSubmit)}>
            <FormField
              control={form.control}
              name="sourceType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>来源类型</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="选择来源类型" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {SOURCE_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            {isUrlSource ? (
              <FormField
                control={form.control}
                name="sourceLocation"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>来源地址</FormLabel>
                    <FormControl>
                      <Input
                        placeholder="https://raw.githubusercontent.com/bytedance/deer-flow/main/docs/API.md"
                        {...field}
                      />
                    </FormControl>
                    <FormDescription>填写远程文档 URL</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : (
              <FormItem>
                <FormLabel>本地文件</FormLabel>
                <FormControl>
                  <Input type="file" onChange={(event) => setFile(event.target.files?.[0] || null)} />
                </FormControl>
              </FormItem>
            )}

            {isUrlSource ? (
              <div className="space-y-3 rounded-lg border p-3">
                <FormField
                  control={form.control}
                  name="scheduleEnabled"
                  render={({ field }) => (
                    <FormItem className="flex items-center justify-between">
                      <div>
                        <FormLabel>开启定时拉取</FormLabel>
                        <FormDescription>开启后按频率自动更新文档</FormDescription>
                      </div>
                      <FormControl>
                        <Checkbox checked={field.value} onCheckedChange={(value) => field.onChange(Boolean(value))} />
                      </FormControl>
                    </FormItem>
                  )}
                />
                {scheduleEnabled ? (
                  <FormField
                    control={form.control}
                    name="scheduleCron"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>拉取频率</FormLabel>
                        <FormControl>
                          <Input placeholder="例如：0 0 0 * * ?" {...field} />
                        </FormControl>
                        <FormDescription>支持 cron 表达式，例如每天凌晨</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                ) : null}
              </div>
            ) : null}

            <FormField
              control={form.control}
              name="processMode"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>处理模式</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="选择处理模式" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {PROCESS_MODE_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormDescription>
                    分块策略：直接分块；数据通道：使用Pipeline清洗
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {isPipelineMode ? (
              <FormField
                control={form.control}
                name="pipelineId"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>数据通道</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange} disabled={loadingPipelines}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder={loadingPipelines ? "加载中..." : "选择数据通道"} />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {pipelines.map((pipeline) => (
                          <SelectItem key={pipeline.id} value={pipeline.id}>
                            {pipeline.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormDescription>选择用于数据清洗的Pipeline</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : null}

            {isChunkMode ? (
              <div className="space-y-3 rounded-lg border p-3">
              <FormField
                control={form.control}
                name="chunkStrategy"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>分块策略</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder="选择分块策略" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {CHUNK_STRATEGY_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {isFixedSize ? (
                <div className="grid gap-4 md:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="chunkSize"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>块大小</FormLabel>
                        <FormControl>
                          <div className="flex items-center gap-2">
                            <Input type="number" {...field} />
                            <Button
                              type="button"
                              variant="outline"
                              onClick={handleNoChunkToggle}
                              className={noChunk
                                ? "bg-slate-100 border-slate-400 font-medium"
                                : ""
                              }
                            >
                              {noChunk && <Check className="w-4 h-4 mr-1" />}
                              不分块
                            </Button>
                          </div>
                        </FormControl>
                        <FormDescription>字符数，选择不分块会写入最大值</FormDescription>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="overlapSize"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>重叠大小</FormLabel>
                        <FormControl>
                          <Input type="number" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              ) : (
                <div className="grid gap-4 md:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="targetChars"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>理想块大小</FormLabel>
                        <FormControl>
                          <Input type="number" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="maxChars"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>块上限</FormLabel>
                        <FormControl>
                          <Input type="number" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="minChars"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>块下限</FormLabel>
                        <FormControl>
                          <Input type="number" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="overlapChars"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>重叠大小</FormLabel>
                        <FormControl>
                          <Input type="number" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              )}
            </div>
            ) : null}

            <DialogFooter>
              <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? "上传中..." : "上传"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
