/** Host-supplied embed options. The ticket callback stays on the host backend. */
export type AssistantOptions = {
  endpoint: string;
  appCode: string;
  ticket: () => Promise<string>;
  context?: Record<string, unknown>;
};

/** The only lifecycle surface this structural step exposes to a host page. */
export type AssistantHandle = {
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
  input: HTMLTextAreaElement;
};

/** A declared input control rendered next to the message box. */
export type ExtraInput = {
  field: any;
  control: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
};
