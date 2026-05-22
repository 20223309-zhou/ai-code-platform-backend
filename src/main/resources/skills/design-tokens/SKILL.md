---
name: design-tokens
description: CSS 变量与设计 Token 体系规范，确保视觉一致性
---

# Skill: design-tokens

## 场景
所有页面生成，确保视觉一致性

## CSS 变量体系

```css
:root {
  /* 颜色 */
  --primary: #4f46e5;
  --primary-light: #818cf8;
  --primary-bg: #eef2ff;
  --success: #22c55e;
  --warning: #f59e0b;
  --danger: #ef4444;
  --bg: #f8fafc;
  --bg-card: #ffffff;
  --text: #1e293b;
  --text-secondary: #64748b;
  --border: #e2e8f0;

  /* 间距 */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 32px;

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;

  /* 阴影 */
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.06);
  --shadow-md: 0 4px 16px rgba(0,0,0,0.08);
  --shadow-lg: 0 8px 32px rgba(0,0,0,0.12);

  /* 字体 */
  --font-sans: 'Inter', system-ui, -apple-system, sans-serif;
  --font-mono: 'JetBrains Mono', monospace;
}
```

## 暗色模式变量

```css
[data-theme="dark"] {
  --bg: #0f172a;
  --bg-card: #1e293b;
  --text: #f1f5f9;
  --text-secondary: #94a3b8;
  --border: #334155;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.3);
}
```

## 约束
- 所有颜色/间距/阴影必须通过 CSS 变量引用，禁止硬编码
- 基础色调不得超过 6 个语义色（primary / success / warning / danger / bg / text）
- 间距使用 4/8/12/16/24/32 体系，不要偏离
