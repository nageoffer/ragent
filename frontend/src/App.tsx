import { RouterProvider } from "react-router-dom";

import { ErrorBoundary } from "@/components/common/ErrorBoundary";
import { Toast } from "@/components/common/Toast";
import { router } from "@/router";

export default function App() {
  return (
    <ErrorBoundary>
      <RouterProvider router={router} />
      <Toast />
    </ErrorBoundary>
  );
}
