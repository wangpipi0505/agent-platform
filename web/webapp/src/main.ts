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
import App from "./App.vue";
const app = createApp(App).use(createPinia()).use(ElementPlus);
installPermissions(app);
app.mount("#app");
