import { useAuthStore } from "@/stores/authStore";

export function useAuth() {
  return useAuthStore();
}
