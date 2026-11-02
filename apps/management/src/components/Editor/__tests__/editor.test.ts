// @vitest-environment jsdom
import { expect, it, vi } from 'vitest';
import { computed, createApp, getCurrentInstance, h, nextTick, reactive, ref, watch } from 'vue';
import Editor from '../index.vue';

vi.mock('@/utils/request', () => ({ globalHeaders: () => ({}) }));

it('cleans persisted HTML on load and emits edited HTML through the component model', async () => {
  const container = document.createElement('div');
  document.body.append(container);
  const model = ref(
    '<p>Saved<img src="/chart.png" onloadstart="alert(1)" onerror="alert(2)"></p><form><input name="password" type="password"></form><div class="el-overlay" style="position:fixed;inset:0">Content</div>'
  );
  let saved = '';
  for (const [name, implementation] of Object.entries({ computed, getCurrentInstance, reactive, ref, watch })) {
    vi.stubGlobal(name, implementation);
  }
  const app = createApp({
    render: () =>
      h(Editor, {
        modelValue: model.value,
        type: 'base64',
        'onUpdate:modelValue': (value: string) => {
          saved = value;
        }
      })
  });
  app.component('el-upload', { render: () => null });
  try {
    app.mount(container);
    await vi.waitFor(() => expect(container.querySelector('.ql-editor')).not.toBeNull());
    await nextTick();
    expect(container.querySelector('img')?.hasAttribute('onloadstart')).toBe(false);
    expect(container.querySelector('img')?.hasAttribute('onerror')).toBe(false);
    const editor = container.querySelector('.ql-editor')!;
    expect(editor.querySelector('form,input,button,.el-overlay')).toBeNull();
    expect([...editor.querySelectorAll<HTMLElement>('[style]')].some((element) => element.style.position === 'fixed')).toBe(false);
    const paragraph = container.querySelector('.ql-editor p')!;
    paragraph.textContent = 'Edited content';
    await vi.waitFor(() => expect(saved).toContain('Edited content'));
    model.value = '<p>Reloaded<img src="/new.png" onloadstart="alert(3)"></p>';
    await nextTick();
    expect(container.querySelector('.ql-editor')?.textContent).toContain('Reloaded');
    expect(container.querySelector('img')?.hasAttribute('onloadstart')).toBe(false);
  } finally {
    app.unmount();
    container.remove();
    vi.unstubAllGlobals();
  }
});
