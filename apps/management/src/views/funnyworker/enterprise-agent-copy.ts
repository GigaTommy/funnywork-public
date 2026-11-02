const zh = {
  title: '企业 Agent 预设',
  notice:
    '统一发布给 macOS 和 Windows 客户端。后台指令为纯文本，工具能力沿用客户端已安装的标准预设。新版本用于新任务，后台仅保存当前版本，正在运行的任务保留已加载配置直到结束。',
  refresh: '刷新',
  empty: '暂无预设',
  name: '名称',
  zone: '辖区',
  version: '版本',
  enabled: '允许新任务选择',
  yes: '是',
  no: '否',
  actions: '操作',
  edit: '编辑并替换当前版本',
  choose: '请选择',
  description: '说明',
  persona: 'Agent 指令（不支持模板表达式）',
  publishRevision: '发布新版本',
  publish: '创建并发布',
  reset: '清空编辑',
  base: '基础组合：标准预设（跨平台）',
  policy: '客户端策略',
  policyHint: '未配置辖区默认禁止个人预设。多辖区用户只有全部辖区允许时才可使用个人预设；默认预设按辖区名称排序取首个有效配置。',
  default: '新任务默认预设',
  standard: '客户端标准预设',
  personal: '允许个人预设',
  savePolicy: '保存策略',
  published: '已发布，在线客户端通常在下次刷新周期内同步。'
};
const en: typeof zh = {
  title: 'Enterprise Agent presets',
  notice:
    'Publish to macOS and Windows. Instructions are literal text and use the installed standard tool composition. New tasks use current revisions; only the current preset is stored. Running tasks retain their loaded configuration until they finish.',
  refresh: 'Refresh',
  empty: 'No presets',
  name: 'Name',
  zone: 'Zone',
  version: 'Revision',
  enabled: 'Available for new tasks',
  yes: 'Yes',
  no: 'No',
  actions: 'Actions',
  edit: 'Edit and replace current version',
  choose: 'Choose',
  description: 'Description',
  persona: 'Agent instructions (no template expressions)',
  publishRevision: 'Publish revision',
  publish: 'Create and publish',
  reset: 'Clear editor',
  base: 'Base composition: standard (cross-platform)',
  policy: 'Client policy',
  policyHint:
    'Unconfigured zones deny personal presets. All assigned zones must permit personal presets. The first valid default in sorted zone order applies.',
  default: 'Default for new tasks',
  standard: 'Installed standard preset',
  personal: 'Allow personal presets',
  savePolicy: 'Save policy',
  published: 'Published. Online clients normally synchronize during the next refresh cycle.'
};
export function enterpriseAgentCopy(locale: string) {
  return locale.startsWith('en') ? en : zh;
}
