import * as React from "react";
import { toast } from "sonner";
import { ChevronDown, Loader2, ShoppingBag } from "lucide-react";
import { isCartOperationTerminal, mergeCartOperations, CartRecoveryQueue } from "@/lib/cartOperationState";
import { actOnCartOperation, listCartOperations, type CartOperation, type CartOperationActionResult } from "@/services/agentService";

const labels: Record<CartOperation["status"], string> = {
  PROCESSING: "正在加入购物车", VERIFYING: "正在核实加购结果", PENDING: "暂时无法确认是否加购成功",
  SUCCEEDED: "已加入购物车", REJECTED: "本次未加入", CANCELLED: "已取消，本次未执行"
};
const unresolved = (op: CartOperation) => !isCartOperationTerminal(op);

/** 独立于助手消息展示业务结果；刷新后也能找回。 */
export function AgentCartOperations({ conversationId, streaming }: { conversationId: string | null; streaming: boolean }) {
  const panelId = React.useId();
  const [disclosure, setDisclosure] = React.useState<{ conversationId: string | null; open: boolean } | null>(null);
  const [operations, setOperations] = React.useState<CartOperation[]>([]);
  const [busy, setBusy] = React.useState<Set<string>>(new Set());
  const [error, setError] = React.useState("");
  const [availability, setAvailability] = React.useState<Record<string, CartOperationActionResult>>({});
  const [now, setNow] = React.useState(Date.now);
  const epoch = React.useRef(0);
  const requests = React.useRef(new Map<string, AbortController>());

  React.useEffect(() => {
    setDisclosure(null);
  }, [conversationId]);

  const action = React.useCallback(async (op: CartOperation, type: "verify" | "continue" | "stop") => {
    const key = `${op.operationId}:${type}`;
    if (requests.current.has(key)) return;
    const currentEpoch = epoch.current;
    const controller = new AbortController();
    requests.current.set(key, controller);
    if (type !== "stop") setBusy(prev => new Set(prev).add(op.operationId));
    try {
      const result = await actOnCartOperation(op.operationId, type, controller.signal);
      if (epoch.current === currentEpoch) {
        setOperations(prev => mergeCartOperations(prev, [result.operation]));
        setAvailability(prev => ({ ...prev, [op.operationId]: result }));
        if (type === "continue" && result.deferred) toast.info(result.notice || "暂时不能继续，请等待后重试");
      }
      return result;
    } catch (e) {
      if (!controller.signal.aborted && epoch.current === currentEpoch) toast.error((e as Error).message || "操作结果尚未确定，请稍后核实");
    } finally {
      if (requests.current.get(key) === controller) requests.current.delete(key);
      if (epoch.current === currentEpoch && type !== "stop") setBusy(prev => { const next = new Set(prev); next.delete(op.operationId); return next; });
    }
  }, []);

  React.useEffect(() => {
    epoch.current += 1;
    const currentEpoch = epoch.current;
    setOperations([]);
    setBusy(new Set());
    setError("");
    setAvailability({});
    if (!conversationId) return;
    let loading = false;
    const recovery = new CartRecoveryQueue();
    const refresh = async () => {
      if (loading) return;
      loading = true;
      try {
        const result = await listCartOperations(conversationId);
        if (epoch.current !== currentEpoch) return;
        setOperations(prev => mergeCartOperations(prev, result));
        setError("");
        if (!streaming) {
          result.forEach(op => {
            if (requests.current.has(`${op.operationId}:verify`) || requests.current.has(`${op.operationId}:continue`)) return;
            if (!recovery.take(op, Date.now())) return;
            void action(op, "verify").then(outcome => recovery.complete(op.operationId, outcome, Date.now()));
          });
        }
      } catch {
        if (epoch.current === currentEpoch) setError("暂时无法加载加购结果，请稍后刷新核实");
      } finally { loading = false; }
    };
    void refresh();
    const timer = window.setInterval(() => { setNow(Date.now()); void refresh(); }, 2500);
    const pendingRequests = requests.current;
    return () => {
      epoch.current += 1;
      window.clearInterval(timer);
      pendingRequests.forEach(controller => controller.abort());
      pendingRequests.clear();
    };
  }, [conversationId, streaming, action]);

  const pendingCount = operations.filter(unresolved).length;
  const expanded = disclosure?.conversationId === conversationId
    ? disclosure.open
    : pendingCount > 0 || Boolean(error);
  const summary = pendingCount > 0
    ? `${pendingCount} 项待处理`
    : error ? "结果暂不可用" : `${operations.length} 项已结束`;

  if (!operations.length && !error) return null;
  return (
    <section className="agent-cart" aria-label="加购结果">
      <div className="agent-cart-bar" data-attention={pendingCount > 0 || Boolean(error)}>
        <button
          type="button"
          className="agent-cart-toggle"
          aria-expanded={expanded}
          aria-controls={panelId}
          onClick={() => setDisclosure({ conversationId, open: !expanded })}
        >
          <ShoppingBag size={15} aria-hidden="true" />
          <span className="agent-cart-title">加购</span>
          <span className="agent-cart-summary" role="status">{summary}</span>
          <ChevronDown size={14} className="agent-cart-chevron" data-expanded={expanded} aria-hidden="true" />
        </button>
        <div id={panelId} hidden={!expanded}>
          <div className="agent-cart-items">
            {error && <p className="agent-cart-notice" role="alert">{error}</p>}
            {operations.map(op => {
              const waiting = availability[op.operationId];
              const retryAt = waiting?.retryAt ? Date.parse(waiting.retryAt) : 0;
              const coolingDown = retryAt > now;
              const working = busy.has(op.operationId);
              const pending = unresolved(op);
              const stateClass = op.status === "REJECTED" ? "agent-status-err"
                : pending ? "agent-status-run" : "agent-status-ok";
              const hasDetails = op.message || op.stoppedAt || op.status === "SUCCEEDED";
              return (
                <article key={op.operationId} className="agent-cart-item">
                  <div className="agent-cart-item-head">
                    <div className="agent-cart-product">
                      <span className="agent-cart-name">{op.productName?.trim() || op.skuCode}</span>
                      {op.productName?.trim() && <span className="agent-cart-sku">SKU · {op.skuCode}</span>}
                    </div>
                    <span className="agent-cart-quantity">+{op.quantity} 件</span>
                    <span className={`agent-cart-state ${stateClass}`} role="status">
                      {working && <Loader2 size={12} className="motion-safe:animate-spin" aria-hidden="true" />}
                      {working ? "正在核实或继续加购" : labels[op.status]}
                    </span>
                  </div>
                  {hasDetails && (
                    <details className="agent-cart-details">
                      <summary>查看详情</summary>
                      {op.message && <p>{op.message}</p>}
                      {op.status === "SUCCEEDED" && (
                        <p>该次执行后数量：{op.quantityAfter} 件 · {op.executedAt ? new Date(op.executedAt).toLocaleString() : "时间未知"}</p>
                      )}
                      {op.stoppedAt && (
                        <p>{op.status === "SUCCEEDED" ? "用户已停止；停止请求未撤销已提交操作。" : "用户已停止后续写尝试，仍可核实结果。"}</p>
                      )}
                    </details>
                  )}
                  {pending && waiting?.notice && (
                    <p className="agent-cart-notice" role="status">
                      {waiting.notice}{coolingDown ? `（约 ${Math.ceil((retryAt - now) / 1000)} 秒后可再操作）` : "；现在可以重试。"}
                    </p>
                  )}
                  {pending && (
                    <div className="agent-cart-actions">
                      <button type="button" className="agent-confirm-btn" disabled={working || coolingDown} onClick={() => void action(op, "verify")}>核实结果</button>
                      {!op.stoppedAt && op.writeAttempts < 3 && !streaming && (
                        <button type="button" className="agent-confirm-btn" data-primary="true" disabled={working || coolingDown} onClick={() => void action(op, "continue")}>继续本次加购</button>
                      )}
                      {!op.stoppedAt && (
                        <button type="button" className="agent-cart-stop" onClick={() => void action(op, "stop")}>停止后续尝试</button>
                      )}
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
