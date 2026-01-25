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
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import type {
  IngestionPipeline,
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

const PIPELINE_PAGE_SIZE = 10;
const TASK_PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "completed", label: "completed" },
  { value: "failed", label: "failed" }
];

const SOURCE_OPTIONS = [
  { value: "url", label: "URL" },
  { value: "feishu", label: "Feishu" },
  { value: "s3", label: "S3" }
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

const taskSchema = z.object({
  pipelineId: z.string().min(1, "请选择流水线"),
  sourceType: z.string().min(1, "请选择来源类型"),
  location: z.string().min(1, "请输入来源位置"),
  fileName: z.string().optional(),
  credentialsJson: z.string().optional(),
  metadataJson: z.string().optional()
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
      toast.error("加载流水线失败");
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
      toast.error("加载任务失败");
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
      toast.error("删除失败");
      console.error(error);
    }
  };

  const openPipelineNodes = async (pipeline: IngestionPipeline) => {
    try {
      const detail = await getIngestionPipeline(pipeline.id);
      setPipelineNodesDialog({ open: true, pipeline: detail });
    } catch (error) {
      toast.error("获取流水线详情失败");
      console.error(error);
    }
  };

  const taskStatusLabel = (status?: string | null) =>
    status ? status.toLowerCase() : "unknown";

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">数据摄入</h1>
          <p className="text-sm text-muted-foreground">管理采集流水线与任务执行情况</p>
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
                <CardTitle>采集流水线</CardTitle>
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
                <CardTitle>采集任务</CardTitle>
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
  const defaultNodes = pipeline?.nodes?.length ? JSON.stringify(pipeline.nodes, null, 2) : "";

  const form = useForm<PipelineFormValues>({
    resolver: zodResolver(pipelineSchema),
    defaultValues: {
      name: pipeline?.name || "",
      description: pipeline?.description || "",
      nodesJson: defaultNodes
    }
  });

  useEffect(() => {
    if (open) {
      form.reset({
        name: pipeline?.name || "",
        description: pipeline?.description || "",
        nodesJson: defaultNodes
      });
    }
  }, [open, pipeline, defaultNodes, form]);

  const handleSubmit = async (values: PipelineFormValues) => {
    let nodes: IngestionPipelinePayload["nodes"] | undefined;
    if (values.nodesJson && values.nodesJson.trim()) {
      try {
        const parsed = JSON.parse(values.nodesJson);
        if (!Array.isArray(parsed)) {
          form.setError("nodesJson", { message: "节点配置必须是JSON数组" });
          return;
        }
        nodes = parsed.map((item) => ({
          nodeId: String(item.nodeId || "").trim(),
          nodeType: String(item.nodeType || "").trim(),
          settings: item.settings ?? null,
          condition: item.condition ?? null,
          nextNodeId: item.nextNodeId ? String(item.nextNodeId) : null
        }));
        const invalid = nodes.some((node) => !node.nodeId || !node.nodeType);
        if (invalid) {
          form.setError("nodesJson", { message: "每个节点必须包含 nodeId 与 nodeType" });
          return;
        }
      } catch (error) {
        form.setError("nodesJson", { message: "节点配置JSON格式错误" });
        return;
      }
    }

    setSaving(true);
    try {
      const payload: IngestionPipelinePayload = {
        name: values.name.trim(),
        description: values.description?.trim() || undefined,
        nodes
      };
      await onSubmit(payload, mode);
    } catch (error) {
      toast.error(mode === "create" ? "创建失败" : "更新失败");
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[720px]">
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
                    <Input placeholder="例如：通用文档摄入" {...field} />
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

            <FormField
              control={form.control}
              name="nodesJson"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>节点配置（JSON 数组）</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder='[{"nodeId":"fetch","nodeType":"fetcher","settings":{},"nextNodeId":"parse"}]'
                      rows={8}
                      {...field}
                    />
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
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[720px]">
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
}

function TaskDialog({ open, pipelineOptions, onOpenChange, onSubmit }: TaskDialogProps) {
  const [saving, setSaving] = useState(false);
  const form = useForm<TaskFormValues>({
    resolver: zodResolver(taskSchema),
    defaultValues: {
      pipelineId: pipelineOptions[0]?.id || "",
      sourceType: "url",
      location: "",
      fileName: "",
      credentialsJson: "",
      metadataJson: ""
    }
  });

  useEffect(() => {
    if (open) {
      form.reset({
        pipelineId: pipelineOptions[0]?.id || "",
        sourceType: "url",
        location: "",
        fileName: "",
        credentialsJson: "",
        metadataJson: ""
      });
    }
  }, [open, pipelineOptions, form]);

  const parseJsonField = (value?: string) => {
    if (!value || !value.trim()) return undefined;
    try {
      return JSON.parse(value);
    } catch {
      return null;
    }
  };

  const handleSubmit = async (values: TaskFormValues) => {
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
      const payload: IngestionTaskCreatePayload = {
        pipelineId: values.pipelineId,
        source: {
          type: values.sourceType,
          location: values.location.trim(),
          fileName: values.fileName?.trim() || undefined,
          credentials: credentials as Record<string, string> | undefined
        },
        metadata: metadata as Record<string, unknown> | undefined
      };
      await onSubmit(payload);
    } catch (error) {
      toast.error("创建失败");
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>新建采集任务</DialogTitle>
          <DialogDescription>支持 URL / Feishu / S3 来源，文件上传请使用上传任务</DialogDescription>
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
            </div>

            <FormField
              control={form.control}
              name="location"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>来源位置</FormLabel>
                  <FormControl>
                    <Input placeholder="URL / 文档ID / 对象路径" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="credentialsJson"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>访问凭证（JSON，可选）</FormLabel>
                  <FormControl>
                    <Textarea placeholder='{"token":"xxx"}' rows={4} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

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
          <DialogTitle>上传文件并采集</DialogTitle>
          <DialogDescription>上传文件后立即触发摄取任务</DialogDescription>
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
        toast.error("加载任务详情失败");
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
                <div>Started: {formatDate(task.startedAt)}</div>
                <div>Completed: {formatDate(task.completedAt)}</div>
                <div>Created: {formatDate(task.createTime)}</div>
                <div>Updated: {formatDate(task.updateTime)}</div>
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
