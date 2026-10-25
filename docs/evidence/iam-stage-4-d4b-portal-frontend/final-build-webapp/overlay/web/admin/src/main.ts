import { installPermissions } from "../../shared/permission";
import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "../../shared/tokens.css";
import PortalShell from "./PortalShell.vue";
import {portalMode,trustedPortal} from '../../shared/portal-auth';
// Capture the host's explicit provider before mounting, without waiting for its ticket.
const provider=trustedPortal(window as Window & {agentPlatformPortal?:unknown});
let mode:'portal'|'dev'='portal',configurationError='';
try{mode=portalMode(import.meta.env.VITE_AUTH_MODE);}catch(e:any){configurationError=e.message;}
const app = createApp(PortalShell,{provider,mode,configurationError}).use(createPinia()).use(ElementPlus);
installPermissions(app);
app.mount("#app");
