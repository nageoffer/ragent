import test from "node:test";
import assert from "node:assert/strict";
import { mergeCartOperation, mergeCartOperations, CartRecoveryQueue } from "../src/lib/cartOperationState.ts";

const pending = { operationId: "O1", status: "PENDING", writeAttempts: 1, skuCode: "A", quantity: 1 };
const deferred = (retryAt) => ({ operation: pending, verificationStarted: false, deferred: true, retryAt });

for (const status of ["SUCCEEDED", "REJECTED", "CANCELLED"]) {
  test(`late pending response cannot overwrite ${status}`, () => {
    const terminal = { ...pending, status, message: "确定结果", quantityAfter: 2 };
    const merged = mergeCartOperation(terminal, { ...pending, stoppedAt: "2026-10-24T01:00:00Z", writeAttempts: 2 });
    assert.equal(merged.status, status);
    assert.equal(merged.message, "确定结果");
    assert.equal(merged.quantityAfter, 2);
    assert.ok(merged.stoppedAt);
    assert.equal(merged.writeAttempts, 2);
  });
}

test("an older stop response cannot erase success or previously recorded stop", () => {
  const stopped = { ...pending, stoppedAt: "2026-10-24T01:00:00Z", writeAttempts: 2 };
  const success = mergeCartOperation(stopped, { ...pending, status: "SUCCEEDED" });
  const late = mergeCartOperation(success, pending);
  assert.equal(late.status, "SUCCEEDED");
  assert.equal(late.stoppedAt, stopped.stoppedAt);
  assert.equal(late.writeAttempts, 2);
});

test("a list captured before action completion cannot remove a new operation or regress its result", () => {
  const complete = { ...pending, status: "SUCCEEDED" };
  const newer = { ...pending, operationId: "O2" };
  const merged = mergeCartOperations([complete, newer], [pending]);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].status, "SUCCEEDED");
  assert.equal(merged[1].operationId, "O2");
});

test("recovery waits for the residual lease and performs only one actual verification round", () => {
  const queue = new CartRecoveryQueue();
  assert.equal(queue.take(pending, 0), true);
  assert.equal(queue.take(pending, 100), false);
  queue.complete("O1", deferred(new Date(45000).toISOString()), 100);
  assert.equal(queue.take(pending, 44000), false);
  assert.equal(queue.take(pending, 45000), true);
  queue.complete("O1", { operation: pending, verificationStarted: true, deferred: false }, 50000);
  assert.equal(queue.take(pending, 51000), false);
  assert.equal(queue.take(pending, 90000), false);
});

test("deferred attempts stop after the bounded recovery window", () => {
  const queue = new CartRecoveryQueue();
  assert.equal(queue.take(pending, 0), true);
  queue.complete("O1", deferred(new Date(90000).toISOString()), 10);
  assert.equal(queue.take(pending, 90000), false);
});

test("a terminal response or failed request does not trigger endless automatic verification", () => {
  for (const result of [undefined, { operation: { ...pending, status: "SUCCEEDED" }, deferred: false }]) {
    const queue = new CartRecoveryQueue();
    assert.equal(queue.take(pending, 0), true);
    queue.complete("O1", result, 100);
    assert.equal(queue.take(pending, 2000), false);
  }
});

test("product names survive partial responses and enrich terminal history", () => {
  const named = { ...pending, productName: "测试商品 256GB 黑色" };
  assert.equal(mergeCartOperation(named, pending).productName, named.productName);
  assert.equal(mergeCartOperation({ ...named, status: "SUCCEEDED" }, { ...named, productName: "新商品名" }).productName, "新商品名");
  assert.equal(mergeCartOperation({ ...pending, status: "SUCCEEDED" }, named).productName, named.productName);
});
