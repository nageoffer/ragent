import { RefreshCw, Search } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { STATUS_OPTIONS, type TraceFilters, type TraceStatus } from "@/pages/admin/traces/traceUtils";

interface FilterBarProps {
  filters: TraceFilters;
  onFiltersChange: (next: Partial<TraceFilters>) => void;
  onSearch: () => void;
  onRefresh: () => void;
  onReset: () => void;
}

export function FilterBar({ filters, onFiltersChange, onSearch, onRefresh, onReset }: FilterBarProps) {
  return (
    <div className="trace-list-filter-card">
      <div className="trace-list-filter-body">
        <div className="trace-list-filter-grid">
          <Input
            className="trace-list-control"
            value={filters.traceId}
            onChange={(event) => onFiltersChange({ traceId: event.target.value })}
            placeholder="按 Trace Id 过滤"
          />
          <Input
            className="trace-list-control"
            value={filters.conversationId}
            onChange={(event) => onFiltersChange({ conversationId: event.target.value })}
            placeholder="按会话 ID 过滤"
          />
          <Input
            className="trace-list-control"
            value={filters.taskId}
            onChange={(event) => onFiltersChange({ taskId: event.target.value })}
            placeholder="按 Task ID 过滤"
          />
          <Select
            value={filters.status || "__all__"}
            onValueChange={(value) => {
              const nextStatus = value === "__all__" ? "" : (value as TraceStatus);
              onFiltersChange({ status: nextStatus });
            }}
          >
            <SelectTrigger className="trace-list-control">
              <SelectValue placeholder="全部状态" />
            </SelectTrigger>
            <SelectContent>
              {STATUS_OPTIONS.map((option) => (
                <SelectItem key={option.value || "__all__"} value={option.value || "__all__"}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <div className="trace-list-filter-actions">
            <div className="trace-list-action-group">
              <Button className="trace-list-btn trace-list-btn-reset" variant="ghost" onClick={onReset}>
                重置
              </Button>
              <Button className="trace-list-btn trace-list-btn-refresh" variant="outline" onClick={onRefresh}>
                <RefreshCw className="h-4 w-4" />
                刷新
              </Button>
              <Button className="trace-list-btn trace-list-btn-primary" onClick={onSearch}>
                <Search className="h-4 w-4" />
                查询
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
