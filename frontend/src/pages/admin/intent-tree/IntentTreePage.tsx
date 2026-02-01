import { useEffect, useMemo, useState } from "react";
import { ChevronDown, ChevronRight, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
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
import { cn } from "@/lib/utils";
import type { KnowledgeBase } from "@/services/knowledgeService";
import { getKnowledgeBases } from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";
import type {
  IntentNodeCreatePayload,
  IntentNodeTree,
  IntentNodeUpdatePayload
} from "@/services/intentTreeService";
import {
  createIntentNode,
  deleteIntentNode,
  getIntentTree,
  updateIntentNode
} from "@/services/intentTreeService";

const ROOT_PARENT = "__ROOT__";

const LEVEL_OPTIONS = [
  { value: 0, label: "DOMAIN", description: "顶层领域" },
  { value: 1, label: "CATEGORY", description: "业务分类" },
  { value: 2, label: "TOPIC", description: "具体主题" }
];

const KIND_OPTIONS = [
  { value: 0, label: "KB", description: "知识库检索" },
  { value: 1, label: "SYSTEM", description: "系统交互" },
  { value: 2, label: "MCP", description: "工具调用" }
];

const formSchema = z.object({
  name: z.string().min(1, "请输入节点名称").max(50, "名称不能超过50个字符"),
  intentCode: z
      .string()
      .min(1, "请输入意图标识")
      .max(80, "意图标识过长")
      .regex(/^[a-zA-Z0-9_-]+$/, "仅支持字母、数字、-和_"),
  level: z.number(),
  kind: z.number(),
  parentCode: z.string().optional(),
  kbId: z.string().optional(),
  mcpToolId: z.string().optional(),
  collectionName: z.string().optional(),
  description: z.string().optional(),
  examplesText: z.string().optional(),
  sortOrder: z.number().int().optional(),
  enabled: z.boolean(),
  promptSnippet: z.string().optional(),
  promptTemplate: z.string().optional(),
  paramPromptTemplate: z.string().optional()
});

type FormValues = z.infer<typeof formSchema>;

type TreeOption = {
  label: string;
  value: string;
  node: IntentNodeTree | null;
  depth: number;
};

const parseExamples = (value?: string | null) => {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) {
      return parsed.map((item) => String(item)).filter(Boolean);
    }
  } catch {
    // Ignore parse errors and fall back to plain text parsing.
  }
  return value
      .split("\n")
      .map((item) => item.trim())
      .filter(Boolean);
};

const buildTreeOptions = (
    nodes: IntentNodeTree[],
    prefix = "",
    depth = 0,
    result: TreeOption[] = []
) => {
  nodes.forEach((node) => {
    const label = prefix ? `${prefix} > ${node.name}` : node.name;
    result.push({ label, value: node.intentCode, node, depth });
    if (node.children && node.children.length > 0) {
      buildTreeOptions(node.children, label, depth + 1, result);
    }
  });
  return result;
};

const findNodeByCode = (nodes: IntentNodeTree[], code: string | null): IntentNodeTree | null => {
  if (!code) return null;
  for (const node of nodes) {
    if (node.intentCode === code) {
      return node;
    }
    if (node.children && node.children.length > 0) {
      const found = findNodeByCode(node.children, code);
      if (found) return found;
    }
  }
  return null;
};

const resolveLevelLabel = (value?: number | null) =>
    LEVEL_OPTIONS.find((option) => option.value === (value ?? 0))?.label ?? "UNKNOWN";

const resolveKindLabel = (value?: number | null) =>
    KIND_OPTIONS.find((option) => option.value === (value ?? 0))?.label ?? "UNKNOWN";

const resolveKindBadge = (value?: number | null) => {
  const label = resolveKindLabel(value);
  if (label === "MCP") return "default";
  if (label === "SYSTEM") return "secondary";
  return "outline";
};

export function IntentTreePage() {
  const [tree, setTree] = useState<IntentNodeTree[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [expandedMap, setExpandedMap] = useState<Record<string, boolean>>({});
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogMode, setDialogMode] = useState<"create" | "edit">("create");
  const [dialogParent, setDialogParent] = useState<IntentNodeTree | null>(null);
  const [editingNode, setEditingNode] = useState<IntentNodeTree | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<IntentNodeTree | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  const selectedNode = useMemo(() => findNodeByCode(tree, selectedCode), [tree, selectedCode]);
  const treeOptions = useMemo(() => buildTreeOptions(tree), [tree]);

  const loadTree = async () => {
    setLoading(true);
    try {
      const data = await getIntentTree();
      setTree(data || []);
      setSelectedCode((prev) => {
        if (prev && findNodeByCode(data || [], prev)) {
          return prev;
        }
        return data?.[0]?.intentCode ?? null;
      });
    } catch (error) {
      toast.error(getErrorMessage(error, "加载意图树失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const loadKnowledgeBases = async () => {
    try {
      const data = await getKnowledgeBases();
      setKnowledgeBases(data);
    } catch (error) {
      console.error(error);
    }
  };

  useEffect(() => {
    loadTree();
    loadKnowledgeBases();
  }, []);

  const handleRefresh = () => {
    loadTree();
  };

  const openCreateDialog = (parent?: IntentNodeTree | null) => {
    setDialogMode("create");
    setDialogParent(parent ?? null);
    setEditingNode(null);
    setDialogOpen(true);
  };

  const openEditDialog = (node: IntentNodeTree) => {
    setDialogMode("edit");
    setEditingNode(node);
    setDialogParent(null);
    setDialogOpen(true);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteIntentNode(deleteTarget.id);
      toast.success("删除成功");
      await loadTree();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const handleCreate = async (payload: IntentNodeCreatePayload) => {
    await createIntentNode(payload);
    toast.success("创建成功");
    await loadTree();
  };

  const handleUpdate = async (id: number, payload: IntentNodeUpdatePayload) => {
    await updateIntentNode(id, payload);
    toast.success("更新成功");
    await loadTree();
  };

  const renderNode = (node: IntentNodeTree, depth = 0) => {
    const hasChildren = Boolean(node.children && node.children.length > 0);
    const isExpanded = expandedMap[node.intentCode] ?? true;
    const isSelected = selectedCode === node.intentCode;
    return (
        <div key={node.intentCode}>
          <div
              className={cn(
                  "group flex cursor-pointer items-center justify-between rounded-xl px-3 py-2 transition-colors",
                  isSelected ? "bg-slate-100 text-slate-900" : "hover:bg-slate-50"
              )}
              style={{ paddingLeft: `${depth * 16 + 12}px` }}
              onClick={() => setSelectedCode(node.intentCode)}
          >
            <div className="flex items-center gap-2">
              {hasChildren ? (
                  <button
                      type="button"
                      className="flex h-5 w-5 items-center justify-center rounded-md text-muted-foreground hover:text-foreground"
                      onClick={(event) => {
                        event.stopPropagation();
                        setExpandedMap((prev) => ({
                          ...prev,
                          [node.intentCode]: !isExpanded
                        }));
                      }}
                      aria-label={isExpanded ? "收起节点" : "展开节点"}
                  >
                    {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                  </button>
              ) : (
                  <span className="h-5 w-5" />
              )}
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium text-foreground">{node.name}</span>
                <Badge variant="outline">{resolveLevelLabel(node.level)}</Badge>
                <Badge variant={resolveKindBadge(node.kind)}>{resolveKindLabel(node.kind)}</Badge>
              </div>
            </div>
            <div className="hidden items-center gap-2 text-xs text-muted-foreground group-hover:flex">
              <span className="truncate">{node.intentCode}</span>
            </div>
          </div>
          {hasChildren && isExpanded ? node.children?.map((child) => renderNode(child, depth + 1)) : null}
        </div>
    );
  };

  return (
      <div className="admin-page">
        <div className="admin-page-header">
          <div>
            <h1 className="admin-page-title">意图树配置</h1>
            <p className="admin-page-subtitle">配置意图层级、类型和节点关系</p>
          </div>
          <div className="admin-page-actions">
            <Button variant="outline" onClick={handleRefresh}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            <Button className="admin-primary-gradient" onClick={() => openCreateDialog(null)}>
              <Plus className="mr-2 h-4 w-4" />
              新建根节点
            </Button>
          </div>
        </div>

        <div className="grid gap-6 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
          <Card>
            <CardHeader>
              <CardTitle>意图树结构</CardTitle>
              <CardDescription>点击节点查看详情或进行编辑</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              {loading ? (
                  <div className="py-10 text-center text-muted-foreground">加载中...</div>
              ) : tree.length === 0 ? (
                  <div className="py-10 text-center text-muted-foreground">暂无节点，请先创建</div>
              ) : (
                  tree.map((node) => renderNode(node))
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>节点详情</CardTitle>
              <CardDescription>查看并管理当前选择的节点</CardDescription>
            </CardHeader>
            <CardContent>
              {!selectedNode ? (
                  <div className="py-10 text-center text-muted-foreground">请选择左侧节点</div>
              ) : (
                  <div className="space-y-4">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <div className="flex items-center gap-2">
                          <h2 className="text-lg font-semibold text-foreground">{selectedNode.name}</h2>
                          <Badge variant="outline">{resolveLevelLabel(selectedNode.level)}</Badge>
                          <Badge variant={resolveKindBadge(selectedNode.kind)}>
                            {resolveKindLabel(selectedNode.kind)}
                          </Badge>
                          <Badge variant={selectedNode.enabled === 0 ? "secondary" : "default"}>
                            {selectedNode.enabled === 0 ? "停用" : "启用"}
                          </Badge>
                        </div>
                        <p className="mt-1 text-xs text-muted-foreground">{selectedNode.intentCode}</p>
                      </div>
                      <div className="flex flex-col gap-2">
                        <Button size="sm" onClick={() => openCreateDialog(selectedNode)}>
                          <Plus className="mr-2 h-4 w-4" />
                          新建子节点
                        </Button>
                        <Button size="sm" variant="outline" onClick={() => openEditDialog(selectedNode)}>
                          <Pencil className="mr-2 h-4 w-4" />
                          编辑节点
                        </Button>
                        <Button
                            size="sm"
                            variant="ghost"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setDeleteTarget(selectedNode)}
                        >
                          <Trash2 className="mr-2 h-4 w-4" />
                          删除节点
                        </Button>
                      </div>
                    </div>

                    <div className="space-y-2 text-sm">
                      <div className="flex items-center justify-between">
                        <span className="text-muted-foreground">父节点</span>
                        <span>{selectedNode.parentCode || "ROOT"}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span className="text-muted-foreground">排序</span>
                        <span>{selectedNode.sortOrder ?? 0}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span className="text-muted-foreground">Collection</span>
                        <span>{selectedNode.collectionName || "-"}</span>
                      </div>
                    </div>

                    <div>
                      <p className="text-sm font-medium">描述</p>
                      <p className="mt-1 text-sm text-muted-foreground">
                        {selectedNode.description || "暂无描述"}
                      </p>
                    </div>

                    <div>
                      <p className="text-sm font-medium">示例问题</p>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {(() => {
                          const examples = parseExamples(selectedNode.examples);
                          if (examples.length === 0) {
                            return <span className="text-sm text-muted-foreground">暂无示例</span>;
                          }
                          return examples.map((item) => (
                              <Badge key={item} variant="secondary">
                                {item}
                              </Badge>
                          ));
                        })()}
                      </div>
                    </div>
                  </div>
              )}
            </CardContent>
          </Card>
        </div>

        <IntentNodeDialog
            open={dialogOpen}
            mode={dialogMode}
            parentNode={dialogParent}
            node={editingNode}
            knowledgeBases={knowledgeBases}
            treeOptions={treeOptions}
            onOpenChange={setDialogOpen}
            onCreate={handleCreate}
            onUpdate={handleUpdate}
        />

        <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => (!open ? setDeleteTarget(null) : null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>确认删除节点？</AlertDialogTitle>
              <AlertDialogDescription>
                节点 [{deleteTarget?.name}] 将被永久删除，无法恢复。
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
      </div>
  );
}

interface IntentNodeDialogProps {
  open: boolean;
  mode: "create" | "edit";
  parentNode: IntentNodeTree | null;
  node: IntentNodeTree | null;
  treeOptions: TreeOption[];
  knowledgeBases: KnowledgeBase[];
  onOpenChange: (open: boolean) => void;
  onCreate: (payload: IntentNodeCreatePayload) => Promise<void>;
  onUpdate: (id: number, payload: IntentNodeUpdatePayload) => Promise<void>;
}

function IntentNodeDialog({
                            open,
                            mode,
                            parentNode,
                            node,
                            treeOptions,
                            knowledgeBases,
                            onOpenChange,
                            onCreate,
                            onUpdate
                          }: IntentNodeDialogProps) {
  const [saving, setSaving] = useState(false);

  const resolvedDefaults = useMemo<FormValues>(() => {
    if (mode === "edit" && node) {
      return {
        name: node.name || "",
        intentCode: node.intentCode || "",
        level: node.level ?? 0,
        kind: node.kind ?? 0,
        parentCode: node.parentCode || ROOT_PARENT,
        kbId: "",
        mcpToolId: node.mcpToolId || "",
        collectionName: node.collectionName || "",
        description: node.description || "",
        examplesText: parseExamples(node.examples).join("\n"),
        sortOrder: node.sortOrder ?? 0,
        enabled: node.enabled !== 0,
        promptSnippet: node.promptSnippet || "",
        promptTemplate: node.promptTemplate || "",
        paramPromptTemplate: node.paramPromptTemplate || ""
      };
    }

    const nextLevel = parentNode ? Math.min((parentNode.level ?? 0) + 1, 2) : 0;
    const parentKind = parentNode?.kind ?? 0;
    const kbMatch = knowledgeBases.find((kb) => kb.collectionName === parentNode?.collectionName);

    return {
      name: "",
      intentCode: "",
      level: nextLevel,
      kind: parentKind,
      parentCode: parentNode?.intentCode || ROOT_PARENT,
      kbId: kbMatch?.id || knowledgeBases[0]?.id || "",
      mcpToolId: "",
      collectionName: "",
      description: "",
      examplesText: "",
      sortOrder: 0,
      enabled: true,
      promptSnippet: "",
      promptTemplate: "",
      paramPromptTemplate: ""
    };
  }, [mode, node, parentNode, knowledgeBases]);

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: resolvedDefaults
  });

  useEffect(() => {
    if (open) {
      form.reset(resolvedDefaults);
    }
  }, [open, resolvedDefaults, form]);

  const parentOptions = useMemo(() => {
    const filtered =
        mode === "edit" && node ? treeOptions.filter((item) => item.value !== node.intentCode) : treeOptions;
    return [{ label: "ROOT", value: ROOT_PARENT, node: null, depth: 0 }].concat(filtered);
  }, [mode, node, treeOptions]);

  const handleSubmit = async (values: FormValues) => {
    const parentCode = values.parentCode === ROOT_PARENT ? null : values.parentCode || null;
    const examples = values.examplesText
        ? values.examplesText
            .split("\n")
            .map((item) => item.trim())
            .filter(Boolean)
        : [];

    if (mode === "create") {
      if (values.kind === 0 && !values.kbId) {
        form.setError("kbId", { message: "请选择知识库" });
        return;
      }
      if (values.kind === 2 && !values.mcpToolId?.trim()) {
        form.setError("mcpToolId", { message: "请输入MCP工具ID" });
        return;
      }
    } else {
      // 编辑模式下也需要验证MCP工具ID
      if (values.kind === 2 && !values.mcpToolId?.trim()) {
        form.setError("mcpToolId", { message: "MCP节点必须填写工具ID" });
        return;
      }
    }

    setSaving(true);
    try {
      if (mode === "create") {
        const payload: IntentNodeCreatePayload = {
          kbId: values.kind === 0 ? values.kbId : undefined,
          intentCode: values.intentCode.trim(),
          name: values.name.trim(),
          level: values.level,
          parentCode,
          description: values.description?.trim() || undefined,
          examples: examples.length > 0 ? examples : undefined,
          kind: values.kind,
          sortOrder: values.sortOrder ?? 0,
          enabled: values.enabled ? 1 : 0,
          mcpToolId: values.kind === 2 ? values.mcpToolId?.trim() || undefined : undefined,
          promptSnippet: values.promptSnippet?.trim() || undefined,
          promptTemplate: values.promptTemplate?.trim() || undefined,
          paramPromptTemplate: values.kind === 2 ? values.paramPromptTemplate?.trim() || undefined : undefined
        };
        await onCreate(payload);
      } else if (node) {
        const payload: IntentNodeUpdatePayload = {
          name: values.name.trim(),
          level: values.level,
          parentCode,
          description: values.description?.trim() || undefined,
          examples: examples.length > 0 ? examples : undefined,
          collectionName: values.kind === 0 ? values.collectionName?.trim() || undefined : undefined,
          mcpToolId: values.kind === 2 ? values.mcpToolId?.trim() || undefined : undefined,
          kind: values.kind,
          sortOrder: values.sortOrder ?? 0,
          enabled: values.enabled ? 1 : 0,
          promptSnippet: values.promptSnippet?.trim() || undefined,
          promptTemplate: values.promptTemplate?.trim() || undefined,
          paramPromptTemplate: values.kind === 2 ? values.paramPromptTemplate?.trim() || undefined : undefined
        };
        await onUpdate(node.id, payload);
      }
      onOpenChange(false);
    } catch (error) {
      console.error(error);
      toast.error(getErrorMessage(error, mode === "create" ? "创建失败" : "更新失败"));
    } finally {
      setSaving(false);
    }
  };

  const kind = form.watch("kind");

  return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sidebar-scroll sm:max-w-[640px]">
          <DialogHeader>
            <DialogTitle>{mode === "create" ? "新建意图节点" : "编辑意图节点"}</DialogTitle>
            <DialogDescription>
              {mode === "create" ? "配置意图节点的层级、类型与描述信息" : "更新节点基础信息"}
            </DialogDescription>
          </DialogHeader>

          <Form {...form}>
            <form className="space-y-4" onSubmit={form.handleSubmit(handleSubmit)}>
              <div className="grid gap-4 md:grid-cols-2">
                <FormField
                    control={form.control}
                    name="name"
                    render={({ field }) => (
                        <FormItem>
                          <FormLabel>节点名称</FormLabel>
                          <FormControl>
                            <Input placeholder="例如：OA系统" {...field} />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                    )}
                />

                <FormField
                    control={form.control}
                    name="intentCode"
                    render={({ field }) => (
                        <FormItem>
                          <FormLabel>意图标识</FormLabel>
                          <FormControl>
                            <Input placeholder="例如：biz-oa" {...field} disabled={mode === "edit"} />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                    )}
                />
              </div>

              <div className="grid gap-4 md:grid-cols-2">
                <FormField
                    control={form.control}
                    name="level"
                    render={({ field }) => (
                        <FormItem>
                          <FormLabel>层级</FormLabel>
                          <Select
                              value={String(field.value)}
                              onValueChange={(value) => field.onChange(Number(value))}
                          >
                            <FormControl>
                              <SelectTrigger>
                                <SelectValue placeholder="选择层级" />
                              </SelectTrigger>
                            </FormControl>
                            <SelectContent>
                              {LEVEL_OPTIONS.map((option) => (
                                  <SelectItem key={option.value} value={String(option.value)}>
                                    {option.label} - {option.description}
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
                    name="kind"
                    render={({ field }) => (
                        <FormItem>
                          <FormLabel>类型</FormLabel>
                          <Select
                              value={String(field.value)}
                              onValueChange={(value) => field.onChange(Number(value))}
                          >
                            <FormControl>
                              <SelectTrigger>
                                <SelectValue placeholder="选择类型" />
                              </SelectTrigger>
                            </FormControl>
                            <SelectContent>
                              {KIND_OPTIONS.map((option) => (
                                  <SelectItem key={option.value} value={String(option.value)}>
                                    {option.label} - {option.description}
                                  </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <FormMessage />
                        </FormItem>
                    )}
                />
              </div>

              <FormField
                  control={form.control}
                  name="parentCode"
                  render={({ field }) => (
                      <FormItem>
                        <FormLabel>父节点</FormLabel>
                        <Select value={field.value || ROOT_PARENT} onValueChange={field.onChange}>
                          <FormControl>
                            <SelectTrigger>
                              <SelectValue placeholder="选择父节点" />
                            </SelectTrigger>
                          </FormControl>
                          <SelectContent>
                            {parentOptions.map((option) => (
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

              {mode === "create" && kind === 0 && (
                  <FormField
                      control={form.control}
                      name="kbId"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>知识库</FormLabel>
                            <Select value={field.value} onValueChange={field.onChange}>
                              <FormControl>
                                <SelectTrigger>
                                  <SelectValue placeholder="选择知识库" />
                                </SelectTrigger>
                              </FormControl>
                              <SelectContent>
                                {knowledgeBases.map((kb) => (
                                    <SelectItem key={kb.id} value={kb.id}>
                                      {kb.name} ({kb.collectionName})
                                    </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                            <FormMessage />
                          </FormItem>
                      )}
                  />
              )}

              {mode === "edit" && kind === 0 && (
                  <FormField
                      control={form.control}
                      name="collectionName"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>Collection 名称</FormLabel>
                            <FormControl>
                              <Input placeholder="Milvus Collection 名称" {...field} />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />
              )}

              {kind === 2 && (
                  <FormField
                      control={form.control}
                      name="mcpToolId"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>MCP 工具ID（必填）</FormLabel>
                            <FormControl>
                              <Input placeholder="例如：sales_query" {...field} />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />
              )}

              <details className="rounded-lg border px-4 py-3" open>
                <summary className="cursor-pointer text-sm font-medium text-foreground">
                  描述与示例
                </summary>
                <div className="mt-3 space-y-4">
                  <FormField
                      control={form.control}
                      name="description"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>描述</FormLabel>
                            <FormControl>
                              <Textarea placeholder="节点的语义说明与说明场景" {...field} />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />

                  <FormField
                      control={form.control}
                      name="examplesText"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>示例问题</FormLabel>
                            <FormControl>
                              <Textarea placeholder="每行一个示例问题" {...field} />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />
                </div>
              </details>

              <details className="rounded-lg border px-4 py-3">
                <summary className="cursor-pointer text-sm font-medium text-foreground">
                  Prompt 配置
                </summary>
                <div className="mt-3 space-y-4">
                  <FormField
                      control={form.control}
                      name="promptSnippet"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>短规则片段（可选）</FormLabel>
                            <FormControl>
                              <Textarea
                                  rows={3}
                                  placeholder="多意图场景下的特定规则，会添加到整体提示词中"
                                  {...field}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />

                  <FormField
                      control={form.control}
                      name="promptTemplate"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>Prompt模板（可选）</FormLabel>
                            <FormControl>
                              <Textarea
                                  rows={4}
                                  placeholder="场景用的完整Prompt模板，KB和MCP节点都可配置"
                                  {...field}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />

                  {kind === 2 && (
                      <FormField
                          control={form.control}
                          name="paramPromptTemplate"
                          render={({ field }) => (
                              <FormItem>
                                <FormLabel>参数提取提示词模板（MCP专属）</FormLabel>
                                <FormControl>
                                  <Textarea
                                      rows={4}
                                      placeholder="用于从用户输入中提取MCP工具参数的提示词模板"
                                      {...field}
                                  />
                                </FormControl>
                                <FormMessage />
                              </FormItem>
                          )}
                      />
                  )}
                </div>
              </details>

              <details className="rounded-lg border px-4 py-3">
                <summary className="cursor-pointer text-sm font-medium text-foreground">
                  高级设置
                </summary>
                <div className="mt-3 grid gap-4 md:grid-cols-2">
                  <FormField
                      control={form.control}
                      name="sortOrder"
                      render={({ field }) => (
                          <FormItem>
                            <FormLabel>排序</FormLabel>
                            <FormControl>
                              <Input
                                  type="number"
                                  value={field.value ?? ""}
                                  onChange={(event) => {
                                    const nextValue = event.target.value;
                                    field.onChange(nextValue === "" ? undefined : Number(nextValue));
                                  }}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                      )}
                  />

                  <FormField
                      control={form.control}
                      name="enabled"
                      render={({ field }) => (
                          <FormItem className="flex flex-col justify-end">
                            <div className="flex items-center gap-2">
                              <FormControl>
                                <Checkbox
                                    checked={field.value}
                                    onCheckedChange={(value) => field.onChange(Boolean(value))}
                                />
                              </FormControl>
                              <FormLabel>启用节点</FormLabel>
                            </div>
                            <FormMessage />
                          </FormItem>
                      )}
                  />
                </div>
              </details>

              <DialogFooter>
                <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
                  取消
                </Button>
                <Button type="submit" disabled={saving}>
                  {saving ? (mode === "create" ? "创建中..." : "保存中...") : mode === "create" ? "创建" : "保存"}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
  );
}
