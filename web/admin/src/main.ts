import { installPermissions } from "../../shared/permission";
import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
// UI-01：Element Plus 暗色基底（html.dark 作用域）须在语义覆盖 tokens.css 之前导入。
import "element-plus/theme-chalk/dark/css-vars.css";
import "../../shared/tokens.css";
// 暗夜流光为本平台自有页面主题；dark 类只加在本应用文档根，不触碰业务宿主 html。
document.documentElement.classList.add("dark");
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
