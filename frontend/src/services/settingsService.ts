import { api } from "@/services/api";

export interface SystemSettings {
  engine: {
    type: string;
  };
  backends: {
    storage: {
      type: string;
      kbBucket: string;
      assetBucket: string;
      endpoint?: string | null;
      publicUrl?: string | null;
      region?: string | null;
    };
    vector: {
      type: string;
    };
    keyword: {
      type: string;
      uris?: string | null;
      index?: string | null;
      analyzer?: string | null;
      searchAnalyzer?: string | null;
    };
    graph: {
      type: string;
      baseUrl?: string | null;
      queryMode?: string | null;
      embeddingModel?: string | null;
    };
  };
  rag: {
    default: {
      collectionName: string;
      dimension: number;
      metricType: string;
      sseTimeoutMs: number;
    };
    features: {
      queryRewrite: boolean;
      rerank: boolean;
      citation: boolean;
      contextEnrich: boolean;
      trace: boolean;
    };
    search: {
      defaultTopK: number;
      recallBudget: number;
      scope: {
        minIntentScore: number;
        confidenceThreshold: number;
        supplementRatio: number;
      };
      channels: {
        timeoutMs: number;
        vector: RetrievalChannel;
        keyword: RetrievalChannel;
        graph: RetrievalChannel;
        webSearch: RetrievalChannel & {
          count: number;
          timeoutSeconds: number;
          apiKeyConfigured: boolean;
        };
      };
      fusion: {
        strategy: string;
        rrfK: number;
        rerankCandidateLimit: number;
      };
    };
    rateLimit: {
      global: {
        enabled: boolean;
        maxConcurrent: number;
        maxWaitSeconds: number;
        leaseSeconds: number;
        pollIntervalMs: number;
      };
    };
    memory: {
      historyKeepTurns: number;
      summaryStartTurns: number;
      summaryEnabled: boolean;
      summaryMaxChars: number;
      titleMaxLength: number;
    };
  };
  ai: {
    providers: Record<
      string,
      {
        url: string;
        apiKey?: string | null;
        endpoints: Record<string, string>;
      }
    >;
    selection: {
      failureThreshold: number;
      openDurationMs: number;
    };
    stream: {
      messageChunkSize: number;
    };
    chat: ModelGroup;
    embedding: ModelGroup;
    rerank: ModelGroup;
    vlm?: ModelGroup | null;
  };
  upload: {
    maxFileSize: number;
    maxRequestSize: number;
  };
}

export interface RetrievalChannel {
  enabled: boolean;
  weight: number;
}

export interface ModelGroup {
  defaultModel?: string | null;
  candidates: ModelCandidate[];
  // chat 组档位机制字段，embedding/rerank/vlm 为空
  defaultTier?: string | null;
  deepThinkingTier?: string | null;
  tiers?: Record<string, TierConfig> | null;
}

export interface TierConfig {
  candidates: string[];
  timeoutMs?: number | null;
}

export interface ModelCandidate {
  id: string;
  provider: string;
  model: string;
  url?: string | null;
  dimension?: number | null;
  priority?: number | null;
  enabled?: boolean | null;
  supportsThinking?: boolean | null;
}

export async function getSystemSettings(): Promise<SystemSettings> {
  return api.get<SystemSettings, SystemSettings>("/rag/settings");
}
