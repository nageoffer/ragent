import type { OrchestrationMode } from "@/services/agentProfileService";

const MODE_LABEL: Record<OrchestrationMode, string> = {
  WORKFLOW: "WorkFlow",
  AGENT: "Agent"
};

export const OTHER_MODE_LABEL: Record<OrchestrationMode, string> = {
  WORKFLOW: "Agent",
  AGENT: "WorkFlow"
};

/**
 * 执行架构标识，作为两个智能体页面副标题的前缀
 *
 * <p>它就是一句关于本页的说明，与副标题同层，所以不另起视觉层次也不套容器
 */
export function AgentModeLabel({ mode }: { mode: OrchestrationMode }) {
  return (
    <>
      <span
        className="font-medium text-slate-600"
        title={`由 ragent.engine.type 配置，控制台只读；${OTHER_MODE_LABEL[mode]} 架构的槽位暂不参与运行`}
      >
        {MODE_LABEL[mode]} 架构
      </span>
      <span className="mx-1.5 text-slate-400">·</span>
    </>
  );
}
