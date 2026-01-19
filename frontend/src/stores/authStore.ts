// @ts-nocheck
/* eslint-disable */

import { create } from "zustand";
import { toast } from "sonner";

import type { User } from "@/types";
import { getCurrentUser, login as loginRequest, logout as logoutRequest } from "@/services/authService";
import { setAuthToken } from "@/services/api";
import { useChatStore } from "@/stores/chatStore";
import { storage } from "@/utils/storage";

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  checkAuth: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: storage.getUser(),
  token: storage.getToken(),
  isAuthenticated: Boolean(storage.getToken()),
  isLoading: false,
  login: async (username, password) => {
    set({ isLoading: true });
    try {
      const data = await loginRequest(username, password);
      const user = {
        userId: data.userId,
        username: data.username || username,
        role: data.role,
        token: data.token,
        avatar: data.avatar
      };
      storage.setToken(user.token);
      storage.setUser(user);
      setAuthToken(user.token);
      set({ user, token: user.token, isAuthenticated: true });
      get().fetchCurrentUser().catch(() => null);
      useChatStore.getState().cancelGeneration();
      useChatStore.setState({
        sessions: [],
        currentSessionId: null,
        messages: [],
        isLoading: false,
        isStreaming: false,
        isCreatingNew: true,
        deepThinkingEnabled: false,
        thinkingStartAt: null,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      });
      toast.success("登录成功");
    } catch (error) {
      toast.error((error as Error).message || "登录失败");
      throw error;
    } finally {
      set({ isLoading: false });
    }
  },
  logout: async () => {
    try {
      await logoutRequest();
    } catch {
      // Ignore network errors on logout
    }
    useChatStore.getState().cancelGeneration();
    useChatStore.setState({
      sessions: [],
      currentSessionId: null,
      messages: [],
      isLoading: false,
      isStreaming: false,
      isCreatingNew: false,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
    storage.clearAuth();
    setAuthToken(null);
    set({ user: null, token: null, isAuthenticated: false });
    toast.success("已退出登录");
  },
  checkAuth: async () => {
    const token = storage.getToken();
    const user = storage.getUser();
    setAuthToken(token);
    set({ token, user, isAuthenticated: Boolean(token) });
    if (token) {
      await get().fetchCurrentUser();
    }
  },
  fetchCurrentUser: async () => {
    const token = get().token || storage.getToken();
    if (!token) return;
    try {
      const data = await getCurrentUser();
      const nextUser = { ...data, token };
      storage.setUser(nextUser);
      set({ user: nextUser, token, isAuthenticated: true });
    } catch {
      return;
    }
  }
}));
