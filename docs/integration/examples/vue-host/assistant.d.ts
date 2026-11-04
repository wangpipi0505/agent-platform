/**
 * 最小全局声明：仅为示例可读性。
 * 宿主工程建议从平台交付的类型（或 docs/integration/versions.md 的公开 API）生成正式声明，
 * 不要把这里的宽松类型当成契约。
 */
export {};

declare global {
  interface AssistantPageContext {
    [key: string]: string | number | boolean;
  }

  interface AssistantEventEnvelope {
    schemaVersion: string;
    event: string;
    integrationId: string;
    appCode: string;
    traceId: string;
    occurredAt: string;
    runId?: string;
    status?: string;
    structured?: { answer?: string; businessEffect?: string };
    businessRecordRefs?: Array<{ source: string; recordId: string }>;
    error?: { code?: string; message?: string };
  }

  interface AssistantInstance {
    open(options?: { pageContext?: AssistantPageContext }): void;
    close(): void;
    updateContext(context: AssistantPageContext): void;
    onHostEvent(listener: (event: AssistantEventEnvelope) => void): () => void;
    destroy(): void;
  }

  interface AssistantGlobal {
    init(options: {
      endpoint: string;
      appCode: string;
      ticket: () => Promise<string>;
      integrationId?: string;
      context?: AssistantPageContext;
      autoLauncher?: boolean;
      mount?: HTMLElement;
      onHostEvent?: (event: AssistantEventEnvelope) => void;
    }): AssistantInstance;
  }

  interface Window {
    AgentPlatformAssistant?: AssistantGlobal;
    AIAssistant?: AssistantGlobal;
  }
}
