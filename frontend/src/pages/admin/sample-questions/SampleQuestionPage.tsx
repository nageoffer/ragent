import { useEffect, useState } from "react";
import { Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
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
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import type { PageResult, SampleQuestion } from "@/services/sampleQuestionService";
import {
  createSampleQuestion,
  deleteSampleQuestion,
  getSampleQuestionsPage,
  updateSampleQuestion
} from "@/services/sampleQuestionService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const emptyForm = {
  title: "",
  description: "",
  question: ""
};

export function SampleQuestionPage() {
  const [pageData, setPageData] = useState<PageResult<SampleQuestion> | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<SampleQuestion | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [keyword, setKeyword] = useState("");
  const [dialogState, setDialogState] = useState<{
    open: boolean;
    mode: "create" | "edit";
    item: SampleQuestion | null;
  }>({ open: false, mode: "create", item: null });
  const [form, setForm] = useState(emptyForm);

  const loadQuestions = async (current = pageNo, keywordValue = keyword) => {
    try {
      setLoading(true);
      const data = await getSampleQuestionsPage(current, PAGE_SIZE, keywordValue || undefined);
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载示例问题失败"));
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadQuestions();
  }, [pageNo, keyword]);

  useEffect(() => {
    if (!dialogState.open) {
      setForm(emptyForm);
      return;
    }
    if (dialogState.mode === "edit" && dialogState.item) {
      setForm({
        title: dialogState.item.title || "",
        description: dialogState.item.description || "",
        question: dialogState.item.question || ""
      });
      return;
    }
    setForm(emptyForm);
  }, [dialogState]);

  const formatDate = (dateStr?: string | null) => {
    if (!dateStr) return "-";
    return new Date(dateStr).toLocaleString("zh-CN");
  };

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchKeyword.trim());
  };

  const handleRefresh = () => {
    setPageNo(1);
    loadQuestions(1, keyword);
  };

  const openCreateDialog = () => {
    setDialogState({ open: true, mode: "create", item: null });
  };

  const openEditDialog = (item: SampleQuestion) => {
    setDialogState({ open: true, mode: "edit", item });
  };

  const handleSubmit = async () => {
    const payload = {
      title: form.title.trim() || null,
      description: form.description.trim() || null,
      question: form.question.trim()
    };

    if (!payload.question) {
      toast.error("请输入示例问题内容");
      return;
    }

    try {
      if (dialogState.mode === "create") {
        await createSampleQuestion(payload);
        toast.success("创建成功");
        setPageNo(1);
        await loadQuestions(1, keyword);
      } else if (dialogState.item) {
        await updateSampleQuestion(dialogState.item.id, payload);
        toast.success("更新成功");
        await loadQuestions(pageNo, keyword);
      }
      setDialogState({ open: false, mode: "create", item: null });
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      console.error(error);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;

    try {
      await deleteSampleQuestion(deleteTarget.id);
      toast.success("删除成功");
      setDeleteTarget(null);
      setPageNo(1);
      await loadQuestions(1, keyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    } finally {
      setDeleteTarget(null);
    }
  };

  const records = pageData?.records || [];

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">示例问题管理</h1>
          <p className="admin-page-subtitle">配置欢迎页的示例问题与推荐问法</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={searchKeyword}
            onChange={(event) => setSearchKeyword(event.target.value)}
            placeholder="搜索标题/描述/问题"
            className="w-[240px]"
          />
          <Button variant="outline" onClick={handleSearch}>
            搜索
          </Button>
          <Button variant="outline" onClick={handleRefresh}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button onClick={openCreateDialog}>
            <Plus className="w-4 h-4 mr-2" />
            新增示例
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="text-center py-8 text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              暂无示例问题，点击上方按钮新增
            </div>
          ) : (
            <Table className="min-w-[860px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">标题</TableHead>
                  <TableHead className="w-[220px]">描述</TableHead>
                  <TableHead>示例问题</TableHead>
                  <TableHead className="w-[170px]">更新时间</TableHead>
                  <TableHead className="w-[140px] text-left">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="font-medium max-w-[160px] truncate" title={item.title || ""}>
                      {item.title || "-"}
                    </TableCell>
                    <TableCell className="max-w-[200px] truncate" title={item.description || ""}>
                      {item.description || "-"}
                    </TableCell>
                    <TableCell className="max-w-[360px] truncate" title={item.question}>
                      {item.question}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDate(item.updateTime || item.createTime)}
                    </TableCell>
                    <TableCell className="text-center">
                      <div className="flex justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => openEditDialog(item)}>
                          <Pencil className="w-4 h-4 mr-0.5" />
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(item)}
                        >
                          <Trash2 className="w-4 h-4 mr-0.5" />
                          删除
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

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              删除后该示例问题将不会出现在欢迎页，是否继续？
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

      <Dialog
        open={dialogState.open}
        onOpenChange={(open) =>
          setDialogState((prev) => ({ ...prev, open, item: open ? prev.item : null }))
        }
      >
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新增示例问题" : "编辑示例问题"}</DialogTitle>
            <DialogDescription>配置欢迎页的示例问题和推荐问法</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">标题</label>
              <Input
                value={form.title}
                onChange={(event) => setForm((prev) => ({ ...prev, title: event.target.value }))}
                placeholder="例如：任务拆解"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Input
                value={form.description}
                onChange={(event) => setForm((prev) => ({ ...prev, description: event.target.value }))}
                placeholder="例如：把目标拆成可执行步骤与优先级"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">示例问题</label>
              <Textarea
                value={form.question}
                onChange={(event) => setForm((prev) => ({ ...prev, question: event.target.value }))}
                placeholder="请输入示例问题内容"
                className="min-h-[120px]"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDialogState({ open: false, mode: "create", item: null })}
            >
              取消
            </Button>
            <Button onClick={handleSubmit}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
