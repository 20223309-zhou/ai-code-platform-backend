---
name: code-audit
description: 生成完成后，检查代码是否符合基础质量指标
---

1. 检查 HTML 是否有未闭合标签
2. 检查 CSS 是否存在重复定义或拼写错误
3. 检查 JS 函数是否有 console.log 残留
4. 发现 3 个以上问题 → 自动修正后重新输出