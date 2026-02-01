import { useEffect, useState } from "react";
import { FileText, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { getAllDocumentsPage, type KnowledgeDocument, type PageResult } from "@/services/knowledgeService";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "failed", label: "failed" },
  { value: "success", label: "success" }
];

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
  return "-";
};

export function KnowledgeAllDocumentsPage() {
  const navigate = useNavigate();
  const [pageData, setPageData] = useState<PageResult<KnowledgeDocument> | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [pageNo, setPageNo] = useState(1);

  const documents = pageData?.records || [];

  const loadDocuments = async (current = pageNo, status = statusFilter, name = keyword) => {
    try {
      setLoading(true);
      const data = await getAllDocumentsPage({
        pageNo: current,
        pageSize: PAGE_SIZE,
        status,
        keyword: name || undefined
      });
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载文档列表失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDocuments();
  }, [pageNo, statusFilter, keyword]);

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchInput.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadDocuments(1, statusFilter, keyword);
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">文档管理</h1>
          <p className="admin-page-subtitle">查看所有知识库文档与处理状态</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="搜索文档名称"
            className="w-[220px]"
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

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : documents.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无文档</div>
          ) : (
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[260px]">文档</TableHead>
                  <TableHead className="w-[200px]">知识库</TableHead>
                  <TableHead className="w-[120px]">来源</TableHead>
                  <TableHead className="w-[120px]">处理模式</TableHead>
                  <TableHead className="w-[140px]">状态</TableHead>
                  <TableHead className="w-[170px]">更新时间</TableHead>
                  <TableHead className="w-[140px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {documents.map((doc) => (
                  <TableRow key={doc.id}>
                    <TableCell className="font-medium">
                      <div className="flex min-w-0 max-w-[300px] items-center gap-2">
                        <FileText className="h-4 w-4 text-muted-foreground" />
                        <button
                          type="button"
                          className="admin-link flex-1 truncate text-left"
                          title={doc.docName || ""}
                          onClick={() => navigate(`/admin/knowledge/${doc.kbId}`)}
                        >
                          {doc.docName || "-"}
                        </button>
                      </div>
                    </TableCell>
                    <TableCell>
                      <button
                        type="button"
                        className="admin-link truncate"
                        title={doc.kbName || String(doc.kbId)}
                        onClick={() => navigate(`/admin/knowledge/${doc.kbId}`)}
                      >
                        {doc.kbName || doc.kbId || "-"}
                      </button>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs text-muted-foreground">
                        {formatSourceLabel(doc.sourceType)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs text-muted-foreground">
                        {formatProcessMode(doc.processMode)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="inline-flex items-center gap-2 text-xs text-muted-foreground">
                        <span className={cn("h-2 w-2 rounded-full", statusDotClass(doc.status))} />
                        <span>{doc.status || "-"}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(doc.updateTime)}
                    </TableCell>
                    <TableCell className="text-center">
                      <Button variant="outline" size="sm" onClick={() => navigate(`/admin/knowledge/${doc.kbId}`)}>
                        查看
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {pageData ? (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
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
    </div>
  );
}
