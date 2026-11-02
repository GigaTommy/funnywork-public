<template>
  <el-config-provider :locale="appStore.locale" :size="appStore.size">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store/modules/settings';
import { handleThemeStyle } from '@/utils/theme';
import { useAppStore } from '@/store/modules/app';

// Initialize appearance on public routes too; the settings drawer is not mounted at login.
useDark({ storageKey: 'useDarkKey', valueDark: 'dark', valueLight: 'light' });
const appStore = useAppStore();
const settingsStore = useSettingsStore();
useMutationObserver(document.documentElement, () => handleThemeStyle(settingsStore.theme), { attributes: true, attributeFilter: ['class'] });

onMounted(() => {
  nextTick(() => {
    // 初始化主题样式
    handleThemeStyle(useSettingsStore().theme);
  });
});
</script>
