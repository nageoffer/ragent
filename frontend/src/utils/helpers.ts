import { format } from "date-fns";

export function formatTimestamp(value?: string) {
  if (!value) return "";
  try {
    return format(new Date(value), "MM月dd日 HH:mm");
  } catch {
    return "";
  }
}

export function truncate(text: string, max = 36) {
  if (!text) return "";
  if (text.length <= max) return text;
  return `${text.slice(0, max)}...`;
}

export function buildQuery(params: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") return;
    search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}
