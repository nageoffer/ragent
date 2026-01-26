import axios from "axios";
import { toast } from "sonner";

import { storage } from "@/utils/storage";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
});

export function setAuthToken(token: string | null) {
  if (token) {
    api.defaults.headers.common.Authorization = token;
  } else {
    delete api.defaults.headers.common.Authorization;
  }
}

api.interceptors.request.use((config) => {
  const token = storage.getToken();
  if (token) {
    config.headers.Authorization = token;
  }
  return config;
});

api.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        const message = payload.message || "请求失败";
        const isAuthExpired = typeof message === "string" && message.includes("未登录");
        if (isAuthExpired) {
          storage.clearAuth();
          if (window.location.pathname !== "/login") {
            window.location.href = "/login";
          }
        }
        return Promise.reject(new Error(message));
      }
      return payload.data;
    }
    return payload;
  },
  (error) => {
    if (error?.response?.status === 401) {
      storage.clearAuth();
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }
    toast.error(error?.message || "网络错误");
    return Promise.reject(error);
  }
);
