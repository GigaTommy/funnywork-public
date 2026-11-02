<template>
  <div v-if="!item.hidden">
    <template v-if="hasOneShowingChild(item, item.children) && (!onlyOneChild.children || onlyOneChild.noShowingChildren) && !item.alwaysShow">
      <app-link v-if="onlyOneChild.meta" :to="resolvePath(onlyOneChild.path, onlyOneChild.query)" @keydown.esc.prevent.stop="onBack?.()">
        <el-menu-item :index="resolvePath(onlyOneChild.path)" :aria-label="onlyOneChild.meta.title" :class="{ 'submenu-title-noDropdown': !isNest }">
          <svg-icon :icon-class="onlyOneChild.meta.icon || (item.meta && item.meta.icon)" />
          <template #title>
            <span class="menu-title" :title="hasTitle(onlyOneChild.meta.title)">{{ onlyOneChild.meta.title }}</span>
          </template>
        </el-menu-item>
      </app-link>
    </template>

    <el-sub-menu v-else ref="subMenu" :index="resolvePath(item.path)" :aria-label="item.meta?.title" tabindex="0" teleported
      @keydown.enter.self.prevent.stop="toggleSubMenu" @keydown.space.self.prevent.stop="toggleSubMenu"
      @keydown.right.self.prevent.stop="openSubMenu" @keydown.esc.self.prevent.stop="closeSubMenu">
      <template v-if="item.meta" #title>
        <svg-icon :icon-class="item.meta ? item.meta.icon : ''" />
        <span class="menu-title" :title="hasTitle(item.meta?.title)">{{ item.meta?.title }}</span>
      </template>

      <sidebar-item
        v-for="(child, index) in item.children"
        :key="child.path + index"
        :is-nest="true"
        :item="child"
        :base-path="resolvePath(child.path)"
        :on-back="closeSubMenu"
        class="nest-menu"
      />
    </el-sub-menu>
  </div>
</template>

<script setup lang="ts">
import { isExternal } from '@/utils/validate';
import AppLink from './Link.vue';
import { getNormalPath } from '@/utils/ruoyi';
import { RouteRecordRaw } from 'vue-router';
import type { MenuInstance, SubMenuInstance } from 'element-plus';

const props = defineProps({
  item: {
    type: Object as PropType<RouteRecordRaw>,
    required: true
  },
  isNest: {
    type: Boolean,
    default: false
  },
  basePath: {
    type: String,
    default: ''
  },
  onBack: {
    type: Function as PropType<()=>void>,
    default: undefined
  }
});

const onlyOneChild = ref<any>({});
const menu=inject<Ref<MenuInstance|undefined>>('funnywork-sidebar-menu');
const subMenu=ref<SubMenuInstance>();

async function openSubMenu(){
  menu?.value?.open(resolvePath(props.item.path));
  await nextTick();
  const root=subMenu.value?.$el as HTMLElement|undefined;
  const trigger=root?.querySelector('.el-sub-menu__title');
  const popupId=trigger?.getAttribute('aria-describedby')||root?.getAttribute('aria-describedby');
  const popup=popupId?document.getElementById(popupId):null;
  const first=(root?.querySelector('ul a[href]')||popup?.querySelector('a[href]')) as HTMLElement|undefined;
  first?.focus();
}
function closeSubMenu(){
  menu?.value?.close(resolvePath(props.item.path));
  void nextTick(()=>{(subMenu.value?.$el as HTMLElement|undefined)?.focus();});
}
function toggleSubMenu(){
  const root=subMenu.value?.$el as HTMLElement|undefined;
  if(root?.getAttribute('aria-expanded')==='true')closeSubMenu();else void openSubMenu();
}

const hasOneShowingChild = (parent: RouteRecordRaw, children?: RouteRecordRaw[]) => {
  if (!children) {
    children = [];
  }
  const showingChildren = children.filter((item) => {
    if (item.hidden) {
      return false;
    }
    onlyOneChild.value = item;
    return true;
  });

  // When there is only one child router, the child router is displayed by default
  if (showingChildren.length === 1) {
    return true;
  }

  // Show parent if there are no child router to display
  if (showingChildren.length === 0) {
    onlyOneChild.value = { ...parent, path: '', noShowingChildren: true };
    return true;
  }

  return false;
};

const resolvePath = (routePath: string, routeQuery?: string): any => {
  if (isExternal(routePath)) {
    return routePath;
  }
  if (isExternal(props.basePath as string)) {
    return props.basePath;
  }
  if (routeQuery) {
    const query = JSON.parse(routeQuery);
    return { path: routePath.startsWith('/') ? routePath : getNormalPath(props.basePath + '/' + routePath), query: query };
  }
  if(routePath.startsWith('/'))return routePath;
  return getNormalPath(props.basePath + '/' + routePath);
};

const hasTitle = (title: string | undefined): string => {
  if (!title || title.length <= 5) {
    return '';
  }
  return title;
};
</script>
<style scoped>
.el-sub-menu:focus-visible{outline:2px solid var(--fw-accent);outline-offset:-2px;border-radius:4px}
</style>
