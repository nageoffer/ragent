import { useEffect, useState } from "react";
import {
  ClipboardList,
  FileUp,
  FolderKanban,
  Pencil,
  Plus,
  RefreshCw,
  Trash2
} from "lucide-react";
import { toast } from "sonner";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import type {
  IngestionPipeline,
  IngestionPipelineNode,
  IngestionPipelinePayload,
  IngestionTask,
  IngestionTaskCreatePayload,
  IngestionTaskNode,
  PageResult
} from "@/services/ingestionService";
import {
  createIngestionPipeline,
  createIngestionTask,
  deleteIngestionPipeline,
  getIngestionPipeline,
  getIngestionPipelines,
  getIngestionTask,
  getIngestionTaskNodes,
  getIngestionTasks,
  updateIngestionPipeline,
  uploadIngestionTask
} from "@/services/ingestionService";
import { getErrorMessage } from "@/utils/error";

const PIPELINE_PAGE_SIZE = 10;
const TASK_PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "completed", label: "completed" },
  { value: "failed", label: "failed" }
];

const SOURCE_OPTIONS = [
  { value: "file", label: "Local File" },
  { value: "url", label: "URL" },
  { value: "feishu", label: "Feishu" },
  { value: "s3", label: "S3" }
];

const NODE_TYPE_OPTIONS = [
  { value: "fetcher", label: "fetcher" },
  { value: "parser", label: "parser" },
  { value: "enhancer", label: "enhancer" },
  { value: "chunker", label: "chunker" },
  { value: "enricher", label: "enricher" },
  { value: "indexer", label: "indexer" }
];

const CHUNK_STRATEGY_OPTIONS = [
  { value: "fixed_size", label: "fixed_size" },
  { value: "structure_aware", label: "structure_aware" },
  { value: "sentence", label: "sentence" },
  { value: "paragraph", label: "paragraph" }
];

const ENHANCER_TASK_OPTIONS = [
  { value: "context_enhance", label: "context_enhance" },
  { value: "keywords", label: "keywords" },
  { value: "questions", label: "questions" },
  { value: "metadata", label: "metadata" }
];

const ENRICHER_TASK_OPTIONS = [
  { value: "keywords", label: "keywords" },
  { value: "summary", label: "summary" },
  { value: "metadata", label: "metadata" }
];

const formatDate = (value?: string | null) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN");
};

const stringifyJson = (value: unknown) => {
  if (!value) return "-";
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};

const truncateJson = (value: unknown, max = 120) => {
  const raw = stringifyJson(value);
  if (raw.length <= max) return raw;
  return `${raw.slice(0, max)}...`;
};

const statusBadgeVariant = (status?: string | null) => {
  if (!status) return "outline";
  const normalized = status.toLowerCase();
  if (normalized === "completed") return "default";
  if (normalized === "failed") return "destructive";
  if (normalized === "running") return "secondary";
  return "outline";
};

const nodeStatusVariant = (status?: string | null) => {
  if (!status) return "outline";
  const normalized = status.toLowerCase();
  if (normalized === "success") return "default";
  if (normalized === "failed") return "destructive";
  return "secondary";
};

const pipelineSchema = z.object({
  name: z.string().min(1, "请输入流水线名称").max(60, "名称不能超过60个字符"),
  description: z.string().optional(),
  nodesJson: z.string().optional()
});

type PipelineFormValues = z.infer<typeof pipelineSchema>;

type PipelineNodeType =
  | "fetcher"
  | "parser"
  | "enhancer"
  | "chunker"
  | "enricher"
  | "indexer";

interface EnhancerTaskForm {
  id: string;
  type: string;
  systemPrompt: string;
  userPromptTemplate: string;
}

interface PipelineNodeForm {
  id: string;
  nodeId: string;
  nodeType: PipelineNodeType;
  nextNodeId: string;
  condition: string;
  chunker: {
    strategy: string;
    chunkSize: string;
    overlapSize: string;
    separator: string;
  };
  enhancer: {
    modelId: string;
    tasks: EnhancerTaskForm[];
  };
  enricher: {
    modelId: string;
    attachDocumentMetadata: boolean;
    tasks: EnhancerTaskForm[];
  };
  parser: {
    rulesJson: string;
  };
  indexer: {
    embeddingModel: string;
    metadataFields: string;
  };
}

const taskSchema = z
  .object({
    pipelineId: z.string().min(1, "请选择流水线"),
    sourceType: z.string().min(1, "请选择来源类型"),
    location: z.string().optional(),
    fileName: z.string().optional(),
    credentialsJson: z.string().optional(),
    metadataJson: z.string().optional()
  })
  .superRefine((values, ctx) => {
    if (values.sourceType !== "file" && !values.location?.trim()) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["location"],
        message: "请输入来源位置"
      });
    }
  });

type TaskFormValues = z.infer<typeof taskSchema>;

export function IngestionPage() {
  const [activeTab, setActiveTab] = useState<"pipelines" | "tasks">("pipelines");
  const [pipelinePage, setPipelinePage] = useState<PageResult<IngestionPipeline> | null>(null);
  const [pipelineKeyword, setPipelineKeyword] = useState("");
  const [pipelineSearch, setPipelineSearch] = useState("");
  const [pipelinePageNo, setPipelinePageNo] = useState(1);
  const [pipelineLoading, setPipelineLoading] = useState(false);
  const [pipelineDialog, setPipelineDialog] = useState<{
    open: boolean;
    mode: "create" | "edit";
    pipeline: IngestionPipeline | null;
  }>({ open: false, mode: "create", pipeline: null });
  const [pipelineNodesDialog, setPipelineNodesDialog] = useState<{
    open: boolean;
    pipeline: IngestionPipeline | null;
  }>({ open: false, pipeline: null });
  const [pipelineDeleteTarget, setPipelineDeleteTarget] = useState<IngestionPipeline | null>(null);

  const [pipelineOptions, setPipelineOptions] = useState<IngestionPipeline[]>([]);

  const [taskPage, setTaskPage] = useState<PageResult<IngestionTask> | null>(null);
  const [taskStatus, setTaskStatus] = useState<string | undefined>();
  const [taskPageNo, setTaskPageNo] = useState(1);
  const [taskLoading, setTaskLoading] = useState(false);
  const [taskDialogOpen, setTaskDialogOpen] = useState(false);
  const [uploadDialogOpen, setUploadDialogOpen] = useState(false);
  const [taskDetail, setTaskDetail] = useState<{ open: boolean; taskId: string | null }>({
    open: false,
    taskId: null
  });

  const pipelines = pipelinePage?.records || [];
  const tasks = taskPage?.records || [];

  const loadPipelines = async (pageNo = pipelinePageNo, keyword = pipelineKeyword) => {
    setPipelineLoading(true);
    try {
      const data = await getIngestionPipelines(pageNo, PIPELINE_PAGE_SIZE, keyword || undefined);
      setPipelinePage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载流水线失败"));
      console.error(error);
    } finally {
      setPipelineLoading(false);
    }
  };

  const loadPipelineOptions = async () => {
    try {
      const data = await getIngestionPipelines(1, 200);
      setPipelineOptions(data.records || []);
    } catch (error) {
      console.error(error);
    }
  };

  const loadTasks = async (pageNo = taskPageNo, status = taskStatus) => {
    setTaskLoading(true);
    try {
      const data = await getIngestionTasks(pageNo, TASK_PAGE_SIZE, status);
      setTaskPage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载任务失败"));
      console.error(error);
    } finally {
      setTaskLoading(false);
    }
  };

  useEffect(() => {
    loadPipelines();
  }, [pipelinePageNo, pipelineKeyword]);

  useEffect(() => {
    loadTasks();
  }, [taskPageNo, taskStatus]);

  useEffect(() => {
    loadPipelineOptions();
  }, []);

  const handlePipelineSearch = () => {
    setPipelinePageNo(1);
    setPipelineKeyword(pipelineSearch.trim());
  };

  const handlePipelineRefresh = () => {
    setPipelinePageNo(1);
    loadPipelines(1, pipelineKeyword);
    loadPipelineOptions();
  };

  const handleTaskRefresh = () => {
    setTaskPageNo(1);
    loadTasks(1, taskStatus);
    loadPipelineOptions();
  };

  const handlePipelineDelete = async () => {
    if (!pipelineDeleteTarget) return;
    try {
      await deleteIngestionPipeline(pipelineDeleteTarget.id);
      toast.success("删除成功");
      setPipelineDeleteTarget(null);
      await loadPipelines(1, pipelineKeyword);
      await loadPipelineOptions();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    }
  };

  const openPipelineNodes = async (pipeline: IngestionPipeline) => {
    try {
      const detail = await getIngestionPipeline(pipeline.id);
      setPipelineNodesDialog({ open: true, pipeline: detail });
    } catch (error) {
      toast.error(getErrorMessage(error, "获取流水线详情失败"));
      console.error(error);
    }
  };

  const taskStatusLabel = (status?: string | null) =>
    status ? status.toLowerCase() : "unknown";

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">数据通道</h1>
          <p className="text-sm text-muted-foreground">管理通道流水线与任务执行情况</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant={activeTab === "pipelines" ? "default" : "outline"}
            size="sm"
            onClick={() => setActiveTab("pipelines")}
          >
            <FolderKanban className="mr-2 h-4 w-4" />
            流水线
          </Button>
          <Button
            variant={activeTab === "tasks" ? "default" : "outline"}
            size="sm"
            onClick={() => setActiveTab("tasks")}
          >
            <ClipboardList className="mr-2 h-4 w-4" />
            任务
          </Button>
        </div>
      </div>

      {activeTab === "pipelines" ? (
        <Card>
          <CardHeader>
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <CardTitle>通道流水线</CardTitle>
                <CardDescription>配置节点顺序与处理逻辑</CardDescription>
              </div>
              <div className="flex flex-1 items-center justify-end gap-2">
                <Input
                  value={pipelineSearch}
                  onChange={(event) => setPipelineSearch(event.target.value)}
                  placeholder="搜索流水线名称"
                  className="max-w-xs"
                />
                <Button variant="outline" onClick={handlePipelineSearch}>
                  搜索
                </Button>
                <Button variant="outline" onClick={handlePipelineRefresh}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
                <Button onClick={() => setPipelineDialog({ open: true, mode: "create", pipeline: null })}>
                  <Plus className="mr-2 h-4 w-4" />
                  新建流水线
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {pipelineLoading ? (
              <div className="py-10 text-center text-muted-foreground">加载中...</div>
            ) : pipelines.length === 0 ? (
              <div className="py-10 text-center text-muted-foreground">暂无流水线</div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>名称</TableHead>
                    <TableHead>描述</TableHead>
                    <TableHead>节点数</TableHead>
                    <TableHead>负责人</TableHead>
                    <TableHead>更新时间</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pipelines.map((pipeline) => (
                    <TableRow key={pipeline.id}>
                      <TableCell className="font-medium">{pipeline.name}</TableCell>
                      <TableCell className="text-muted-foreground">
                        {pipeline.description || "-"}
                      </TableCell>
                      <TableCell>{pipeline.nodes?.length ?? 0}</TableCell>
                      <TableCell>{pipeline.createdBy || "-"}</TableCell>
                      <TableCell>{formatDate(pipeline.updateTime)}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" onClick={() => openPipelineNodes(pipeline)}>
                            查看节点
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setPipelineDialog({ open: true, mode: "edit", pipeline })}
                          >
                            <Pencil className="mr-2 h-4 w-4" />
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setPipelineDeleteTarget(pipeline)}
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

            <Pagination
              current={pipelinePage?.current || 1}
              pages={pipelinePage?.pages || 1}
              total={pipelinePage?.total || 0}
              onPrev={() => setPipelinePageNo((prev) => Math.max(1, prev - 1))}
              onNext={() =>
                setPipelinePageNo((prev) => Math.min(pipelinePage?.pages || 1, prev + 1))
              }
            />
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <CardTitle>通道任务</CardTitle>
                <CardDescription>监控执行状态与节点日志</CardDescription>
              </div>
              <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
                <Select
                  value={taskStatus || "all"}
                  onValueChange={(value) => {
                    setTaskPageNo(1);
                    setTaskStatus(value === "all" ? undefined : value);
                  }}
                >
                  <SelectTrigger className="w-[180px]">
                    <SelectValue placeholder="任务状态" />
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
                <Button variant="outline" onClick={handleTaskRefresh}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
                <Button variant="outline" onClick={() => setUploadDialogOpen(true)}>
                  <FileUp className="mr-2 h-4 w-4" />
                  上传文件
                </Button>
                <Button onClick={() => setTaskDialogOpen(true)}>
                  <Plus className="mr-2 h-4 w-4" />
                  新建任务
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {taskLoading ? (
              <div className="py-10 text-center text-muted-foreground">加载中...</div>
            ) : tasks.length === 0 ? (
              <div className="py-10 text-center text-muted-foreground">暂无任务</div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>任务ID</TableHead>
                    <TableHead>来源</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>负责人</TableHead>
                    <TableHead>分片数</TableHead>
                    <TableHead>创建时间</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tasks.map((task) => (
                    <TableRow key={task.id}>
                      <TableCell className="font-mono text-xs">{task.id}</TableCell>
                      <TableCell>
                        <div className="text-sm">
                          <span className="font-medium">{task.sourceType || "-"}</span>
                          <span className="text-muted-foreground">
                            {" "}
                            {task.sourceFileName || task.sourceLocation || ""}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={statusBadgeVariant(task.status)}>
                          {taskStatusLabel(task.status)}
                        </Badge>
                      </TableCell>
                      <TableCell>{task.createdBy || "-"}</TableCell>
                      <TableCell>{task.chunkCount ?? "-"}</TableCell>
                      <TableCell>{formatDate(task.createTime)}</TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="outline" onClick={() => setTaskDetail({ open: true, taskId: task.id })}>
                          查看详情
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}

            <Pagination
              current={taskPage?.current || 1}
              pages={taskPage?.pages || 1}
              total={taskPage?.total || 0}
              onPrev={() => setTaskPageNo((prev) => Math.max(1, prev - 1))}
              onNext={() => setTaskPageNo((prev) => Math.min(taskPage?.pages || 1, prev + 1))}
            />
          </CardContent>
        </Card>
      )}

      <PipelineDialog
        open={pipelineDialog.open}
        mode={pipelineDialog.mode}
        pipeline={pipelineDialog.pipeline}
        onOpenChange={(open) => setPipelineDialog((prev) => ({ ...prev, open }))}
        onSubmit={async (payload, mode) => {
          if (mode === "create") {
            await createIngestionPipeline(payload);
            toast.success("创建成功");
          } else if (pipelineDialog.pipeline) {
            await updateIngestionPipeline(pipelineDialog.pipeline.id, payload);
            toast.success("更新成功");
          }
          setPipelineDialog({ open: false, mode: "create", pipeline: null });
          await loadPipelines(1, pipelineKeyword);
          await loadPipelineOptions();
        }}
      />

      <PipelineNodesDialog
        open={pipelineNodesDialog.open}
        pipeline={pipelineNodesDialog.pipeline}
        onOpenChange={(open) => setPipelineNodesDialog({ open, pipeline: open ? pipelineNodesDialog.pipeline : null })}
      />

      <AlertDialog open={Boolean(pipelineDeleteTarget)} onOpenChange={(open) => (!open ? setPipelineDeleteTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除流水线？</AlertDialogTitle>
            <AlertDialogDescription>
              流水线 [{pipelineDeleteTarget?.name}] 将被永久删除。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handlePipelineDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <TaskDialog
        open={taskDialogOpen}
        pipelineOptions={pipelineOptions}
        onOpenChange={setTaskDialogOpen}
        onSubmit={async (payload) => {
          const result = await createIngestionTask(payload);
          toast.success(`任务已创建：${result.taskId}`);
          setTaskDialogOpen(false);
          await loadTasks(1, taskStatus);
        }}
        onUpload={async (pipelineId, file) => {
          const result = await uploadIngestionTask(pipelineId, file);
          toast.success(`上传成功：${result.taskId}`);
          setTaskDialogOpen(false);
          await loadTasks(1, taskStatus);
        }}
      />

      <UploadDialog
        open={uploadDialogOpen}
        pipelineOptions={pipelineOptions}
        onOpenChange={setUploadDialogOpen}
        onSubmit={async (pipelineId, file) => {
          const result = await uploadIngestionTask(pipelineId, file);
          toast.success(`上传成功：${result.taskId}`);
          setUploadDialogOpen(false);
          await loadTasks(1, taskStatus);
        }}
      />

      <TaskDetailDialog
        open={taskDetail.open}
        taskId={taskDetail.taskId}
        onOpenChange={(open) => setTaskDetail({ open, taskId: open ? taskDetail.taskId : null })}
      />
    </div>
  );
}

interface PaginationProps {
  current: number;
  pages: number;
  total: number;
  onPrev: () => void;
  onNext: () => void;
}

function Pagination({ current, pages, total, onPrev, onNext }: PaginationProps) {
  if (total === 0) return null;
  return (
    <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
      <span>共 {total} 条</span>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={onPrev} disabled={current <= 1}>
          上一页
        </Button>
        <span>
          {current} / {pages}
        </span>
        <Button variant="outline" size="sm" onClick={onNext} disabled={current >= pages}>
          下一页
        </Button>
      </div>
    </div>
  );
}

interface PipelineDialogProps {
  open: boolean;
  mode: "create" | "edit";
  pipeline: IngestionPipeline | null;
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: IngestionPipelinePayload, mode: "create" | "edit") => Promise<void>;
}

function PipelineDialog({ open, mode, pipeline, onOpenChange, onSubmit }: PipelineDialogProps) {
  const [saving, setSaving] = useState(false);
  const [nodeMode, setNodeMode] = useState<"form" | "json">("form");
  const [nodes, setNodes] = useState<PipelineNodeForm[]>([]);
  const defaultNodes = pipeline?.nodes?.length ? JSON.stringify(pipeline.nodes, null, 2) : "";

  const form = useForm<PipelineFormValues>({
    resolver: zodResolver(pipelineSchema),
    defaultValues: {
      name: pipeline?.name || "",
      description: pipeline?.description || "",
      nodesJson: defaultNodes
    }
  });

  const createLocalId = () => `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;

  const createTask = (type: string) => ({
    id: createLocalId(),
    type,
    systemPrompt: "",
    userPromptTemplate: ""
  });

  const createNode = (nodeType: PipelineNodeType = "fetcher"): PipelineNodeForm => ({
    id: createLocalId(),
    nodeId: "",
    nodeType,
    nextNodeId: "",
    condition: "",
    chunker: {
      strategy: "structure_aware",
      chunkSize: "",
      overlapSize: "",
      separator: ""
    },
    enhancer: {
      modelId: "",
      tasks: []
    },
    enricher: {
      modelId: "",
      attachDocumentMetadata: true,
      tasks: []
    },
    parser: {
      rulesJson: ""
    },
    indexer: {
      embeddingModel: "",
      metadataFields: ""
    }
  });

  const mapSettingsTasks = (tasks: unknown): EnhancerTaskForm[] => {
    if (!Array.isArray(tasks)) return [];
    return tasks.map((task) => ({
      id: createLocalId(),
      type: String((task as { type?: string }).type || ""),
      systemPrompt: String((task as { systemPrompt?: string }).systemPrompt || ""),
      userPromptTemplate: String((task as { userPromptTemplate?: string }).userPromptTemplate || "")
    }));
  };

  const buildNodeForm = (node: IngestionPipelineNode): PipelineNodeForm => {
    const settings = (node.settings as Record<string, unknown>) || {};
    const rawCondition = node.condition as unknown;
    const condition = rawCondition
      ? typeof rawCondition === "string"
        ? rawCondition
        : JSON.stringify(rawCondition, null, 2)
      : "";
    const tasks = mapSettingsTasks((settings as { tasks?: unknown }).tasks);
    const nodeType = (node.nodeType as PipelineNodeType) || "fetcher";
    return {
      id: createLocalId(),
      nodeId: node.nodeId || "",
      nodeType,
      nextNodeId: node.nextNodeId || "",
      condition,
      chunker: {
        strategy: String((settings as { strategy?: string }).strategy || "structure_aware"),
        chunkSize: settings.chunkSize != null ? String(settings.chunkSize) : "",
        overlapSize: settings.overlapSize != null ? String(settings.overlapSize) : "",
        separator: String((settings as { separator?: string }).separator || "")
      },
      enhancer: {
        modelId: String((settings as { modelId?: string }).modelId || ""),
        tasks: nodeType === "enhancer" ? tasks : []
      },
      enricher: {
        modelId: String((settings as { modelId?: string }).modelId || ""),
        attachDocumentMetadata:
          (settings as { attachDocumentMetadata?: boolean }).attachDocumentMetadata ?? true,
        tasks: nodeType === "enricher" ? tasks : []
      },
      parser: {
        rulesJson: Array.isArray((settings as { rules?: unknown }).rules)
          ? JSON.stringify((settings as { rules?: unknown }).rules, null, 2)
          : ""
      },
      indexer: {
        embeddingModel: String((settings as { embeddingModel?: string }).embeddingModel || ""),
        metadataFields: Array.isArray((settings as { metadataFields?: string[] }).metadataFields)
          ? (settings as { metadataFields?: string[] }).metadataFields?.join(", ") || ""
          : ""
      }
    };
  };

  const buildNodesFromPipeline = (source?: IngestionPipelineNode[] | null) => {
    if (!source || source.length === 0) return [];
    return source.map(buildNodeForm);
  };

  const parseCondition = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return JSON.parse(trimmed);
    }
    return trimmed;
  };

  const parseParserRules = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    const parsed = JSON.parse(trimmed);
    if (Array.isArray(parsed)) {
      return { rules: parsed };
    }
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { rules?: unknown }).rules)) {
      return { rules: (parsed as { rules?: unknown }).rules };
    }
    throw new Error("解析规则必须是数组或包含 rules 字段的对象");
  };

  const buildSettings = (node: PipelineNodeForm) => {
    switch (node.nodeType) {
      case "chunker": {
        if (!node.chunker.strategy) {
          throw new Error("分块节点需要选择 strategy");
        }
        const chunkSize = node.chunker.chunkSize.trim();
        const overlapSize = node.chunker.overlapSize.trim();
        const chunkSizeValue = chunkSize ? Number(chunkSize) : undefined;
        const overlapSizeValue = overlapSize ? Number(overlapSize) : undefined;
        if (chunkSizeValue !== undefined && Number.isNaN(chunkSizeValue)) {
          throw new Error("chunkSize 必须是数字");
        }
        if (overlapSizeValue !== undefined && Number.isNaN(overlapSizeValue)) {
          throw new Error("overlapSize 必须是数字");
        }
        return {
          strategy: node.chunker.strategy,
          chunkSize: chunkSizeValue,
          overlapSize: overlapSizeValue,
          separator: node.chunker.separator.trim() || undefined
        };
      }
      case "enhancer": {
        const tasks = node.enhancer.tasks
          .filter((task) => task.type)
          .map((task) => ({
            type: task.type,
            systemPrompt: task.systemPrompt.trim() || undefined,
            userPromptTemplate: task.userPromptTemplate.trim() || undefined
          }));
        const payload: Record<string, unknown> = {};
        if (node.enhancer.modelId.trim()) {
          payload.modelId = node.enhancer.modelId.trim();
        }
        if (tasks.length > 0) {
          payload.tasks = tasks;
        }
        return Object.keys(payload).length ? payload : undefined;
      }
      case "enricher": {
        const tasks = node.enricher.tasks
          .filter((task) => task.type)
          .map((task) => ({
            type: task.type,
            systemPrompt: task.systemPrompt.trim() || undefined,
            userPromptTemplate: task.userPromptTemplate.trim() || undefined
          }));
        const payload: Record<string, unknown> = {
          attachDocumentMetadata: node.enricher.attachDocumentMetadata
        };
        if (node.enricher.modelId.trim()) {
          payload.modelId = node.enricher.modelId.trim();
        }
        if (tasks.length > 0) {
          payload.tasks = tasks;
        }
        return payload;
      }
      case "parser": {
        if (!node.parser.rulesJson.trim()) {
          return undefined;
        }
        return parseParserRules(node.parser.rulesJson);
      }
      case "indexer": {
        const fields = node.indexer.metadataFields
          .split(",")
          .map((item) => item.trim())
          .filter(Boolean);
        const payload: Record<string, unknown> = {};
        if (node.indexer.embeddingModel.trim()) {
          payload.embeddingModel = node.indexer.embeddingModel.trim();
        }
        if (fields.length > 0) {
          payload.metadataFields = fields;
        }
        return Object.keys(payload).length ? payload : undefined;
      }
      case "fetcher":
      default:
        return undefined;
    }
  };

  const buildNodesPayload = (sourceNodes: PipelineNodeForm[]) => {
    const result: IngestionPipelinePayload["nodes"] = [];
    for (const node of sourceNodes) {
      const nodeId = node.nodeId.trim();
      if (!nodeId) {
        return { ok: false as const, message: "节点ID不能为空" };
      }
      if (!node.nodeType) {
        return { ok: false as const, message: "节点类型不能为空" };
      }
      let settings: Record<string, unknown> | undefined;
      let condition: unknown;
      try {
        settings = buildSettings(node) as Record<string, unknown> | undefined;
        condition = parseCondition(node.condition);
      } catch (error) {
        return { ok: false as const, message: error instanceof Error ? error.message : "节点配置错误" };
      }
      result.push({
        nodeId,
        nodeType: node.nodeType,
        settings: settings ?? null,
        condition: condition ?? null,
        nextNodeId: node.nextNodeId.trim() || null
      });
    }
    return { ok: true as const, nodes: result };
  };

  const parseNodesJson = (raw: string | undefined) => {
    if (!raw || !raw.trim()) {
      return { ok: true as const, nodes: [] };
    }
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) {
        return { ok: false as const, message: "节点配置必须是JSON数组" };
      }
      const nodesForm = parsed.map((item) => buildNodeForm(item as IngestionPipelineNode));
      return { ok: true as const, nodes: nodesForm };
    } catch (error) {
      return { ok: false as const, message: "节点配置JSON格式错误" };
    }
  };

  const switchMode = (nextMode: "form" | "json") => {
    if (nextMode === nodeMode) return;
    if (nextMode === "json") {
      const result = buildNodesPayload(nodes);
      if (!result.ok) {
        toast.error(result.message);
        return;
      }
      form.setValue("nodesJson", JSON.stringify(result.nodes, null, 2));
      setNodeMode("json");
      return;
    }
    const parsed = parseNodesJson(form.getValues("nodesJson"));
    if (!parsed.ok) {
      toast.error(parsed.message);
      return;
    }
    setNodes(parsed.nodes);
    setNodeMode("form");
  };

  useEffect(() => {
    if (open) {
      form.reset({
        name: pipeline?.name || "",
        description: pipeline?.description || "",
        nodesJson: defaultNodes
      });
      setNodes(buildNodesFromPipeline(pipeline?.nodes));
      setNodeMode("form");
    }
  }, [open, pipeline, defaultNodes, form]);

  const handleSubmit = async (values: PipelineFormValues) => {
    let nodesPayload: IngestionPipelinePayload["nodes"] | undefined;
    if (nodeMode === "json") {
      if (values.nodesJson && values.nodesJson.trim()) {
        try {
          const parsed = JSON.parse(values.nodesJson);
          if (!Array.isArray(parsed)) {
            form.setError("nodesJson", { message: "节点配置必须是JSON数组" });
            return;
          }
          nodesPayload = parsed.map((item) => ({
            nodeId: String(item.nodeId || "").trim(),
            nodeType: String(item.nodeType || "").trim(),
            settings: item.settings ?? null,
            condition: item.condition ?? null,
            nextNodeId: item.nextNodeId ? String(item.nextNodeId) : null
          }));
          const invalid = nodesPayload.some((node) => !node.nodeId || !node.nodeType);
          if (invalid) {
            form.setError("nodesJson", { message: "每个节点必须包含 nodeId 与 nodeType" });
            return;
          }
        } catch (error) {
          form.setError("nodesJson", { message: "节点配置JSON格式错误" });
          return;
        }
      }
    } else {
      const result = buildNodesPayload(nodes);
      if (!result.ok) {
        toast.error(result.message);
        return;
      }
      nodesPayload = result.nodes;
    }

    setSaving(true);
    try {
      const payload: IngestionPipelinePayload = {
        name: values.name.trim(),
        description: values.description?.trim() || undefined,
        nodes: nodesPayload
      };
      await onSubmit(payload, mode);
    } catch (error) {
      toast.error(getErrorMessage(error, mode === "create" ? "创建失败" : "更新失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[880px]">
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建流水线" : "编辑流水线"}</DialogTitle>
          <DialogDescription>配置节点顺序与处理逻辑</DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form className="space-y-4" onSubmit={form.handleSubmit(handleSubmit)}>
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>流水线名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：通用文档通道" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>描述</FormLabel>
                  <FormControl>
                    <Textarea placeholder="说明流水线用途或流程" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-sm font-medium">节点配置</div>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant={nodeMode === "form" ? "default" : "outline"}
                  onClick={() => switchMode("form")}
                >
                  表单配置
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={nodeMode === "json" ? "default" : "outline"}
                  onClick={() => switchMode("json")}
                >
                  JSON配置
                </Button>
              </div>
            </div>

            {nodeMode === "json" ? (
              <FormField
                control={form.control}
                name="nodesJson"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>节点配置（JSON 数组）</FormLabel>
                    <FormControl>
                      <Textarea
                        placeholder='[{"nodeId":"fetch","nodeType":"fetcher","settings":{},"nextNodeId":"parse"}]'
                        rows={10}
                        {...field}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : (
              <div className="space-y-4">
                {nodes.length === 0 ? (
                  <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                    暂无节点，请添加节点配置
                  </div>
                ) : null}

                {nodes.map((node, index) => (
                  <div key={node.id} className="rounded-lg border p-4 space-y-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Badge variant="outline">{node.nodeType}</Badge>
                        <span className="text-sm text-muted-foreground">节点 {index + 1}</span>
                      </div>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setNodes((prev) => prev.filter((item) => item.id !== node.id))}
                      >
                        删除
                      </Button>
                    </div>

                    <div className="grid gap-4 md:grid-cols-2">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">节点ID</label>
                        <Input
                          value={node.nodeId}
                          onChange={(event) =>
                            setNodes((prev) =>
                              prev.map((item) =>
                                item.id === node.id ? { ...item, nodeId: event.target.value } : item
                              )
                            )
                          }
                          placeholder="例如：fetch"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">节点类型</label>
                        <Select
                          value={node.nodeType}
                          onValueChange={(value) =>
                            setNodes((prev) =>
                              prev.map((item) =>
                                item.id === node.id
                                  ? { ...item, nodeType: value as PipelineNodeType }
                                  : item
                              )
                            )
                          }
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="选择节点类型" />
                          </SelectTrigger>
                          <SelectContent>
                            {NODE_TYPE_OPTIONS.map((option) => (
                              <SelectItem key={option.value} value={option.value}>
                                {option.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">下一节点ID</label>
                        <Input
                          value={node.nextNodeId}
                          onChange={(event) =>
                            setNodes((prev) =>
                              prev.map((item) =>
                                item.id === node.id ? { ...item, nextNodeId: event.target.value } : item
                              )
                            )
                          }
                          placeholder="例如：parse"
                        />
                      </div>
                    </div>

                    {node.nodeType === "fetcher" ? (
                      <div className="rounded-lg bg-muted/50 p-3 text-sm text-muted-foreground">
                        Fetcher 无额外配置
                      </div>
                    ) : null}

                    {node.nodeType === "parser" ? (
                      <div className="space-y-2">
                        <label className="text-sm font-medium">解析规则（JSON）</label>
                        <Textarea
                          rows={5}
                          value={node.parser.rulesJson}
                          onChange={(event) =>
                            setNodes((prev) =>
                              prev.map((item) =>
                                item.id === node.id
                                  ? { ...item, parser: { ...item.parser, rulesJson: event.target.value } }
                                  : item
                              )
                            )
                          }
                          placeholder='[{"mimeType":"PDF","options":{}}]'
                        />
                      </div>
                    ) : null}

                    {node.nodeType === "chunker" ? (
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">分块策略</label>
                          <Select
                            value={node.chunker.strategy}
                            onValueChange={(value) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, chunker: { ...item.chunker, strategy: value } }
                                    : item
                                )
                              )
                            }
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="选择策略" />
                            </SelectTrigger>
                            <SelectContent>
                              {CHUNK_STRATEGY_OPTIONS.map((option) => (
                                <SelectItem key={option.value} value={option.value}>
                                  {option.label}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Chunk Size</label>
                          <Input
                            type="number"
                            value={node.chunker.chunkSize}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, chunker: { ...item.chunker, chunkSize: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="例如：512"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Overlap Size</label>
                          <Input
                            type="number"
                            value={node.chunker.overlapSize}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, chunker: { ...item.chunker, overlapSize: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="例如：128"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">自定义分隔符</label>
                          <Input
                            value={node.chunker.separator}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, chunker: { ...item.chunker, separator: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="可选"
                          />
                        </div>
                      </div>
                    ) : null}

                    {node.nodeType === "enhancer" ? (
                      <div className="space-y-4">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">模型ID</label>
                          <Input
                            value={node.enhancer.modelId}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, enhancer: { ...item.enhancer, modelId: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="可选"
                          />
                        </div>
                        <div className="flex items-center justify-between">
                          <span className="text-sm font-medium">增强任务</span>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? {
                                        ...item,
                                        enhancer: {
                                          ...item.enhancer,
                                          tasks: [...item.enhancer.tasks, createTask("context_enhance")]
                                        }
                                      }
                                    : item
                                )
                              )
                            }
                          >
                            <Plus className="mr-2 h-4 w-4" />
                            添加任务
                          </Button>
                        </div>
                        {node.enhancer.tasks.length === 0 ? (
                          <div className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">
                            暂无任务
                          </div>
                        ) : (
                          <div className="space-y-3">
                            {node.enhancer.tasks.map((task, taskIndex) => (
                              <div key={task.id} className="rounded-md border p-3 space-y-3">
                                <div className="flex items-center justify-between">
                                  <span className="text-xs text-muted-foreground">任务 {taskIndex + 1}</span>
                                  <Button
                                    type="button"
                                    size="sm"
                                    variant="ghost"
                                    className="text-destructive hover:text-destructive"
                                    onClick={() =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enhancer: {
                                                  ...item.enhancer,
                                                  tasks: item.enhancer.tasks.filter((t) => t.id !== task.id)
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                  >
                                    删除
                                  </Button>
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">任务类型</label>
                                  <Select
                                    value={task.type}
                                    onValueChange={(value) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enhancer: {
                                                  ...item.enhancer,
                                                  tasks: item.enhancer.tasks.map((t) =>
                                                    t.id === task.id ? { ...t, type: value } : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                  >
                                    <SelectTrigger>
                                      <SelectValue placeholder="选择类型" />
                                    </SelectTrigger>
                                    <SelectContent>
                                      {ENHANCER_TASK_OPTIONS.map((option) => (
                                        <SelectItem key={option.value} value={option.value}>
                                          {option.label}
                                        </SelectItem>
                                      ))}
                                    </SelectContent>
                                  </Select>
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">System Prompt</label>
                                  <Textarea
                                    rows={2}
                                    value={task.systemPrompt}
                                    onChange={(event) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enhancer: {
                                                  ...item.enhancer,
                                                  tasks: item.enhancer.tasks.map((t) =>
                                                    t.id === task.id
                                                      ? { ...t, systemPrompt: event.target.value }
                                                      : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                    placeholder="可选"
                                  />
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">User Prompt 模板</label>
                                  <Textarea
                                    rows={2}
                                    value={task.userPromptTemplate}
                                    onChange={(event) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enhancer: {
                                                  ...item.enhancer,
                                                  tasks: item.enhancer.tasks.map((t) =>
                                                    t.id === task.id
                                                      ? { ...t, userPromptTemplate: event.target.value }
                                                      : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                    placeholder="可选"
                                  />
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ) : null}

                    {node.nodeType === "enricher" ? (
                      <div className="space-y-4">
                        <div className="grid gap-4 md:grid-cols-2">
                          <div className="space-y-2">
                            <label className="text-sm font-medium">模型ID</label>
                            <Input
                              value={node.enricher.modelId}
                              onChange={(event) =>
                                setNodes((prev) =>
                                  prev.map((item) =>
                                    item.id === node.id
                                      ? { ...item, enricher: { ...item.enricher, modelId: event.target.value } }
                                      : item
                                  )
                                )
                              }
                              placeholder="可选"
                            />
                          </div>
                          <div className="space-y-2">
                            <label className="text-sm font-medium">附加文档元数据</label>
                            <Select
                              value={node.enricher.attachDocumentMetadata ? "true" : "false"}
                              onValueChange={(value) =>
                                setNodes((prev) =>
                                  prev.map((item) =>
                                    item.id === node.id
                                      ? {
                                          ...item,
                                          enricher: {
                                            ...item.enricher,
                                            attachDocumentMetadata: value === "true"
                                          }
                                        }
                                      : item
                                  )
                                )
                              }
                            >
                              <SelectTrigger>
                                <SelectValue placeholder="选择" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="true">是</SelectItem>
                                <SelectItem value="false">否</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>
                        </div>
                        <div className="flex items-center justify-between">
                          <span className="text-sm font-medium">富集任务</span>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? {
                                        ...item,
                                        enricher: {
                                          ...item.enricher,
                                          tasks: [...item.enricher.tasks, createTask("keywords")]
                                        }
                                      }
                                    : item
                                )
                              )
                            }
                          >
                            <Plus className="mr-2 h-4 w-4" />
                            添加任务
                          </Button>
                        </div>
                        {node.enricher.tasks.length === 0 ? (
                          <div className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">
                            暂无任务
                          </div>
                        ) : (
                          <div className="space-y-3">
                            {node.enricher.tasks.map((task, taskIndex) => (
                              <div key={task.id} className="rounded-md border p-3 space-y-3">
                                <div className="flex items-center justify-between">
                                  <span className="text-xs text-muted-foreground">任务 {taskIndex + 1}</span>
                                  <Button
                                    type="button"
                                    size="sm"
                                    variant="ghost"
                                    className="text-destructive hover:text-destructive"
                                    onClick={() =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enricher: {
                                                  ...item.enricher,
                                                  tasks: item.enricher.tasks.filter((t) => t.id !== task.id)
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                  >
                                    删除
                                  </Button>
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">任务类型</label>
                                  <Select
                                    value={task.type}
                                    onValueChange={(value) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enricher: {
                                                  ...item.enricher,
                                                  tasks: item.enricher.tasks.map((t) =>
                                                    t.id === task.id ? { ...t, type: value } : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                  >
                                    <SelectTrigger>
                                      <SelectValue placeholder="选择类型" />
                                    </SelectTrigger>
                                    <SelectContent>
                                      {ENRICHER_TASK_OPTIONS.map((option) => (
                                        <SelectItem key={option.value} value={option.value}>
                                          {option.label}
                                        </SelectItem>
                                      ))}
                                    </SelectContent>
                                  </Select>
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">System Prompt</label>
                                  <Textarea
                                    rows={2}
                                    value={task.systemPrompt}
                                    onChange={(event) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enricher: {
                                                  ...item.enricher,
                                                  tasks: item.enricher.tasks.map((t) =>
                                                    t.id === task.id
                                                      ? { ...t, systemPrompt: event.target.value }
                                                      : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                    placeholder="可选"
                                  />
                                </div>
                                <div className="space-y-2">
                                  <label className="text-sm font-medium">User Prompt 模板</label>
                                  <Textarea
                                    rows={2}
                                    value={task.userPromptTemplate}
                                    onChange={(event) =>
                                      setNodes((prev) =>
                                        prev.map((item) =>
                                          item.id === node.id
                                            ? {
                                                ...item,
                                                enricher: {
                                                  ...item.enricher,
                                                  tasks: item.enricher.tasks.map((t) =>
                                                    t.id === task.id
                                                      ? { ...t, userPromptTemplate: event.target.value }
                                                      : t
                                                  )
                                                }
                                              }
                                            : item
                                        )
                                      )
                                    }
                                    placeholder="可选"
                                  />
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ) : null}

                    {node.nodeType === "indexer" ? (
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">Embedding 模型</label>
                          <Input
                            value={node.indexer.embeddingModel}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, indexer: { ...item.indexer, embeddingModel: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="可选"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">元数据字段</label>
                          <Input
                            value={node.indexer.metadataFields}
                            onChange={(event) =>
                              setNodes((prev) =>
                                prev.map((item) =>
                                  item.id === node.id
                                    ? { ...item, indexer: { ...item.indexer, metadataFields: event.target.value } }
                                    : item
                                )
                              )
                            }
                            placeholder="用逗号分隔，如 keywords,summary"
                          />
                        </div>
                      </div>
                    ) : null}

                    <div className="space-y-2">
                      <label className="text-sm font-medium">条件（JSON / SpEL，可选）</label>
                      <Textarea
                        rows={2}
                        value={node.condition}
                        onChange={(event) =>
                          setNodes((prev) =>
                            prev.map((item) =>
                              item.id === node.id ? { ...item, condition: event.target.value } : item
                            )
                          )
                        }
                        placeholder='{"field":"source_type","op":"eq","value":"file"} 或 #context.source.type == "file"'
                      />
                    </div>
                  </div>
                ))}

                <Button type="button" variant="outline" onClick={() => setNodes((prev) => [...prev, createNode()])}>
                  <Plus className="mr-2 h-4 w-4" />
                  添加节点
                </Button>
              </div>
            )}

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? "保存中..." : "保存"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

interface PipelineNodesDialogProps {
  open: boolean;
  pipeline: IngestionPipeline | null;
  onOpenChange: (open: boolean) => void;
}

function PipelineNodesDialog({ open, pipeline, onOpenChange }: PipelineNodesDialogProps) {
  const nodes = pipeline?.nodes || [];
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-h-[90vh] overflow-y-auto sm:max-w-[720px]"
        onOpenAutoFocus={(event) => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>流水线节点</DialogTitle>
          <DialogDescription>{pipeline?.name || ""}</DialogDescription>
        </DialogHeader>
        {nodes.length === 0 ? (
          <div className="py-6 text-center text-muted-foreground">暂无节点</div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>节点ID</TableHead>
                <TableHead>类型</TableHead>
                <TableHead>下一节点</TableHead>
                <TableHead>配置</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {nodes.map((node, index) => (
                <TableRow key={node.id || `${node.nodeId}-${index}`}>
                  <TableCell>{index + 1}</TableCell>
                  <TableCell className="font-mono text-xs">{node.nodeId}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{node.nodeType}</Badge>
                  </TableCell>
                  <TableCell>{node.nextNodeId || "-"}</TableCell>
                  <TableCell>
                    <pre className="max-w-[280px] whitespace-pre-wrap text-xs text-muted-foreground">
                      {truncateJson(node.settings || node.condition)}
                    </pre>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface TaskDialogProps {
  open: boolean;
  pipelineOptions: IngestionPipeline[];
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: IngestionTaskCreatePayload) => Promise<void>;
  onUpload: (pipelineId: string, file: File) => Promise<void>;
}

function TaskDialog({ open, pipelineOptions, onOpenChange, onSubmit, onUpload }: TaskDialogProps) {
  const [saving, setSaving] = useState(false);
  const [localFile, setLocalFile] = useState<File | null>(null);
  const form = useForm<TaskFormValues>({
    resolver: zodResolver(taskSchema),
    defaultValues: {
      pipelineId: pipelineOptions[0]?.id || "",
      sourceType: "file",
      location: "",
      fileName: "",
      credentialsJson: "",
      metadataJson: ""
    }
  });

  const sourceType = form.watch("sourceType");
  const isLocalFile = sourceType === "file";

  const sourceMeta = (() => {
    switch (sourceType) {
      case "file":
        return {
          locationPlaceholder: "/path/to/file 或 file://path/to/file",
          locationHint: "支持本地文件路径或 file:// 协议",
          credentialsHint: ""
        };
      case "feishu":
        return {
          locationPlaceholder: "https://open.feishu.cn/...",
          locationHint: "填写飞书文档链接",
          credentialsHint: '{"tenantAccessToken":"..."} 或 {"app_id":"...","app_secret":"..."}'
        };
      case "s3":
        return {
          locationPlaceholder: "s3://bucket/key",
          locationHint: "填写 S3 路径，例如 s3://biz/file.md",
          credentialsHint: ""
        };
      case "url":
      default:
        return {
          locationPlaceholder: "https://example.com/file.pdf",
          locationHint: "支持 http/https 链接",
          credentialsHint: '{"token":"xxx"} 或 {"Authorization":"Bearer xxx"}'
        };
    }
  })();

  const showCredentials = sourceType === "url" || sourceType === "feishu";

  useEffect(() => {
    if (open) {
      form.reset({
        pipelineId: pipelineOptions[0]?.id || "",
        sourceType: "file",
        location: "",
        fileName: "",
        credentialsJson: "",
        metadataJson: ""
      });
      setLocalFile(null);
    }
  }, [open, pipelineOptions, form]);

  useEffect(() => {
    if (!isLocalFile) {
      setLocalFile(null);
    }
  }, [isLocalFile]);

  const parseJsonField = (value?: string) => {
    if (!value || !value.trim()) return undefined;
    try {
      return JSON.parse(value);
    } catch {
      return null;
    }
  };

  const handleSubmit = async (values: TaskFormValues) => {
    if (values.sourceType === "file") {
      if (!localFile) {
        toast.error("请选择文件");
        return;
      }
      setSaving(true);
      try {
        await onUpload(values.pipelineId, localFile);
      } catch (error) {
        toast.error(getErrorMessage(error, "上传失败"));
        console.error(error);
      } finally {
        setSaving(false);
      }
      return;
    }

    const credentials = parseJsonField(values.credentialsJson);
    if (credentials === null) {
      form.setError("credentialsJson", { message: "凭证JSON格式错误" });
      return;
    }
    const metadata = parseJsonField(values.metadataJson);
    if (metadata === null) {
      form.setError("metadataJson", { message: "元数据JSON格式错误" });
      return;
    }

    setSaving(true);
    try {
      const location = values.location?.trim() || "";
      if (!location) {
        form.setError("location", { message: "请输入来源位置" });
        return;
      }
      const normalizedType = values.sourceType ? values.sourceType.toUpperCase() : values.sourceType;
      const payload: IngestionTaskCreatePayload = {
        pipelineId: values.pipelineId,
        source: {
          type: normalizedType,
          location,
          fileName: values.fileName?.trim() || undefined,
          credentials: credentials as Record<string, string> | undefined
        },
        metadata: metadata as Record<string, unknown> | undefined
      };
      await onSubmit(payload);
    } catch (error) {
      toast.error(getErrorMessage(error, "创建失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>新建通道任务</DialogTitle>
          <DialogDescription>支持 Local File / URL / Feishu / S3 来源，Local File 会直接上传文件</DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form className="space-y-4" onSubmit={form.handleSubmit(handleSubmit)}>
            <FormField
              control={form.control}
              name="pipelineId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>流水线</FormLabel>
                  <Select value={field.value} onValueChange={field.onChange}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="选择流水线" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {pipelineOptions.map((pipeline) => (
                        <SelectItem key={pipeline.id} value={pipeline.id}>
                          {pipeline.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid gap-4 md:grid-cols-2">
              <FormField
                control={form.control}
                name="sourceType"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>来源类型</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger>
                          <SelectValue placeholder="选择来源" />
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

              {isLocalFile ? (
                <FormItem>
                  <FormLabel>本地文件</FormLabel>
                  <FormControl>
                    <Input
                      type="file"
                      onChange={(event) => setLocalFile(event.target.files?.[0] || null)}
                    />
                  </FormControl>
                </FormItem>
              ) : (
                <FormField
                  control={form.control}
                  name="fileName"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>文件名（可选）</FormLabel>
                      <FormControl>
                        <Input placeholder="例如：doc.md" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              )}
            </div>

            {isLocalFile ? null : (
              <FormField
                control={form.control}
                name="location"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>来源位置</FormLabel>
                    <FormControl>
                      <Input placeholder={sourceMeta.locationPlaceholder} {...field} />
                    </FormControl>
                    <FormDescription>{sourceMeta.locationHint}</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            {showCredentials ? (
              <FormField
                control={form.control}
                name="credentialsJson"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>访问凭证（JSON，可选）</FormLabel>
                    <FormControl>
                      <Textarea placeholder={sourceMeta.credentialsHint || '{"token":"xxx"}'} rows={4} {...field} />
                    </FormControl>
                    {sourceMeta.credentialsHint ? (
                      <FormDescription>示例：{sourceMeta.credentialsHint}</FormDescription>
                    ) : null}
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : null}

            <FormField
              control={form.control}
              name="metadataJson"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>任务元数据（JSON，可选）</FormLabel>
                  <FormControl>
                    <Textarea placeholder='{"source":"manual"}' rows={4} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? "创建中..." : "创建任务"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

interface UploadDialogProps {
  open: boolean;
  pipelineOptions: IngestionPipeline[];
  onOpenChange: (open: boolean) => void;
  onSubmit: (pipelineId: string, file: File) => Promise<void>;
}

function UploadDialog({ open, pipelineOptions, onOpenChange, onSubmit }: UploadDialogProps) {
  const [pipelineId, setPipelineId] = useState(pipelineOptions[0]?.id || "");
  const [file, setFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) {
      setPipelineId(pipelineOptions[0]?.id || "");
      setFile(null);
    }
  }, [open, pipelineOptions]);

  const handleSubmit = async () => {
    if (!pipelineId) {
      toast.error("请选择流水线");
      return;
    }
    if (!file) {
      toast.error("请选择文件");
      return;
    }
    setSaving(true);
    try {
      await onSubmit(pipelineId, file);
    } catch (error) {
      toast.error(getErrorMessage(error, "上传失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle>上传文件并进入通道</DialogTitle>
          <DialogDescription>上传文件后立即触发通道任务</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium">流水线</label>
            <Select value={pipelineId} onValueChange={setPipelineId}>
              <SelectTrigger className="mt-2">
                <SelectValue placeholder="选择流水线" />
              </SelectTrigger>
              <SelectContent>
                {pipelineOptions.map((pipeline) => (
                  <SelectItem key={pipeline.id} value={pipeline.id}>
                    {pipeline.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div>
            <label className="text-sm font-medium">文件</label>
            <Input
              type="file"
              className="mt-2"
              onChange={(event) => setFile(event.target.files?.[0] || null)}
            />
          </div>
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

interface TaskDetailDialogProps {
  open: boolean;
  taskId: string | null;
  onOpenChange: (open: boolean) => void;
}

function TaskDetailDialog({ open, taskId, onOpenChange }: TaskDetailDialogProps) {
  const [task, setTask] = useState<IngestionTask | null>(null);
  const [nodes, setNodes] = useState<IngestionTaskNode[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open || !taskId) return;
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const [detail, nodeLogs] = await Promise.all([
          getIngestionTask(taskId),
          getIngestionTaskNodes(taskId)
        ]);
        if (!active) return;
        setTask(detail);
        setNodes(nodeLogs || []);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载任务详情失败"));
        console.error(error);
      } finally {
        if (active) setLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, [open, taskId]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[820px]">
        <DialogHeader>
          <DialogTitle>任务详情</DialogTitle>
          <DialogDescription>{taskId || ""}</DialogDescription>
        </DialogHeader>
        {loading || !task ? (
          <div className="py-6 text-center text-muted-foreground">加载中...</div>
        ) : (
          <div className="space-y-6">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <Badge variant={statusBadgeVariant(task.status)}>{task.status || "-"}</Badge>
                  {task.errorMessage ? (
                    <Badge variant="destructive">error</Badge>
                  ) : null}
                </div>
                <div className="text-sm text-muted-foreground">Pipeline: {task.pipelineId}</div>
                <div className="text-sm text-muted-foreground">
                  Source: {task.sourceType || "-"} {task.sourceFileName || task.sourceLocation || ""}
                </div>
                <div className="text-sm text-muted-foreground">Chunks: {task.chunkCount ?? "-"}</div>
              </div>
              <div className="space-y-2 text-sm text-muted-foreground">
                <div>Created: {formatDate(task.createTime)}</div>
                <div>Started: {formatDate(task.startedAt)}</div>
                <div>Completed: {formatDate(task.completedAt)}</div>
              </div>
            </div>

            {task.errorMessage ? (
              <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                {task.errorMessage}
              </div>
            ) : null}

            <div>
              <h3 className="text-sm font-medium">任务元数据</h3>
              <pre className="mt-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground">
                {stringifyJson(task.metadata)}
              </pre>
            </div>

            <div>
              <h3 className="text-sm font-medium">节点执行日志</h3>
              {nodes.length === 0 ? (
                <div className="mt-2 text-sm text-muted-foreground">暂无节点日志</div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>节点</TableHead>
                      <TableHead>类型</TableHead>
                      <TableHead>状态</TableHead>
                      <TableHead>耗时</TableHead>
                      <TableHead>消息</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {nodes.map((node) => (
                      <TableRow key={node.id}>
                        <TableCell className="font-mono text-xs">{node.nodeId}</TableCell>
                        <TableCell>{node.nodeType}</TableCell>
                        <TableCell>
                          <Badge variant={nodeStatusVariant(node.status)}>{node.status || "-"}</Badge>
                        </TableCell>
                        <TableCell>{node.durationMs ?? "-"} ms</TableCell>
                        <TableCell>
                          <div className="text-xs text-muted-foreground">
                            {node.message || node.errorMessage || "-"}
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
