import { installPermissions } from "../../shared/permission";
import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
// UI-01：Element Plus 暗色基底（html.dark 作用域）须在语义覆盖 tokens.css 之前导入。
import "element-plus/theme-chalk/dark/css-vars.css";
import "../../shared/tokens.css";
// F4-4 亮色切换引导（方案 §7.3"暗色默认，亮色可切"）：偏好持久 localStorage，初始跟随系统；
// 挂载前上屏避免主题闪烁；dark/light 互斥挂类，顶栏开关见 shared/theme.ts useTheme。
import { initTheme } from "../../shared/theme";
initTheme(document);
import PortalShell from "./PortalShell.vue";
import { router } from "./router";
import {portalMode,trustedPortal} from '../../shared/portal-auth';
// Capture the host's explicit provider before mounting, without waiting for its ticket.
const provider=trustedPortal(window as Window & {agentPlatformPortal?:unknown});
let mode:'portal'|'dev'='portal',configurationError='';
try{mode=portalMode(import.meta.env.VITE_AUTH_MODE);}catch(e:any){configurationError=e.message;}
const app = createApp(PortalShell,{provider,mode,configurationError}).use(createPinia()).use(router).use(ElementPlus);
installPermissions(app);
app.mount("#app");
