import type { CartOperation, CartOperationActionResult } from "@/services/agentService";

export const isCartOperationTerminal = (op: CartOperation) =>
  ["SUCCEEDED", "REJECTED", "CANCELLED"].includes(op.status);

/** 旧列表或操作响应不得覆盖已确定结果；停止事实及已用预算不能倒退。 */
export function mergeCartOperation(current: CartOperation, incoming: CartOperation): CartOperation {
  const result = isCartOperationTerminal(current) ? current : incoming;
  return {
    ...result,
    productName: incoming.productName?.trim() || current.productName?.trim() || null,
    writeAttempts: Math.max(current.writeAttempts, incoming.writeAttempts),
    stoppedAt: current.stoppedAt ?? incoming.stoppedAt,
    disconnectedAt: current.disconnectedAt ?? incoming.disconnectedAt
  };
}

export function mergeCartOperations(current: CartOperation[], incoming: CartOperation[]): CartOperation[] {
  const next = new Map(current.map(op => [op.operationId, op]));
  for (const op of incoming) {
    const previous = next.get(op.operationId);
    next.set(op.operationId, previous ? mergeCartOperation(previous, op) : op);
  }
  return [...next.values()];
}

/** 每次进入会话至多完成一轮自动核实。仅资格暂不可用时有界等待，不重复开启核实轮次。 */
export class CartRecoveryQueue {
  private entries = new Map<string, { deadline: number; nextAt: number; inFlight: boolean; finished: boolean }>();

  take(op: CartOperation, now: number): boolean {
    if (isCartOperationTerminal(op)) return false;
    let entry = this.entries.get(op.operationId);
    if (!entry) {
      entry = { deadline: now + 60000, nextAt: now, inFlight: false, finished: false };
      this.entries.set(op.operationId, entry);
    }
    if (entry.finished || entry.inFlight || now > entry.deadline || now < entry.nextAt) return false;
    entry.inFlight = true;
    return true;
  }

  complete(id: string, result: CartOperationActionResult | undefined, now: number) {
    const entry = this.entries.get(id);
    if (!entry) return;
    entry.inFlight = false;
    if (!result || !result.deferred || result.verificationStarted || isCartOperationTerminal(result.operation)) {
      entry.finished = true;
      return;
    }
    const retryAt = result.retryAt ? Date.parse(result.retryAt) : now + 2500;
    entry.nextAt = Math.max(now + 1000, Number.isFinite(retryAt) ? retryAt : now + 2500);
    if (entry.nextAt > entry.deadline) entry.finished = true;
  }
}
