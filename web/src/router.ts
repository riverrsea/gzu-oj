import { createRouter, createWebHistory } from "vue-router";
import { loadSession, session } from "./stores/session";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/problems" },
    { path: "/login", component: () => import("./views/AuthView.vue"), meta: { public: true } },
    { path: "/problems", component: () => import("./views/ProblemListView.vue"), meta: { public: true } },
    { path: "/problems/:id", component: () => import("./views/WorkspaceView.vue"), meta: { public: true, workspace: true } },
    { path: "/submissions", component: () => import("./views/SubmissionsView.vue") },
    { path: "/practice", component: () => import("./views/PracticeView.vue") },
    { path: "/training", component: () => import("./views/TrainingView.vue") },
    { path: "/shares/timed-papers/:token", component: () => import("./views/TimedShareView.vue"), meta: { public: true } },
    { path: "/admin", component: () => import("./views/AdminView.vue"), meta: { admin: true } },
    { path: "/admin/problems/new", component: () => import("./views/AdminProblemView.vue"), meta: { admin: true } },
    { path: "/admin/workers", component: () => import("./views/AdminWorkerView.vue"), meta: { admin: true } },
  ],
});

router.beforeEach(async (to) => {
  if (!session.ready) await loadSession();
  if (!to.meta.public && !session.user) return { path: "/login", query: { redirect: to.fullPath } };
  if (to.meta.admin && session.user?.role !== "ADMIN") return "/problems";
});

export default router;
