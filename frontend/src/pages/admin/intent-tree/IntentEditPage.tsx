import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
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
  getIntentTree,
  updateIntentNode,
  type IntentNodeTree,
  type IntentNodeUpdatePayload
} from "@/services/intentTreeService";
import { getErrorMessage } from "@/utils/error";

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
  intentCode: z.string().min(1, "意图标识不能为空"),
  level: z.number(),
  kind: z.number(),
  parentCode: z.string().optional(),
  collectionName: z.string().optional(),
  mcpToolId: z.string().optional(),
  description: z.string().optional(),
  examplesText: z.string().optional(),
  topK: z.number().int().positive("TopK 必须大于 0").optional(),
  sortOrder: z.number().int().optional(),
  enabled: z.boolean(),
  promptSnippet: z.string().optional(),
  promptTemplate: z.string().optional(),
  paramPromptTemplate: z.string().optional()
});

type FormValues = z.infer<typeof formSchema>;

type FlatIntentNode = {
  id: number;
  intentCode: string;
  name: string;
  level: number;
  kind: number;
  parentCode?: string | null;
  description?: string | null;
  examples?: string | null;
  collectionName?: string | null;
  mcpToolId?: string | null;
  topK?: number | null;
  enabled: number;
  sortOrder: number;
  promptSnippet?: string | null;
  promptTemplate?: string | null;
  paramPromptTemplate?: string | null;
  pathText: string;
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

const flattenIntentTree = (
  nodes: IntentNodeTree[],
  parentPath: string[] = []
): FlatIntentNode[] => {
  const result: FlatIntentNode[] = [];
  nodes.forEach((node) => {
    const currentPath = [...parentPath, node.name];
    const children = node.children || [];
    result.push({
      id: node.id,
      intentCode: node.intentCode,
      name: node.name,
      level: node.level ?? 0,
      kind: node.kind ?? 0,
      parentCode: node.parentCode,
      description: node.description,
      examples: node.examples,
      collectionName: node.collectionName,
      mcpToolId: node.mcpToolId,
      topK: node.topK,
      enabled: node.enabled === 0 ? 0 : 1,
      sortOrder: node.sortOrder ?? 0,
      promptSnippet: node.promptSnippet,
      promptTemplate: node.promptTemplate,
      paramPromptTemplate: node.paramPromptTemplate,
      pathText: currentPath.join(" > ")
    });
    result.push(...flattenIntentTree(children, currentPath));
  });
  return result;
};

const emptyDefaults: FormValues = {
  name: "",
  intentCode: "",
  level: 0,
  kind: 0,
  parentCode: ROOT_PARENT,
  collectionName: "",
  mcpToolId: "",
  description: "",
  examplesText: "",
  topK: undefined,
  sortOrder: 0,
  enabled: true,
  promptSnippet: "",
  promptTemplate: "",
  paramPromptTemplate: ""
};

export function IntentEditPage() {
  const navigate = useNavigate();
  const { id: routeId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const [tree, setTree] = useState<IntentNodeTree[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const returnTo = useMemo(() => {
    const from = searchParams.get("from") || "";
    if (from.startsWith("/admin/")) {
      return from;
    }
    return "/admin/intent-list";
  }, [searchParams]);

  const rows = useMemo(() => flattenIntentTree(tree), [tree]);

  const currentNode = useMemo(() => {
    if (!routeId) return null;
    return rows.find((row) => String(row.id) === routeId) || null;
  }, [rows, routeId]);

  const excludedCodes = useMemo(() => {
    if (!currentNode) return new Set<string>();
    const childrenMap = new Map<string, string[]>();
    rows.forEach((row) => {
      if (!row.parentCode) return;
      const next = childrenMap.get(row.parentCode) || [];
      next.push(row.intentCode);
      childrenMap.set(row.parentCode, next);
    });

    const excluded = new Set<string>([currentNode.intentCode]);
    const stack = [currentNode.intentCode];
    while (stack.length > 0) {
      const code = stack.pop();
      if (!code) continue;
      const children = childrenMap.get(code) || [];
      children.forEach((childCode) => {
        if (!excluded.has(childCode)) {
          excluded.add(childCode);
          stack.push(childCode);
        }
      });
    }
    return excluded;
  }, [rows, currentNode]);

  const parentOptions = useMemo(() => {
    return [
      { value: ROOT_PARENT, label: "ROOT" },
      ...rows
        .filter((row) => !excludedCodes.has(row.intentCode))
        .map((row) => ({
          value: row.intentCode,
          label: row.pathText
        }))
    ];
  }, [rows, excludedCodes]);

  const resolvedDefaults = useMemo<FormValues>(() => {
    if (!currentNode) return emptyDefaults;
    return {
      name: currentNode.name || "",
      intentCode: currentNode.intentCode || "",
      level: currentNode.level ?? 0,
      kind: currentNode.kind ?? 0,
      parentCode: currentNode.parentCode || ROOT_PARENT,
      collectionName: currentNode.collectionName || "",
      mcpToolId: currentNode.mcpToolId || "",
      description: currentNode.description || "",
      examplesText: parseExamples(currentNode.examples).join("\n"),
      topK: currentNode.topK ?? undefined,
      sortOrder: currentNode.sortOrder ?? 0,
      enabled: currentNode.enabled !== 0,
      promptSnippet: currentNode.promptSnippet || "",
      promptTemplate: currentNode.promptTemplate || "",
      paramPromptTemplate: currentNode.paramPromptTemplate || ""
    };
  }, [currentNode]);

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: emptyDefaults
  });

  useEffect(() => {
    const loadTree = async () => {
      try {
        setLoading(true);
        const data = await getIntentTree();
        setTree(data || []);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载意图节点失败"));
        console.error(error);
      } finally {
        setLoading(false);
      }
    };
    loadTree();
  }, []);

  useEffect(() => {
    form.reset(resolvedDefaults);
  }, [resolvedDefaults, form]);

  const kind = form.watch("kind");

  const handleSubmit = async (values: FormValues) => {
    if (!currentNode) return;
    if (values.kind === 2 && !values.mcpToolId?.trim()) {
      form.setError("mcpToolId", { message: "MCP节点必须填写工具ID" });
      return;
    }

    const parentCode = values.parentCode === ROOT_PARENT ? null : values.parentCode || null;
    const examples = values.examplesText
      ? values.examplesText
          .split("\n")
          .map((item) => item.trim())
          .filter(Boolean)
      : [];

    const payload: IntentNodeUpdatePayload = {
      name: values.name.trim(),
      level: values.level,
      parentCode,
      description: values.description?.trim() || "",
      examples,
      collectionName: values.kind === 0 ? values.collectionName?.trim() || "" : "",
      mcpToolId: values.kind === 2 ? values.mcpToolId?.trim() || "" : "",
      kind: values.kind,
      topK: values.topK ?? undefined,
      sortOrder: values.sortOrder ?? 0,
      enabled: values.enabled ? 1 : 0,
      promptSnippet: values.promptSnippet?.trim() || "",
      promptTemplate: values.promptTemplate?.trim() || "",
      paramPromptTemplate: values.kind === 2 ? values.paramPromptTemplate?.trim() || "" : ""
    };

    try {
      setSaving(true);
      await updateIntentNode(currentNode.id, payload);
      toast.success("更新成功");
      navigate(returnTo);
    } catch (error) {
      toast.error(getErrorMessage(error, "更新失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">加载中...</CardContent>
        </Card>
      </div>
    );
  }

  if (!currentNode) {
    return (
      <div className="admin-page">
        <Card>
          <CardContent className="space-y-3 py-12 text-center">
            <p className="text-sm text-muted-foreground">未找到对应意图节点</p>
            <Button variant="outline" onClick={() => navigate(returnTo)}>
              返回意图列表
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">编辑意图节点</h1>
          <p className="admin-page-subtitle">
            {currentNode.name}（{currentNode.intentCode}）
          </p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => navigate(returnTo)}>
            返回意图列表
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>节点配置</CardTitle>
          <CardDescription>修改节点基础信息、Prompt与高级参数</CardDescription>
        </CardHeader>
        <CardContent>
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
                        <Input {...field} disabled />
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
                      <Select value={String(field.value)} onValueChange={(value) => field.onChange(Number(value))}>
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
                      <Select value={String(field.value)} onValueChange={(value) => field.onChange(Number(value))}>
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

              {kind === 0 ? (
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
              ) : null}

              {kind === 2 ? (
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
              ) : null}

              <details className="rounded-lg border px-4 py-3" open>
                <summary className="cursor-pointer text-sm font-medium text-foreground">描述与示例</summary>
                <div className="mt-3 space-y-4">
                  <FormField
                    control={form.control}
                    name="description"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>描述</FormLabel>
                        <FormControl>
                          <Textarea placeholder="节点语义说明与适用场景" {...field} />
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
                <summary className="cursor-pointer text-sm font-medium text-foreground">Prompt 配置</summary>
                <div className="mt-3 space-y-4">
                  <FormField
                    control={form.control}
                    name="promptSnippet"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>短规则片段（可选）</FormLabel>
                        <FormControl>
                          <Textarea rows={3} placeholder="多意图场景下的规则补充" {...field} />
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
                        <FormLabel>Prompt 模板（可选）</FormLabel>
                        <FormControl>
                          <Textarea rows={4} placeholder="场景专属完整提示词模板" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  {kind === 2 ? (
                    <FormField
                      control={form.control}
                      name="paramPromptTemplate"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>参数提取提示词模板（MCP专属）</FormLabel>
                          <FormControl>
                            <Textarea rows={4} placeholder="用于提取MCP工具参数" {...field} />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                  ) : null}
                </div>
              </details>

              <details className="rounded-lg border px-4 py-3">
                <summary className="cursor-pointer text-sm font-medium text-foreground">高级设置</summary>
                <div className="mt-3 grid gap-4 md:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="topK"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>节点 TopK（可选）</FormLabel>
                        <FormControl>
                          <Input
                            type="number"
                            min={1}
                            placeholder="留空则使用全局 TopK"
                            value={field.value ?? ""}
                            onChange={(event) => {
                              const value = event.target.value;
                              field.onChange(value === "" ? undefined : Number(value));
                            }}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

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
                              const value = event.target.value;
                              field.onChange(value === "" ? undefined : Number(value));
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
                            <Checkbox checked={field.value} onCheckedChange={(value) => field.onChange(value === true)} />
                          </FormControl>
                          <FormLabel className="!m-0">节点启用</FormLabel>
                        </div>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              </details>

              <div className="flex justify-end gap-2 pt-2">
                <Button type="button" variant="outline" onClick={() => navigate(returnTo)} disabled={saving}>
                  取消
                </Button>
                <Button type="submit" className="admin-primary-gradient" disabled={saving}>
                  {saving ? "保存中..." : "保存修改"}
                </Button>
              </div>
            </form>
          </Form>
        </CardContent>
      </Card>
    </div>
  );
}
