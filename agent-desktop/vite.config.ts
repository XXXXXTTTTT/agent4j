import { defineConfig } from 'vite'

/** Electron 的主进程不经 Vite 打包；保留统一配置入口供未来本地资源扩展。 */
export default defineConfig({})
