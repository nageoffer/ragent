import { api } from "@/services/api";
import type { User } from "@/types";

export interface LoginResponse extends User {}

export async function login(username: string, password: string) {
  return api.post<LoginResponse>("/auth/login", { username, password });
}

export async function logout() {
  return api.post<void>("/auth/logout");
}
