import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { MessageList } from "@/components/chat/MessageList";
import { MainLayout } from "@/components/layout/MainLayout";
import { useChatStore } from "@/stores/chatStore";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    fetchSessions,
    selectSession,
    createSession
  } = useChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);

  React.useEffect(() => {
    let active = true;
    fetchSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [fetchSessions]);

  React.useEffect(() => {
    if (sessionId) {
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    createSession().catch(() => null);
  }, [sessionId, sessionsReady, isCreatingNew, selectSession, createSession]);

  React.useEffect(() => {
    if (currentSessionId && currentSessionId !== sessionId) {
      navigate(`/chat/${currentSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  return (
    <MainLayout>
      <div className="flex h-full flex-col bg-white">
        <div className="flex-1 min-h-0">
          <MessageList
            messages={messages}
            isLoading={isLoading}
            isStreaming={isStreaming}
            sessionKey={currentSessionId}
          />
        </div>
        <div className="relative z-20 bg-white">
          <div className="mx-auto max-w-4xl px-6 py-6">
            <ChatInput />
          </div>
        </div>
      </div>
    </MainLayout>
  );
}
