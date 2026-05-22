---
name: responsive-breakpoints
description: 响应式断点与移动端适配规范，覆盖导航折叠、卡片网格、表单布局
---

# Skill: responsive-breakpoints

## 场景
所有需要适配桌面/平板/手机的页面，尤其是导航栏、卡片网格、表单布局

## 断点体系

| 断点 | 宽度 | 目标设备 |
|------|------|----------|
| xs   | < 576px  | 手机竖屏 |
| sm   | ≥ 576px  | 手机横屏 |
| md   | ≥ 768px  | 平板 |
| lg   | ≥ 992px  | 桌面窄 |
| xl   | ≥ 1200px | 桌面宽 |

## 最佳实践（CSS Media Queries）

```css
/* 移动端优先 */
.grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}

@media (min-width: 768px) {
  .grid { grid-template-columns: repeat(2, 1fr); }
}

@media (min-width: 1200px) {
  .grid { grid-template-columns: repeat(3, 1fr); }
}
```

## 最佳实践（导航栏折叠）

- 桌面端：水平导航栏
- 移动端：汉堡菜单 + 侧边抽屉
- 使用 `matchMedia('(max-width: 768px)')` 监听断点变化

```js
const isMobile = ref(window.matchMedia('(max-width: 768px)').matches)
const mql = window.matchMedia('(max-width: 768px)')
mql.addEventListener('change', (e) => { isMobile.value = e.matches })
```

## 最佳实践（Ant Design Vue 断点）

```vue
<a-row :gutter="[16, 16]">
  <a-col :xs="24" :sm="12" :lg="8" :xl="6">
    <!-- 卡片内容 -->
  </a-col>
</a-row>
```

## 约束
- 不要对所有分辨率使用同一套布局
- 不要在 JS 中写硬编码宽度值，使用 CSS 变量或断点函数
- 移动端交互需考虑触摸友好（至少 44px 点击区域）
