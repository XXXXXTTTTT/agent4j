import type { BrowserWindowConstructorOptions } from 'electron'

export const LOCAL_BACKEND_ORIGIN = 'http://127.0.0.1:8080'

/** 生成桌面窗口的固定安全策略。 */
export function desktopWindowOptions(preloadPath: string): BrowserWindowConstructorOptions {
  return {
    width: 1560,
    height: 980,
    minWidth: 1040,
    minHeight: 680,
    backgroundColor: '#15191f',
    show: false,
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
    },
  }
}

export function isAllowedNavigation(url: string): boolean {
  try {
    return new URL(url).origin === LOCAL_BACKEND_ORIGIN
  } catch {
    return false
  }
}

/** 原生目录内容只允许被可信 Agent4J 工作台请求。 */
export function isAllowedDesktopBridgeOrigin(url: string): boolean {
  return isAllowedNavigation(url)
}

/** 服务离线时显示的无权限连接页面。页面不含桌面桥。 */
export function offlineScreenUrl(detail: string): string {
  const escaped = detail.replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character] ?? character)
  return `data:text/html;charset=utf-8,${encodeURIComponent(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Agent4J Desktop</title><style>body{margin:0;background:#15191f;color:#e8edf2;font:14px system-ui;display:grid;min-height:100vh;place-items:center}.panel{width:min(520px,calc(100vw - 48px));border:1px solid #303844;background:#1c222b;padding:28px}.mark{color:#78d6a3;font:700 12px ui-monospace,monospace}h1{font-size:24px;margin:10px 0}p{color:#aeb9c7;line-height:1.6}code{display:block;background:#10141a;border:1px solid #28303a;padding:10px;color:#d6e1ed;white-space:pre-wrap}</style><main class="panel"><div class="mark">AGENT4J DESKTOP</div><h1>正在等待本地服务</h1><p>${escaped}</p><p>桌面端会自动重试连接。请在项目根目录启动：</p><code>docker compose -f docker-compose.local.yml --env-file .env up -d --build</code></main></html>`)}`
}
