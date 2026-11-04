import {
  createHostEventBridge,
  exactOriginOf,
  postHostChannelEvent,
  readHostChannelHello,
  type HostChannel,
} from "../../shared/assistant-host-events";

export type HostChannelHandle = {
  /** 会话/对话已展示时调用；只发送一次 opened。 */
  notifyOpened(): void;
  close(): void;
};

/**
 * iframe 最小通道：webapp 作为被嵌入页，只接受父窗口的 hello，
 * 之后用 hello 的精确 origin 回发 ready/opened。绝不使用 "*"，
 * 也不接受无 hello 的“直接事件请求”。
 */
export function connectHostChannel(options: {
  appCode: string;
  integrationId?: string;
  parentWindow?: Window | null;
  referrer?: string;
  windowRef?: Window;
}): HostChannelHandle | null {
  const windowRef = options.windowRef ?? window;
  const parent = options.parentWindow ?? windowRef.parent;
  if (!parent || parent === windowRef) return null;

  const expectedOrigin = exactOriginOf(options.referrer ?? "");
  const bridge = createHostEventBridge({
    appCode: options.appCode,
    integrationId: options.integrationId,
  });
  let channel: HostChannel | null = null;
  let targetOrigin = "";

  const send = (name: "ready" | "opened") => {
    if (!channel) return;
    const event = bridge.emit(name, {});
    if (event) postHostChannelEvent(parent, targetOrigin, event, channel);
  };

  const onMessage = (message: MessageEvent) => {
    if (message.source !== parent) return;
    const origin = exactOriginOf(message.origin);
    if (!origin) return;
    if (expectedOrigin && origin !== expectedOrigin) return;
    const hello = readHostChannelHello(message.data);
    if (!hello.ok) return;
    windowRef.removeEventListener("message", onMessage);
    channel = hello.channel;
    targetOrigin = origin;
    send("ready");
  };

  windowRef.addEventListener("message", onMessage);
  return {
    notifyOpened() {
      send("opened");
    },
    close() {
      windowRef.removeEventListener("message", onMessage);
    },
  };
}
