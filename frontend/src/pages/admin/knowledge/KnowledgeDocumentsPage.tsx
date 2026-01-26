import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { FileUp, FolderOpen, PlayCircle, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

import type { KnowledgeBase, KnowledgeDocument, KnowledgeDocumentUploadPayload, PageResult } from "@/services/knowledgeService";
import {
  deleteDocument,
  enableDocument,
  getKnowledgeBase,
  getDocumentsPage,
  startDocumentChunk,
  uploadDocument
} from "@/services/knowledgeService";
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

const INT_MAX = 2147483647;

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

const formatSourceLabel = (sourceType?: string | null) => {
  const normalized = sourceType?.toLowerCase();
  if (normalized === "url") return "URL";
  if (normalized === "file") return "Local File";
  return "-";
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
                  <TableHead>来源</TableHead>
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
                      <div className="flex min-w-0 max-w-[280px] items-center gap-2">
                        <FolderOpen className="h-4 w-4 text-muted-foreground" />
                        <button
                          type="button"
                          className="flex-1 truncate text-left text-primary underline-offset-4 hover:underline"
                          title={doc.docName || ""}
                          onClick={() => navigate(`/admin/knowledge/${kbId}/docs/${doc.id}`)}
                        >
                          {doc.docName || "-"}
                        </button>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex min-w-0 max-w-[240px] items-center gap-2">
                        <span className="shrink-0 text-xs text-muted-foreground">
                          {formatSourceLabel(doc.sourceType)}
                        </span>
                        {doc.sourceType?.toLowerCase() === "url" && doc.sourceLocation ? (
                          <span className="truncate" title={doc.sourceLocation}>
                            {doc.sourceLocation}
                          </span>
                        ) : null}
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
  onSubmit: (payload: KnowledgeDocumentUploadPayload) => Promise<void>;
}

const uploadSchema = z
  .object({
    sourceType: z.enum(["file", "url"]),
    sourceLocation: z.string().optional(),
    scheduleEnabled: z.boolean().default(false),
    scheduleCron: z.string().optional(),
    chunkStrategy: z.enum(["fixed_size", "structure_aware"]),
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
        message: "请输入来源位置"
      });
    }
    if (values.scheduleEnabled && isBlank(values.scheduleCron)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["scheduleCron"],
        message: "请输入定时频率"
      });
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
  });

type UploadFormValues = z.infer<typeof uploadSchema>;

function UploadDialog({ open, onOpenChange, onSubmit }: UploadDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const form = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
    defaultValues: {
      sourceType: "file",
      sourceLocation: "",
      scheduleEnabled: false,
      scheduleCron: "",
      chunkStrategy: "fixed_size",
      chunkSize: "512",
      overlapSize: "128",
      targetChars: "1400",
      maxChars: "1800",
      minChars: "600",
      overlapChars: "0"
    }
  });

  const sourceType = form.watch("sourceType");
  const chunkStrategy = form.watch("chunkStrategy");
  const scheduleEnabled = form.watch("scheduleEnabled");
  const isUrlSource = sourceType === "url";
  const isFixedSize = chunkStrategy === "fixed_size";

  useEffect(() => {
    if (open) {
      setFile(null);
      form.reset();
    }
  }, [open, form]);

  useEffect(() => {
    if (isUrlSource) {
      setFile(null);
    }
  }, [isUrlSource]);

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
        chunkStrategy: values.chunkStrategy,
        chunkSize: values.chunkStrategy === "fixed_size" ? chunkSize : null,
        overlapSize: values.chunkStrategy === "fixed_size" ? overlapSize : null,
        targetChars: values.chunkStrategy === "structure_aware" ? targetChars : null,
        maxChars: values.chunkStrategy === "structure_aware" ? maxChars : null,
        minChars: values.chunkStrategy === "structure_aware" ? minChars : null,
        overlapChars: values.chunkStrategy === "structure_aware" ? overlapChars : null
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
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[620px]">
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
                    <FormLabel>来源位置</FormLabel>
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
                              onClick={() => form.setValue("chunkSize", String(INT_MAX))}
                            >
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
