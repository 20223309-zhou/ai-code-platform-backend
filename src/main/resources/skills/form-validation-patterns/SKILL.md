---
name: form-validation-patterns
description: 表单校验的完整实现规范，适用于所有包含表单的页面
---

# Skill: form-validation-patterns

## 场景
表单页面、注册/登录、数据提交、配置编辑、多步骤表单

## 最佳实践（Vue + Ant Design Vue）

- 使用 `<a-form>` + `:rules` + `ref="formRef"` 声明式校验
- 表单项必填用 `required: true`，自定义规则用 `validator`
- 异步校验（如用户名唯一性）用 `validator` 返回 `Promise`
- 联动校验（如密码确认）通过 `validator` 内访问 `getFieldValue`
- 提交时 `formRef.value.validate()` 统一触发全部校验
- 错误信息放在表单项下方，不在顶部汇总
- 复杂表单使用 `useForm` 或 `reactive` 管理整个表单状态

## 最佳实践（HTML / 原生 JS）

- 使用 HTML5 内置校验：`required`, `type="email"`, `pattern`, `minlength`
- 配合 `:invalid` / `:valid` CSS 伪类显示实时反馈
- `setCustomValidity()` 覆盖默认错误文案
- JS 侧 `form.checkValidity()` + `reportValidity()` 触发浏览器原生气泡
- 需要自定义样式的错误提示时，用 `classList.add('error')` 控制

## 示例代码片段（Vue）

```vue
<template>
  <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
    <a-form-item label="邮箱" name="email">
      <a-input v-model:value="form.email" />
    </a-form-item>
    <a-form-item label="密码" name="password">
      <a-input-password v-model:value="form.password" />
    </a-form-item>
    <a-form-item label="确认密码" name="confirm">
      <a-input-password v-model:value="form.confirm" />
    </a-form-item>
    <a-button type="primary" @click="onSubmit">提交</a-button>
  </a-form>
</template>

<script setup>
import { reactive, ref } from 'vue'

const formRef = ref()
const form = reactive({ email: '', password: '', confirm: '' })
const rules = {
  email: [{ required: true, type: 'email', message: '请输入有效邮箱' }],
  password: [{ required: true, min: 6, message: '密码至少6位' }],
  confirm: [
    { required: true, message: '请确认密码' },
    {
      validator: (_, value) =>
        value === form.password
          ? Promise.resolve()
          : Promise.reject(new Error('两次密码不一致'))
    }
  ]
}

const onSubmit = async () => {
  try {
    await formRef.value.validate()
    // submit logic
  } catch { /* validation failed */ }
}
</script>
```

## 示例代码片段（原生 HTML）

```html
<form id="myForm">
  <label for="email">邮箱</label>
  <input type="email" id="email" required pattern="[^@]+@[^@]+\.[^@]+" />
  <span class="error-msg"></span>
  <button type="submit">提交</button>
</form>
<script>
  document.getElementById('myForm').addEventListener('submit', (e) => {
    e.preventDefault()
    const form = e.target
    if (!form.checkValidity()) {
      form.reportValidity()
      return
    }
    // submit logic
  })
</script>
```
