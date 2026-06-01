# Git 分支工作流（可回滚）

## 每次更新固定流程

1. 拉取主分支最新代码  
   `git checkout main`  
   `git pull origin main`

2. 新建功能分支  
   `git checkout -b feature/<yyyymmdd>-<topic>`

3. 开发与提交  
   `git add -A`  
   `git commit -m "feat: ..."`

4. 推送分支并发起 PR  
   `git push -u origin feature/<yyyymmdd>-<topic>`

5. 合并到 `main`（推荐 Squash）

## 回滚方式

### 回滚单次错误提交

`git revert <commit_sha>`

### 回滚最近一次合并

`git revert -m 1 <merge_commit_sha>`

### 回到历史稳定版本

先切换到 `main`，再执行：

`git reset --hard <stable_commit_sha>`

注意：最后一条是强回滚，推远端前请确认团队一致。

