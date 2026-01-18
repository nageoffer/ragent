import { create } from "zustand";

import { storage } from "@/utils/storage";

export type ThemeMode = "light" | "dark";

interface ThemeState {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  toggleTheme: () => void;
  initialize: () => void;
}

function applyTheme(theme: ThemeMode) {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: "light",
  setTheme: (theme) => {
    storage.setTheme(theme);
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => {
    const next = get().theme === "light" ? "dark" : "light";
    get().setTheme(next);
  },
  initialize: () => {
    const stored = storage.getTheme();
    const theme = stored === "dark" ? "dark" : "light";
    applyTheme(theme);
    set({ theme });
  }
}));
