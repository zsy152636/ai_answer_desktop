/**
 * 路由表（hash 模式，便于 Electron 以 file:// 加载本地文件）。
 */
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';

import AboutPage from '@/pages/AboutPage.vue';
import DocumentsPage from '@/pages/DocumentsPage.vue';
import LlmSettingsPage from '@/pages/LlmSettingsPage.vue';
import SystemSettingsPage from '@/pages/SystemSettingsPage.vue';
import AttemptListPage from '@/pages/attempts/AttemptListPage.vue';
import AttemptPage from '@/pages/attempts/AttemptPage.vue';
import AttemptResultPage from '@/pages/attempts/AttemptResultPage.vue';
import DashboardPage from '@/pages/dashboard/DashboardPage.vue';
import NotFoundPage from '@/pages/NotFoundPage.vue';
import PaperEditPage from '@/pages/papers/PaperEditPage.vue';
import PaperListPage from '@/pages/papers/PaperListPage.vue';
import QuestionEditPage from '@/pages/questions/QuestionEditPage.vue';
import QuestionListPage from '@/pages/questions/QuestionListPage.vue';
import WrongQuestionsPage from '@/pages/wrongQuestions/WrongQuestionsPage.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'dashboard',
    component: DashboardPage,
    meta: { title: '仪表盘' }
  },
  {
    path: '/questions',
    name: 'questions',
    component: QuestionListPage,
    meta: { title: '题库' }
  },
  {
    path: '/questions/new',
    name: 'question-new',
    component: QuestionEditPage,
    meta: { title: '新建题目' }
  },
  {
    path: '/questions/:id/edit',
    name: 'question-edit',
    component: QuestionEditPage,
    meta: { title: '编辑题目' }
  },
  {
    path: '/papers',
    name: 'papers',
    component: PaperListPage,
    meta: { title: '试卷' }
  },
  {
    path: '/papers/new',
    name: 'paper-new',
    component: PaperEditPage,
    meta: { title: '新建试卷' }
  },
  {
    path: '/papers/:id/edit',
    name: 'paper-edit',
    component: PaperEditPage,
    meta: { title: '编辑试卷' }
  },
  {
    path: '/attempts',
    name: 'attempts',
    component: AttemptListPage,
    meta: { title: '作答记录' }
  },
  {
    path: '/attempts/:id',
    name: 'attempt',
    component: AttemptPage,
    meta: { title: '答题' }
  },
  {
    path: '/attempts/:id/result',
    name: 'attempt-result',
    component: AttemptResultPage,
    meta: { title: '作答结果' }
  },
  {
    path: '/wrong-questions',
    name: 'wrong-questions',
    component: WrongQuestionsPage,
    meta: { title: '错题本' }
  },
  {
    path: '/settings/llm',
    name: 'settings-llm',
    component: LlmSettingsPage,
    meta: { title: 'LLM 设置' }
  },
  {
    path: '/settings/system',
    name: 'settings-system',
    component: SystemSettingsPage,
    meta: { title: '系统设置' }
  },
  {
    path: '/documents',
    name: 'documents',
    component: DocumentsPage,
    meta: { title: '资料库' }
  },
  {
    path: '/about',
    name: 'about',
    component: AboutPage,
    meta: { title: '关于' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundPage,
    meta: { title: '页面不存在' }
  }
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
});

router.afterEach((route) => {
  const title = typeof route.meta.title === 'string' ? route.meta.title : '页面';
  document.title = `${title} · AI 智能答题系统`;
});

export default router;
