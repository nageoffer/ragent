# 快速测试指南

## 问题：No static resource api/ragent/knowledge-base

### 原因
前端开发环境需要配置代理，将API请求转发到后端服务器。

### 解决方案

已在 `vite.config.ts` 中添加代理配置：

```typescript
server: {
  port: 5173,
  proxy: {
    "/api": {
      target: "http://localhost:8080",
      changeOrigin: true,
      secure: false
    }
  }
}
```

### 测试步骤

#### 1. 确认后端服务运行
```bash
curl http://localhost:8080/api/ragent/knowledge-base
# 应该返回：{"code":"A000001","message":"未登录或登录已过期",...}
# 这说明后端服务正常，只是需要登录
```

#### 2. 重启前端开发服务器
```bash
cd /Users/machen/workspace/nageoffer/ragent/frontend

# 停止旧的服务器（如果有）
pkill -f "vite"

# 启动新的服务器
npm run dev
```

#### 3. 访问前端
打开浏览器访问：http://localhost:5173 或 http://localhost:5174

#### 4. 登录测试
1. 使用管理员账号登录（role='admin'）
2. 点击左侧边栏底部的用户头像
3. 选择"管理后台"
4. 进入知识库管理页面

#### 5. 测试知识库功能
- 点击"新建知识库"
- 填写表单：
  - 名称：测试知识库
  - Embedding模型：text-embedding-v3
  - Collection名称：test_kb
- 点击创建

### 常见问题

**Q: 端口被占用怎么办？**
A: Vite会自动尝试下一个端口（5174、5175...）

**Q: 代理不生效？**
A: 确保重启了开发服务器，配置修改后需要重启

**Q: 后端API返回401？**
A: 正常现象，需要先登录获取token

**Q: 如何创建管理员账号？**
A: 在数据库中手动设置用户的role字段为'admin'：
```sql
UPDATE t_user SET role = 'admin' WHERE username = 'your_username';
```

### API路径说明

| 前端请求 | 代理后 | 后端实际路径 |
|---------|--------|-------------|
| /api/ragent/knowledge-base | http://localhost:8080/api/ragent/knowledge-base | /knowledge-base (context-path已包含/api/ragent) |

### 网络请求检查

打开浏览器开发者工具（F12）-> Network标签，查看请求：

**正常情况：**
- Request URL: http://localhost:5173/api/ragent/knowledge-base
- Status: 200 OK 或 401 Unauthorized（未登录）
- Response: JSON格式数据

**异常情况：**
- Status: 404 Not Found
- Response: "No static resource..."
- 解决：检查代理配置，重启开发服务器

### 当前状态

✅ 后端服务运行中：http://localhost:8080
✅ 前端服务运行中：http://localhost:5174
✅ 代理配置已添加
✅ 可以开始测试

### 下一步

1. 登录系统
2. 进入管理后台
3. 测试知识库CRUD功能
4. 如有问题，查看浏览器控制台和Network标签
