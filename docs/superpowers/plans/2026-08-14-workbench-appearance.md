# Workbench Appearance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent4J 工作台提供可持久化、可组合且响应式稳定的外观自定义系统。

**Architecture:** 在前端新增纯偏好定义模块和 Provider；Provider 将经过校验的偏好写入根元素数据属性。设置抽屉通过 Provider 修改状态，CSS 变量按模式、预设、字体、密度和圆角分层覆盖现有工作台令牌。

**Tech Stack:** React 19、TypeScript、Vitest、Testing Library、原生 CSS 自定义属性、lucide-react。

---

### Task 1: 外观偏好状态

**Files:**
- Create: `agent-web/src/main/frontend/src/appearance/appearancePreferences.ts`
- Create: `agent-web/src/main/frontend/src/appearance/AppearanceProvider.tsx`
- Test: `agent-web/src/main/frontend/src/appearance/appearancePreferences.test.ts`

- [ ] **Step 1: 编写失败测试**

测试有效存储值会恢复，未知的预设、模式、字体、密度或圆角会回退到 `DEFAULT_APPEARANCE`。

- [ ] **Step 2: 运行失败测试**

Run: `npm run test:run -- appearancePreferences.test.ts --maxWorkers=1`

Expected: FAIL，因为外观模块尚不存在。

- [ ] **Step 3: 实现偏好定义和 Provider**

定义 `AppearancePreferences`、默认值、预设元数据和 `normalizeAppearancePreferences`。Provider 使用 `localStorage` 单一键存取合法 JSON，并向 `document.documentElement` 写入 `data-color-mode`、`data-theme-preset`、`data-ui-font`、`data-ui-density`、`data-ui-radius`。

- [ ] **Step 4: 运行测试验证通过**

Run: `npm run test:run -- appearancePreferences.test.ts --maxWorkers=1`

Expected: PASS。

### Task 2: 外观设置抽屉

**Files:**
- Create: `agent-web/src/main/frontend/src/components/AppearanceSettingsDialog.tsx`
- Test: `agent-web/src/main/frontend/src/components/AppearanceSettingsDialog.test.tsx`
- Modify: `agent-web/src/main/frontend/src/components/ConversationSidebar.tsx`
- Modify: `agent-web/src/main/frontend/src/main.tsx`

- [ ] **Step 1: 编写失败测试**

测试抽屉展示五个预设和四组可访问控制；选择预设会调用 Provider；Escape 与关闭按钮会关闭抽屉。

- [ ] **Step 2: 运行失败测试**

Run: `npm run test:run -- AppearanceSettingsDialog.test.tsx --maxWorkers=1`

Expected: FAIL，因为设置抽屉尚不存在。

- [ ] **Step 3: 实现抽屉与入口**

用 `dialog` 语义、固定遮罩和独立滚动内容实现抽屉。将 `AppearanceProvider` 包裹 `App`，在会话侧栏添加带工具提示的“外观设置”图标按钮。

- [ ] **Step 4: 运行测试验证通过**

Run: `npm run test:run -- AppearanceSettingsDialog.test.tsx ConversationSidebar.test.tsx --maxWorkers=1`

Expected: PASS。

### Task 3: 语义主题令牌和响应式约束

**Files:**
- Modify: `agent-web/src/main/frontend/src/styles.css`
- Modify: `agent-web/src/main/frontend/src/components/TerminalPanel.tsx`
- Test: `agent-web/src/main/frontend/src/components/Workbench.test.tsx`

- [ ] **Step 1: 编写失败测试**

测试工作台存在外观入口和根元素数据属性契约，且抽屉不会取代主工作区的可访问区域。

- [ ] **Step 2: 运行失败测试**

Run: `npm run test:run -- Workbench.test.tsx --maxWorkers=1`

Expected: FAIL，因为外观入口和属性契约尚不存在。

- [ ] **Step 3: 实现 CSS 令牌层**

保留固定视口和面板独立滚动规则，将色彩、文字、圆角和密度替换为令牌。为三种模式、五个预设、三种字体、三种密度和三种圆角定义覆盖层；终端从同一语义令牌读取颜色。

- [ ] **Step 4: 运行回归测试**

Run: `npm run test:run -- --maxWorkers=1`

Expected: PASS。

### Task 4: 生产验收

**Files:**
- Verify: `agent-web/src/main/frontend`

- [ ] **Step 1: 构建前端与应用**

Run: `npm run build`，然后 `mvn -pl agent-web -am package -DskipTests`

Expected: 两个命令均成功。

- [ ] **Step 2: 重建运行服务**

Run: `docker compose -f docker-compose.local.yml --env-file .env up -d --build agent-web`

Expected: `agent-web` 启动，`/actuator/health/readiness` 返回 `UP`。

- [ ] **Step 3: 验证浏览器布局**

在 2048×1017 和窄屏尺寸检查根文档高度、抽屉滚动容器和工作台独立面板滚动容器。

- [ ] **Step 4: 提交**

Run: `git add` 仅包含外观模块、组件、样式和对应测试，然后 `git commit -m "feat(web): add workbench appearance customization"`

Expected: 形成单一外观自定义里程碑提交。
