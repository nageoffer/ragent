import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { GitBranch, Pencil, RefreshCw, Search, X } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger
} from "@/components/ui/alert-dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  batchDeleteIntentNodes,
  batchDisableIntentNodes,
  batchEnableIntentNodes,
  getIntentTree,
  type IntentNodeTree
} from "@/services/intentTreeService";
import { getErrorMessage } from "@/utils/error";

const ALL_VALUE = "__ALL__";
const ROOT_VALUE = "__ROOT__";
const PAGE_SIZE_OPTIONS = [10, 20, 50];

const LEVEL_OPTIONS = [
  { value: 0, label: "DOMAIN" },
  { value: 1, label: "CATEGORY" },
  { value: 2, label: "TOPIC" }
];

const KIND_OPTIONS = [
  { value: 0, label: "KB" },
  { value: 1, label: "SYSTEM" },
  { value: 2, label: "MCP" }
];

const FILTER_SELECT_TRIGGER_CLASS =
  "h-10 text-sm border-slate-200 focus:ring-0 focus:ring-offset-0 focus-visible:ring-0 focus-visible:ring-offset-0 data-[state=open]:border-slate-200 data-[state=open]:ring-0";
const FILTER_INPUT_CLASS =
  "h-10 border-slate-200 pl-10 text-sm focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-slate-200";

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
  depth: number;
  pathText: string;
  pathNames: string[];
  pathCodes: string[];
  childCount: number;
  exampleCount: number;
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
  parentNames: string[] = [],
  parentCodes: string[] = []
): FlatIntentNode[] => {
  const result: FlatIntentNode[] = [];
  nodes.forEach((node) => {
    const currentNames = [...parentNames, node.name];
    const currentCodes = [...parentCodes, node.intentCode];
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
      depth: Math.max(currentNames.length - 1, 0),
      pathText: currentNames.join(" > "),
      pathNames: currentNames,
      pathCodes: currentCodes,
      childCount: children.length,
      exampleCount: parseExamples(node.examples).length
    });
    result.push(...flattenIntentTree(children, currentNames, currentCodes));
  });
  return result;
};

const resolveLevelLabel = (value: number) =>
  LEVEL_OPTIONS.find((option) => option.value === value)?.label ?? "UNKNOWN";

const resolveKindLabel = (value: number) =>
  KIND_OPTIONS.find((option) => option.value === value)?.label ?? "UNKNOWN";

const resolveKindBadge = (value: number) => {
  const label = resolveKindLabel(value);
  if (label === "MCP") return "default";
  if (label === "SYSTEM") return "secondary";
  return "outline";
};

const resolveLevelBadgeClass = (value: number) => {
  if (value === 0) return "border-[#91d5ff] bg-[#e6f7ff] text-[#1890FF]";
  if (value === 1) return "border-[#b7eb8f] bg-[#f6ffed] text-[#52C41A]";
  if (value === 2) return "border-[#ffd591] bg-[#fff7e6] text-[#FA8C16]";
  return "border-slate-200 bg-slate-50 text-slate-600";
};

export function IntentListPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [tree, setTree] = useState<IntentNodeTree[]>([]);
  const [loading, setLoading] = useState(true);
  const [levelFilter, setLevelFilter] = useState(ALL_VALUE);
  const [kindFilter, setKindFilter] = useState(ALL_VALUE);
  const [statusFilter, setStatusFilter] = useState(ALL_VALUE);
  const [parentFilter, setParentFilter] = useState(ALL_VALUE);
  const [keyword, setKeyword] = useState("");
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [batchSubmitting, setBatchSubmitting] = useState<null | "enable" | "disable" | "delete">(null);

  const loadIntentTree = async () => {
    try {
      setLoading(true);
      const data = await getIntentTree();
      setTree(data || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载意图列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadIntentTree();
  }, []);

  const rows = useMemo(() => flattenIntentTree(tree), [tree]);

  const parentOptions = useMemo(() => {
    return [
      { value: ALL_VALUE, label: "全部父节点" },
      { value: ROOT_VALUE, label: "ROOT（根节点）" },
      ...rows.map((row) => ({
        value: row.intentCode,
        label: row.pathText
      }))
    ];
  }, [rows]);

  const filteredRows = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return rows.filter((row) => {
      if (normalizedKeyword) {
        const searchable = [row.name, row.intentCode, String(row.id), row.pathText]
          .join(" ")
          .toLowerCase();
        if (!searchable.includes(normalizedKeyword)) {
          return false;
        }
      }

      if (levelFilter !== ALL_VALUE && row.level !== Number(levelFilter)) {
        return false;
      }

      if (kindFilter !== ALL_VALUE && row.kind !== Number(kindFilter)) {
        return false;
      }

      if (statusFilter === "enabled" && row.enabled === 0) {
        return false;
      }
      if (statusFilter === "disabled" && row.enabled !== 0) {
        return false;
      }

      if (parentFilter !== ALL_VALUE) {
        if (parentFilter === ROOT_VALUE) {
          if (row.parentCode) {
            return false;
          }
        } else if (row.parentCode !== parentFilter) {
          return false;
        }
      }

      return true;
    });
  }, [rows, keyword, levelFilter, kindFilter, statusFilter, parentFilter]);

  const total = filteredRows.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(pageNo, totalPages);
  const startIndex = (currentPage - 1) * pageSize;
  const pageRows = filteredRows.slice(startIndex, startIndex + pageSize);
  const selectedIdSet = useMemo(() => new Set(selectedIds), [selectedIds]);
  const selectedRows = useMemo(() => rows.filter((row) => selectedIdSet.has(row.id)), [rows, selectedIdSet]);
  const pageRowIds = useMemo(() => pageRows.map((row) => row.id), [pageRows]);
  const allPageSelected =
    pageRowIds.length > 0 && pageRowIds.every((id) => selectedIdSet.has(id));
  const somePageSelected =
    !allPageSelected && pageRowIds.some((id) => selectedIdSet.has(id));

  useEffect(() => {
    if (pageNo !== currentPage) {
      setPageNo(currentPage);
    }
  }, [currentPage, pageNo]);

  useEffect(() => {
    setSelectedIds((prev) => prev.filter((id) => rows.some((row) => row.id === id)));
  }, [rows]);

  const handleResetFilters = () => {
    setKeyword("");
    setLevelFilter(ALL_VALUE);
    setKindFilter(ALL_VALUE);
    setStatusFilter(ALL_VALUE);
    setParentFilter(ALL_VALUE);
    setPageNo(1);
  };

  const toggleRowSelect = (id: number, checked: boolean) => {
    setSelectedIds((prev) => {
      if (checked) {
        if (prev.includes(id)) return prev;
        return [...prev, id];
      }
      return prev.filter((item) => item !== id);
    });
  };

  const togglePageSelect = (checked: boolean) => {
    setSelectedIds((prev) => {
      if (checked) {
        const next = new Set(prev);
        pageRowIds.forEach((id) => next.add(id));
        return Array.from(next);
      }
      const pageIdSet = new Set(pageRowIds);
      return prev.filter((id) => !pageIdSet.has(id));
    });
  };

  const runBatchUpdateEnabled = async (enabled: 0 | 1) => {
    if (selectedRows.length === 0 || batchSubmitting) return;
    setBatchSubmitting(enabled === 1 ? "enable" : "disable");
    try {
      const targetIds = selectedRows.map((row) => row.id);
      if (enabled === 1) {
        await batchEnableIntentNodes(targetIds);
      } else {
        await batchDisableIntentNodes(targetIds);
      }
      toast.success(`已${enabled === 1 ? "启用" : "禁用"} ${targetIds.length} 项`);
      await loadIntentTree();
      setSelectedIds([]);
    } catch (error) {
      toast.error(getErrorMessage(error, "批量更新失败"));
      console.error(error);
    } finally {
      setBatchSubmitting(null);
    }
  };

  const runBatchDelete = async () => {
    if (selectedRows.length === 0 || batchSubmitting) return;
    setBatchSubmitting("delete");
    try {
      const targetIds = selectedRows.map((row) => row.id);
      await batchDeleteIntentNodes(targetIds);
      toast.success(`已删除 ${targetIds.length} 项`);
      await loadIntentTree();
      setSelectedIds([]);
    } catch (error) {
      toast.error(getErrorMessage(error, "批量删除失败"));
      console.error(error);
    } finally {
      setBatchSubmitting(null);
    }
  };

  const resolveResourceText = (row: FlatIntentNode) => {
    if (row.kind === 0) {
      return row.collectionName || "-";
    }
    if (row.kind === 2) {
      return row.mcpToolId || "-";
    }
    return "系统策略";
  };

  const rangeStart = total === 0 ? 0 : startIndex + 1;
  const rangeEnd = total === 0 ? 0 : Math.min(startIndex + pageRows.length, total);
  const isBatchDisabled = Boolean(batchSubmitting);
  const showPagination = !loading && total > 0;

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">意图列表</h1>
          <p className="admin-page-subtitle">支持多维筛选、分页查看和快速定位到意图树节点</p>
        </div>
      </div>

      <div className="space-y-3">
        <div className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
            <div className="relative w-full lg:min-w-[280px] lg:max-w-[420px] lg:flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <Input
                value={keyword}
                onChange={(event) => {
                  setKeyword(event.target.value);
                  setPageNo(1);
                }}
                placeholder="搜索意图名称/ID..."
                aria-label="搜索意图名称或ID"
                className={FILTER_INPUT_CLASS}
              />
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Select
                value={levelFilter}
                onValueChange={(value) => {
                  setLevelFilter(value);
                  setPageNo(1);
                }}
              >
                <SelectTrigger aria-label="层级筛选" className={cn("w-[136px]", FILTER_SELECT_TRIGGER_CLASS)}>
                  <SelectValue placeholder="层级" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_VALUE}>全部层级</SelectItem>
                  {LEVEL_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={String(option.value)}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Select
                value={kindFilter}
                onValueChange={(value) => {
                  setKindFilter(value);
                  setPageNo(1);
                }}
              >
                <SelectTrigger aria-label="类型筛选" className={cn("w-[136px]", FILTER_SELECT_TRIGGER_CLASS)}>
                  <SelectValue placeholder="类型" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_VALUE}>全部类型</SelectItem>
                  {KIND_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={String(option.value)}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Select
                value={statusFilter}
                onValueChange={(value) => {
                  setStatusFilter(value);
                  setPageNo(1);
                }}
              >
                <SelectTrigger aria-label="状态筛选" className={cn("w-[136px]", FILTER_SELECT_TRIGGER_CLASS)}>
                  <SelectValue placeholder="状态" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_VALUE}>全部状态</SelectItem>
                  <SelectItem value="enabled">仅启用</SelectItem>
                  <SelectItem value="disabled">仅禁用</SelectItem>
                </SelectContent>
              </Select>

              <Select
                value={parentFilter}
                onValueChange={(value) => {
                  setParentFilter(value);
                  setPageNo(1);
                }}
              >
                <SelectTrigger aria-label="父节点筛选" className={cn("w-[220px]", FILTER_SELECT_TRIGGER_CLASS)}>
                  <SelectValue placeholder="父节点" />
                </SelectTrigger>
                <SelectContent className="max-h-[22rem]">
                  {parentOptions.map((option) => (
                    <SelectItem
                      key={option.value}
                      value={option.value}
                      title={option.label}
                      className="max-w-[32rem]"
                    >
                      <span className="block truncate">{option.label}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Button
                variant="outline"
                className="h-10 gap-1.5 border-slate-200 px-3 text-sm"
                onClick={loadIntentTree}
                disabled={loading}
              >
                <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
                刷新
              </Button>
              <Button
                variant="outline"
                className="h-10 gap-1.5 border-rose-200 bg-rose-50 px-3 text-sm font-medium text-rose-700 hover:bg-rose-100 hover:text-rose-800"
                onClick={handleResetFilters}
              >
                <X className="h-4 w-4" />
                清空筛选
              </Button>
            </div>
          </div>
        </div>
      </div>

      <Card className="overflow-hidden">
        <CardContent className="space-y-3 pt-4">
          {selectedRows.length > 0 ? (
            <div className="-mx-6">
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-none border-y border-slate-200/80 bg-slate-50 px-6 py-2">
                <span className="text-sm font-medium text-slate-700">已选 {selectedRows.length} 项</span>
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-8 px-3 text-xs"
                    disabled={isBatchDisabled}
                    onClick={() => runBatchUpdateEnabled(1)}
                  >
                    批量启用
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-8 px-3 text-xs"
                    disabled={isBatchDisabled}
                    onClick={() => runBatchUpdateEnabled(0)}
                  >
                    批量禁用
                  </Button>
                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-8 px-3 text-xs text-destructive hover:text-destructive"
                        disabled={isBatchDisabled}
                      >
                        批量删除
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>确认批量删除？</AlertDialogTitle>
                        <AlertDialogDescription>
                          将删除已选中的 {selectedRows.length} 个意图节点，该操作不可恢复。
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>取消</AlertDialogCancel>
                        <AlertDialogAction
                          className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                          onClick={runBatchDelete}
                        >
                          删除
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </div>
              </div>
            </div>
          ) : null}

          {loading ? (
            <div className="py-10 text-center text-muted-foreground">加载中...</div>
          ) : pageRows.length === 0 ? (
            <div className="py-10 text-center text-muted-foreground">
              {rows.length === 0
                ? "暂无意图节点，请先在意图树配置中创建"
                : "没有匹配结果，请调整筛选条件"}
            </div>
          ) : (
            <Table className="min-w-[1280px] [&_th]:h-10 [&_th]:py-2 [&_td]:py-2">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[48px]">
                    <Checkbox
                      checked={allPageSelected ? true : somePageSelected ? "indeterminate" : false}
                      onCheckedChange={(checked) => togglePageSelect(checked === true)}
                      aria-label="全选当前页"
                      disabled={batchSubmitting !== null || pageRows.length === 0}
                    />
                  </TableHead>
                  <TableHead className="w-[300px]">意图节点</TableHead>
                  <TableHead className="w-[120px]">层级</TableHead>
                  <TableHead className="w-[120px]">类型</TableHead>
                  <TableHead className="w-[320px]">路径</TableHead>
                  <TableHead className="w-[220px]">关联资源</TableHead>
                  <TableHead className="w-[90px]">示例数</TableHead>
                  <TableHead className="w-[90px]">状态</TableHead>
                  <TableHead className="sticky right-0 z-20 w-[180px] bg-[#F9FAFB] text-left shadow-[-1px_0_0_rgba(226,232,240,1)]">
                    操作
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pageRows.map((row) => (
                  <TableRow
                    key={row.id}
                    className="group text-[13px] hover:!bg-slate-50"
                  >
                    <TableCell>
                      <Checkbox
                        checked={selectedIdSet.has(row.id)}
                        onCheckedChange={(checked) => toggleRowSelect(row.id, checked === true)}
                        aria-label={`选择 ${row.name}`}
                        disabled={batchSubmitting !== null}
                      />
                    </TableCell>
                    <TableCell>
                      <div className="space-y-0.5">
                        <div className="flex items-center gap-2">
                          <span className="font-semibold text-slate-900">{row.name}</span>
                          <span className="rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-xs text-slate-600">
                            {row.intentCode}
                          </span>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className={cn("font-medium", resolveLevelBadgeClass(row.level))}>
                        {resolveLevelLabel(row.level)}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={resolveKindBadge(row.kind)}>
                        {resolveKindLabel(row.kind)}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-1">
                        {row.pathNames.map((segment, index) => (
                          <span key={`${row.id}-${segment}-${index}`} className="inline-flex items-center gap-1">
                            {index > 0 ? <span className="text-slate-300">/</span> : null}
                            <button
                              type="button"
                              className={cn(
                                "rounded px-1.5 py-0.5 text-xs transition-colors",
                                index === row.pathNames.length - 1
                                  ? "bg-slate-100 text-slate-600 font-medium hover:bg-slate-200"
                                  : "text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                              )}
                              onClick={() =>
                                navigate(
                                  `/admin/intent-tree?intentCode=${encodeURIComponent(row.pathCodes[index])}`
                                )
                              }
                            >
                              {segment}
                            </button>
                          </span>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="space-y-0.5">
                        <div
                          className="truncate text-sm text-slate-700"
                          title={resolveResourceText(row)}
                        >
                          {resolveResourceText(row)}
                        </div>
                        <p className="text-xs text-slate-400">TopK: {row.topK ?? "全局默认"}</p>
                      </div>
                    </TableCell>
                    <TableCell>
                      <span className="font-medium text-slate-700">{row.exampleCount}</span>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={row.enabled === 0 ? "secondary" : "default"}
                        className={cn(
                          row.enabled === 0
                            ? "border-[#d9d9d9] bg-[#fafafa] text-[#8c8c8c] font-semibold"
                            : "border-[#b7eb8f] bg-[#f6ffed] text-[#52C41A] font-semibold"
                        )}
                      >
                        {row.enabled === 0 ? "禁用" : "启用"}
                      </Badge>
                    </TableCell>
                    <TableCell
                      className="sticky right-0 z-10 bg-white shadow-[-1px_0_0_rgba(226,232,240,1)] group-hover:bg-slate-50"
                    >
                      <div className="flex items-center gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 px-2.5 text-xs"
                          title="编辑"
                          aria-label={`编辑 ${row.name}`}
                          onClick={() =>
                            navigate(
                              `/admin/intent-list/${row.id}/edit?from=${encodeURIComponent(
                                `${location.pathname}${location.search}`
                              )}`
                            )
                          }
                        >
                          <Pencil className="mr-0.5 h-4 w-4" />
                          编辑
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-8 px-2.5 text-xs"
                          title="定位树"
                          aria-label={`定位 ${row.name} 到意图树`}
                          onClick={() =>
                            navigate(
                              `/admin/intent-tree?intentCode=${encodeURIComponent(row.intentCode)}`
                            )
                          }
                        >
                          <GitBranch className="mr-0.5 h-4 w-4" />
                          定位树
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

      {showPagination ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
          <span>
            共 {total} 条，显示 {rangeStart}-{rangeEnd}
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <span>每页</span>
            <Select
              value={String(pageSize)}
              onValueChange={(value) => {
                setPageSize(Number(value));
                setPageNo(1);
              }}
            >
              <SelectTrigger className="h-8 w-[92px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PAGE_SIZE_OPTIONS.map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size} 条
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo(1)}
              disabled={currentPage <= 1}
            >
              首页
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              disabled={currentPage <= 1}
            >
              上一页
            </Button>
            <span>
              {currentPage} / {totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo((prev) => Math.min(totalPages, prev + 1))}
              disabled={currentPage >= totalPages}
            >
              下一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPageNo(totalPages)}
              disabled={currentPage >= totalPages}
            >
              末页
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
