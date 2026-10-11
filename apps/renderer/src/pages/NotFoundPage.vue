<script setup lang="ts">
/** 404 页面 */
import { ElButton } from 'element-plus';
import { useRouter } from 'vue-router';

import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';

const router = useRouter();
</script>

<template>
  <div class="not-found">
    <PageHeader title="页面不存在" description="请求的页面路径未在渲染层路由表中定义。" />
    <StatePanel
      state="empty"
      title="找不到该页面"
      description="请检查地址是否正确，或从侧边导航进入需要的功能页。"
      action-text="返回仪表盘"
      @action="router.push('/')"
    >
      <template #actions>
        <ElButton type="primary" @click="router.push('/')">返回仪表盘</ElButton>
      </template>
    </StatePanel>
  </div>
</template>

<style scoped>
.not-found {
  max-width: 720px;
  margin: var(--space-10) auto;
}
</style>
