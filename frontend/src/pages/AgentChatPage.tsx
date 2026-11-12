import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { AgentChatInput } from "@/components/agent/AgentChatInput";
import { AgentLayout } from "@/components/agent/AgentLayout";
import { AgentMessageList } from "@/components/agent/AgentMessageList";
import { useAgentChatStore } from "@/stores/agentChatStore";

export function AgentChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    loadSessions,
    loadMessages,
    startNewChat
  } = useAgentChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  React.useEffect(() => {
    let active = true;
    loadSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [loadSessions]);

  React.useEffect(() => {
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        startNewChat();
        navigate("/chat", { replace: true });
        return;
      }
      loadMessages(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    startNewChat();
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    loadMessages,
    startNewChat,
    navigate
  ]);

  // 新会话在 meta 事件产生 conversationId 后同步 URL
  React.useEffect(() => {
    if (currentSessionId && !sessionId) {
      navigate(`/chat/${currentSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  // agent-main 网格两行：事件流占满 输入条贴底
  return (
    <AgentLayout>
      <AgentMessageList
        messages={messages}
        isLoading={isLoading}
        isStreaming={isStreaming}
        sessionKey={currentSessionId}
      />
      <AgentChatInput />
    </AgentLayout>
  );
}
