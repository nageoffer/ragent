import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";
import { ChangeLogsPage } from "@/pages/ChangeLogsPage";
import { DocPreviewPage } from "@/pages/DocPreviewPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { DashboardPage } from "@/pages/admin/dashboard/DashboardPage";
import { KnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { KnowledgeGraphPage } from "@/pages/admin/knowledge-graph/KnowledgeGraphPage";
import { BizChangeLogPage } from "@/pages/admin/change-logs/BizChangeLogPage";
import { IntentTreePage } from "@/pages/admin/intent-tree/IntentTreePage";
import { IntentListPage } from "@/pages/admin/intent-tree/IntentListPage";
import { IntentEditPage } from "@/pages/admin/intent-tree/IntentEditPage";
import { IngestionPage } from "@/pages/admin/ingestion/IngestionPage";
import { RagTracePage } from "@/pages/admin/traces/RagTracePage";
import { RagTraceDetailPage } from "@/pages/admin/traces/RagTraceDetailPage";
import { SystemSettingsPage } from "@/pages/admin/settings/SystemSettingsPage";
import { SampleQuestionPage } from "@/pages/admin/sample-questions/SampleQuestionPage";
import { QueryTermMappingPage } from "@/pages/admin/query-term-mapping/QueryTermMappingPage";
import { AgentProfilePage } from "@/pages/admin/agents/AgentProfilePage";
import { AgentPromptPage } from "@/pages/admin/agents/AgentPromptPage";
import { UserListPage } from "@/pages/admin/users/UserListPage";
import { useAuthStore } from "@/stores/authStore";

function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "admin") {
    return <Navigate to="/chat" replace />;
  }

  return children;
}

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return <Navigate to={isAuthenticated ? "/chat" : "/login"} replace />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <HomeRedirect />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/change-logs",
    element: (
      <RequireAuth>
        <ChangeLogsPage />
      </RequireAuth>
    )
  },
  {
    path: "/preview/doc/:docId",
    element: (
      <RequireAuth>
        <DocPreviewPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/admin/dashboard" replace />
      },
      {
        path: "dashboard",
        element: <DashboardPage />
      },
      {
        path: "knowledge",
        element: <KnowledgeListPage />
      },
      {
        path: "knowledge/:kbId",
        element: <KnowledgeDocumentsPage />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <KnowledgeChunksPage />
      },
      {
        path: "knowledge-graph",
        element: <KnowledgeGraphPage />
      },
      {
        path: "intent-tree",
        element: <IntentTreePage />
      },
      {
        path: "intent-list",
        element: <IntentListPage />
      },
      {
        path: "intent-list/:id/edit",
        element: <IntentEditPage />
      },
      {
        path: "ingestion",
        element: <IngestionPage />
      },
      {
        path: "traces",
        element: <RagTracePage />
      },
      {
        path: "traces/:traceId",
        element: <RagTraceDetailPage />
      },
      {
        path: "change-logs",
        element: <BizChangeLogPage />
      },
      {
        path: "settings",
        element: <SystemSettingsPage />
      },
      {
        path: "sample-questions",
        element: <SampleQuestionPage />
      },
      {
        path: "mappings",
        element: <QueryTermMappingPage />
      },
      {
        path: "agents",
        element: <AgentProfilePage />
      },
      {
        path: "agents/:agentId",
        element: <AgentPromptPage />
      },
      {
        path: "users",
        element: <UserListPage />
      }
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
