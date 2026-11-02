import { reactive } from 'vue';
export const user = reactive({
  permissions: new URLSearchParams(location.search).has('readonly') ? ['funnyworker:tasks:read'] : ['*:*:*'],
  roles: ['admin']
});
export const useUserStore = () => user;
