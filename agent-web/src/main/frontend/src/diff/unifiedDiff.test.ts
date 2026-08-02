import { describe, expect, it } from 'vitest'

import { UnifiedDiffError, parseUnifiedDiff } from './unifiedDiff'

describe('Unified Diff 转换', () => {
  it('按 hunk 行类型生成原始文本和修改文本', () => {
    const diff = `diff --git a/src/App.java b/src/App.java
index 3367afd..d68a283 100644
--- a/src/App.java
+++ b/src/App.java
@@ -1,4 +1,4 @@
 class App {
-    String value = "old";
+    String value = "new";
 }
`

    expect(parseUnifiedDiff(diff)).toEqual([
      {
        path: 'src/App.java',
        original: 'class App {\n    String value = "old";\n}\n',
        modified: 'class App {\n    String value = "new";\n}\n',
      },
    ])
  })

  it('保持多文件顺序并处理新增和删除文件路径', () => {
    const diff = `diff --git a/src/Old.java b/src/Old.java
deleted file mode 100644
--- a/src/Old.java
+++ /dev/null
@@ -1 +0,0 @@
-old
diff --git a/src/New.java b/src/New.java
new file mode 100644
--- /dev/null
+++ b/src/New.java
@@ -0,0 +1 @@
+new
`

    expect(parseUnifiedDiff(diff)).toEqual([
      { path: 'src/Old.java', original: 'old\n', modified: '' },
      { path: 'src/New.java', original: '', modified: 'new\n' },
    ])
  })

  it('解析失败时保留原始 Unified Diff', () => {
    const rawDiff = 'not a unified diff'

    expect(() => parseUnifiedDiff(rawDiff)).toThrow(UnifiedDiffError)
    try {
      parseUnifiedDiff(rawDiff)
    } catch (error) {
      expect(error).toBeInstanceOf(UnifiedDiffError)
      expect((error as UnifiedDiffError).rawDiff).toBe(rawDiff)
    }
  })
})
