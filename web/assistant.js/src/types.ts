/** Scalar values allowed in the host page context (contract §5.1). */
export type PageContextValue = string | number | boolean;

/** Page context supplied by the host for one open/update call. */
export type PageContext = Record<string, PageContextValue>;

/** Host-supplied embed options. The ticket callback stays on the host backend. */
export type AssistantOptions = {
  endpoint: string;
  appCode: string;
  ticket: () => Promise<string>;
  context?: PageContext;
  /** Defaults to true: the SDK renders its own floating launcher. */
  autoLauncher?: boolean;
  /** Explicit host element that owns the assistant. Re-init replaces its instance. */
  mount?: HTMLElement;
};

/** Data-only lifecycle events; the event bridge stays an additive step 2 surface. */
export type AssistantEvent = "open" | "close" | "session" | "destroy";

export type AssistantEventPayload = {
  open: { pageContext: PageContext; appCode: string };
  close: { appCode: string };
  session: { sessionId: string; appCode: string };
  destroy: { appCode: string };
};

export type AssistantOpenOptions = {
  pageContext?: PageContext;
};

/** Host lifecycle surface. All methods are additive over the original destroy. */
export type AssistantHandle = {
  open(options?: AssistantOpenOptions): void;
  close(): void;
  updateContext(pageContext: PageContext): void;
  on<E extends AssistantEvent>(
    event: E,
    listener: (payload: AssistantEventPayload[E]) => void,
  ): () => void;
  off<E extends AssistantEvent>(
    event: E,
    listener: (payload: AssistantEventPayload[E]) => void,
  ): void;
  destroy(): void;
};

/** Shadow-DOM handles owned by the widget layer. */
export type Widget = {
  host: HTMLElement;
  root: ShadowRoot;
  drawer: HTMLElement;
  log: HTMLElement;
  error: HTMLElement;
  connection: HTMLElement;
  send: HTMLButtonElement;
  close: HTMLButtonElement;
  input: HTMLTextAreaElement;
  experience: HTMLElement;
  welcome: HTMLElement;
  capabilities: HTMLElement;
  prompts: HTMLElement;
  experienceNote: HTMLElement;
  ball?: HTMLElement;
};

/** A declared input control rendered next to the message box. */
export type ExtraInput = {
  field: any;
  control: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
};
