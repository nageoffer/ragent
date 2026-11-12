import { create } from "zustand";

import { getSystemSettings } from "@/services/settingsService";

export type EngineType = "workflow" | "agent";

interface EngineState {
  engineType: EngineType | null;
  loading: boolean;
  initialize: () => Promise<void>;
}

// 只允许在鉴权后的 EngineGate 内触发：未登录查询配置会被拦截器重定向登录页
export const useEngineStore = create<EngineState>((set, get) => ({
  engineType: null,
  loading: false,
  initialize: async () => {
    if (get().engineType || get().loading) return;
    set({ loading: true });
    try {
      const settings = await getSystemSettings();
      const type = settings.engine?.type?.toLowerCase();
      set({ engineType: type === "agent" ? "agent" : "workflow", loading: false });
    } catch (error) {
      console.warn("获取引擎类型失败，回退 workflow", error);
      set({ engineType: "workflow", loading: false });
    }
  }
}));
