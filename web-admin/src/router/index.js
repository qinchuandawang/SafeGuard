import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../components/Layout.vue'
import Dashboard from '../views/Dashboard.vue'
import Users from '../views/Users.vue'
import Knowledge from '../views/Knowledge.vue'
import Records from '../views/Records.vue'
import Models from '../views/Models.vue'
import Profile from '../views/Profile.vue'
import NotFound from '../views/NotFound.vue'

const routes = [
  { path: '/login', component: Login, meta: { public: true } },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: Dashboard },
      { path: 'users', component: Users },
      { path: 'knowledge', component: Knowledge },
      { path: 'records', component: Records },
      { path: 'models', component: Models },
      { path: 'profile', component: Profile },
    ],
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: NotFound },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('admin_token')
  if (!to.meta.public && !token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.path === '/login' && token) {
    next('/dashboard')
    return
  }
  next()
})

export default router
