---
name: api-call-pattern
description: API 调用模式最佳实践，包括列表查询、表单提交、数据删除等场景的异步处理规范
---

# Skill: api-call-pattern

## 场景
所有需要与服务端通信的页面：列表查询、提交表单、数据删除、状态更新

## 最佳实践（通用原则）

- 每个 API 调用使用 try/catch 包裹，至少处理 loading 和 error 两种状态
- loading 用布尔 ref 控制，请求前 `true`，finally 中 `false`
- 错误提示用 toast/notification，不静默吞异常
- 列表/分页接口返回格式归一化为 `{ records: [], total: 0 }` 或 `{ list: [], total: 0 }`
- 表单提交成功后刷新列表或跳转

## 最佳实践（Vue 组合式 API）

```ts
import { ref } from 'vue'
import { message } from 'ant-design-vue'

const loading = ref(false)
const error = ref<string | null>(null)

async function fetchData<T>(apiFn: () => Promise<T>): Promise<T | null> {
  loading.value = true
  error.value = null
  try {
    return await apiFn()
  } catch (e: any) {
    error.value = e.message || '请求失败'
    message.error(error.value)
    return null
  } finally {
    loading.value = false
  }
}
```

## 最佳实践（HTML / 原生 JS）

- 使用 `async/await` + try/catch
- loading 状态控制按钮 `disabled` + 显示旋转动画
- 错误统一在页面顶部或 toast 显示

## 约束
- 禁止在组件 mount 时用 `setTimeout` 模拟 API（除非明确说明是 mock）
- API 参数和返回值必须有类型定义（Vue 用 TypeScript）
- 列表页删除后必须重新 fetch 列表，不能仅从 dataSource 中 splice
