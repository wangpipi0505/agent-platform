import { installPermissions } from "../../shared/permission";
import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
// UI-01：Element Plus 暗色基底（html.dark 作用域）须在语义覆盖 tokens.css 之前导入。
import "element-plus/theme-chalk/dark/css-vars.css";
import "../../shared/tokens.css";
// 本应用为自有独立页面：dark 类只加在本应用文档根；嵌入宿主场景由 SDK Shadow DOM 承载。
document.documentElement.classList.add("dark");
import App from "./App.vue";
const app = createApp(App).use(createPinia()).use(ElementPlus);
installPermissions(app);
app.mount("#app");
