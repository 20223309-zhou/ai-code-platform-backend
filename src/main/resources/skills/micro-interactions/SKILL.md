---
name: micro-interactions
description: 微交互与状态反馈规范，包括骨架屏、加载态、空状态、操作动画
---

# Skill: micro-interactions

## 场景
数据加载中、空数据、操作反馈、页面切换、列表滚动加载

## 最佳实践（加载骨架屏）

- 使用 CSS 渐变动画 `@keyframes shimmer` 实现骨架屏
- 骨架屏形状与目标内容一致（矩形、圆形、文本行）

```css
.skeleton {
  background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
```

## 最佳实践（空状态）

```vue
<a-empty description="暂无数据">
  <template #image>
    <svg><!-- 内联 SVG 空状态图标 --></svg>
  </template>
  <a-button type="primary" @click="onRefresh">刷新</a-button>
</a-empty>
```

## 最佳实践（过渡动画）

- 列表进入/离开使用 `<TransitionGroup>` + `name="list"`
- 页面切换使用 `<transition name="fade" mode="out-in">`
- 卡片 hover 使用 `transform: translateY(-2px)` + `box-shadow` 变化

```css
.fade-enter-active, .fade-leave-active { transition: opacity 0.3s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.list-enter-active, .list-leave-active { transition: all 0.3s; }
.list-enter-from { opacity: 0; transform: translateY(20px); }
.list-leave-to { opacity: 0; transform: translateX(-20px); }
```

## 最佳实践（操作反馈）

- 表单提交成功用 `message.success('操作成功')`
- 失败用 `message.error(error.message)`
- 删除等危险操作使用 `Modal.confirm` 二次确认
- 按钮加载状态用 `:loading="loading"` 防止重复点击

## 约束
- 禁止使用无意义动画（如持续旋转的无用元素）
- 动画时间统一使用 0.2s ~ 0.3s, 太慢或太快都影响体验
- 骨架屏宽度高度接近实际内容，不要全屏铺满
