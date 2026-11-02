<template>
  <div class="business-sidebar" :class="[sideTheme, { 'has-logo': showLogo }]" :style="{ backgroundColor: bgColor }">
    <logo v-if="showLogo" :collapse="isCollapse" />
    <div class="sidebar-navigation">
      <transition :enter-active-class="proxy?.animate.menuSearchAnimate.enter" mode="out-in">
        <el-menu
          ref="menuInstance"
          class="business-menu"
          :default-active="activeMenu"
          :collapse="isCollapse"
          :background-color="bgColor"
          :text-color="textColor"
          :unique-opened="true"
          :active-text-color="theme"
          :collapse-transition="false"
          :popper-offset="12"
          mode="vertical"
        >
          <el-scrollbar class="business-menu-scroll" wrap-class="scrollbar-wrapper">
            <sidebar-item v-for="r in primaryRouters" :key="r.path" :item="r" :base-path="r.path" />
          </el-scrollbar>
          <div v-if="settingsRouter" class="sidebar-settings">
            <sidebar-item :item="settingsRouter" :base-path="settingsRouter.path" />
          </div>
        </el-menu>
      </transition>
    </div>
  </div>
</template>

<script setup lang="ts">
import Logo from './Logo.vue';
import SidebarItem from './SidebarItem.vue';
import variables from '@/assets/styles/variables.module.scss';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { RouteRecordRaw } from 'vue-router';
import type { MenuInstance } from 'element-plus';
import {businessNavigation} from '@/utils/business-navigation';

const menuInstance=ref<MenuInstance>();
provide('funnywork-sidebar-menu',menuInstance);

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const route = useRoute();
const appStore = useAppStore();
const settingsStore = useSettingsStore();
const permissionStore = usePermissionStore();
const sidebarRouters = computed<RouteRecordRaw[]>(() => businessNavigation(permissionStore.getSidebarRoutes()));
const primaryRouters = computed(() => sidebarRouters.value.filter(route => route.path !== '/_navigation/settings'));
const settingsRouter = computed(() => sidebarRouters.value.find(route => route.path === '/_navigation/settings'));
const showLogo = computed(() => settingsStore.sidebarLogo);
const sideTheme = computed(() => settingsStore.sideTheme);
const theme = computed(() => settingsStore.theme);
const isCollapse = computed(() => !appStore.sidebar.opened);

const activeMenu = computed(() => {
  const { meta, path } = route;
  // if set path, the sidebar will highlight the path you set
  if (meta.activeMenu) {
    return meta.activeMenu;
  }
  return path;
});

const bgColor = computed(() => (sideTheme.value === 'theme-dark' ? variables.menuBackground : variables.menuLightBackground));
const textColor = computed(() => (sideTheme.value === 'theme-dark' ? variables.menuColor : variables.menuLightColor));
</script>
<style scoped lang="scss">
.business-sidebar { display: flex; flex-direction: column; }
.sidebar-navigation { flex: 1; min-height: 0; }
.business-menu { display: flex; flex-direction: column; }
// Only the business destinations scroll; system settings stay at the bottom.
.business-sidebar .business-menu-scroll { flex: 1; min-height: 0; height: auto !important; }
.sidebar-settings { flex: none; max-height: 45%; overflow-y: auto; overflow-x: hidden; border-top: 1px solid var(--el-border-color-light); }
</style>
