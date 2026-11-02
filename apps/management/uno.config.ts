import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  presetWebFonts,
  transformerDirectives
} from 'unocss';

export default defineConfig({
  shortcuts: {
    'panel-title':
      'pb-[5px] font-sans leading-[1.1] font-medium text-base text-[#6379bb] border-b border-b-solid border-[var(--el-border-color-light)] mb-5 mt-0'
  },
  theme: {
    colors: {
      primary: 'var(--el-color-primary)',
      primary_dark: 'var(--el-color-primary-light-5)'
    }
  },
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons(),
    presetTypography(),
    presetWebFonts({
      fonts: {}
    })
  ],
  // Variant-group syntax is unused and can rewrite object-property arithmetic in SFC scripts.
  transformers: [transformerDirectives()]
});
