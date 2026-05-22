---
name: table-list-patterns
description: 表格列表页与数据展示的实现规范，包括搜索、分页、批量操作
---

# Skill: table-list-patterns

## 场景
管理后台列表页、数据表格、搜索查询、批量操作、分页展示

## 最佳实践（Vue + Ant Design Vue）

- 使用 `<a-table>` + `:columns` + `:data-source` + `:pagination`
- 分页参数使用响应式对象 `pagination = reactive({ current: 1, pageSize: 10, total: 0 })`
- 搜索表单使用 `<a-form>` 内联布局，`onSearch()` 统一提交并重置 `current` 到第 1 页
- 批量操作使用 `rowSelection = { selectedRowKeys, onChange }` + `selectedRowKeys`
- 表格数据变化时自动回到顶部：`tableRef.value?.scrollTo(0)`
- 异步加载数据使用单独的 `fetchList()` 函数，接收分页/搜索参数
- loading 状态绑定 `<a-table :loading="loading">`

## 约束
- 表格不要使用自定义渲染 `<template #bodyCell>` 做简单文本格式化，尽量用 `customRender`
- 操作列始终固定在右侧 `fixed: 'right'`
- 搜索表单统一放在表格上方，使用 `<a-space>` 排列
- 批量删除使用 `Modal.confirm` 二次确认

## 示例代码片段

```vue
<template>
  <div>
    <a-form layout="inline" :model="searchForm" @finish="onSearch">
      <a-form-item name="keyword">
        <a-input v-model:value="searchForm.keyword" placeholder="搜索关键词" />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit">搜索</a-button>
          <a-button @click="onReset">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>

    <div style="margin-bottom: 12px">
      <a-button type="primary" ghost @click="onAdd">新增</a-button>
      <a-button danger :disabled="!selectedRowKeys.length" @click="onBatchDelete">
        批量删除 ({{ selectedRowKeys.length }})
      </a-button>
    </div>

    <a-table
      ref="tableRef"
      :columns="columns"
      :data-source="list"
      :loading="loading"
      :row-key="(r) => r.id"
      :row-selection="{ selectedRowKeys, onChange: (keys) => selectedRowKeys = keys }"
      :pagination="pagination"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <a-space>
            <a @click="onEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a style="color: red">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'

const list = ref([])
const loading = ref(false)
const selectedRowKeys = ref([])
const searchForm = reactive({ keyword: '' })
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80 },
  { title: '名称', dataIndex: 'name' },
  { title: '状态', dataIndex: 'status' },
  { title: '创建时间', dataIndex: 'createTime' },
  { title: '操作', key: 'action', fixed: 'right', width: 150 }
]

const fetchList = async () => {
  loading.value = true
  try {
    const res = await api.list({ ...searchForm, ...pagination })
    list.value = res.records
    pagination.total = res.total
  } finally {
    loading.value = false
  }
}

const onSearch = () => {
  pagination.current = 1
  fetchList()
}

const onReset = () => {
  searchForm.keyword = ''
  onSearch()
}

const onTableChange = (pag) => {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  fetchList()
}

const onBatchDelete = () => {
  Modal.confirm({
    title: '确认删除所选项目？',
    content: `共 ${selectedRowKeys.value.length} 项将被删除`,
    onOk: async () => {
      await api.batchDelete(selectedRowKeys.value)
      selectedRowKeys.value = []
      fetchList()
    }
  })
}

onMounted(fetchList)
</script>
```
