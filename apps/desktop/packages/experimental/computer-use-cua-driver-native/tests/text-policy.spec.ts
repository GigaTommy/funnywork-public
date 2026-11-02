/** Text-mode observations cannot request images or invent pointer positions. */

import { describe, expect, it } from 'vitest'
import { rejectTextImages, textArguments, textTool, type NativeTool } from '../src/policy.ts'

function native(name: string, properties: Record<string, unknown> = {}): NativeTool {
  return { name, inputSchema: { type: 'object', properties } }
}

describe('Accessibility text tools', () => {
  it('forces a tree-only observation even when the upstream default includes a screenshot', () => {
    const source = native('get_window_state', { pid: {}, window_id: {}, screenshot_out_file: {}, session: {} })
    const tool = textTool(source)!
    expect(textArguments(tool, { pid: 17, window_id: 7 })).toEqual({
      pid: 17, window_id: 7, include_accessibility_tree: true, include_screenshot: false,
    })
    expect(source.inputSchema.properties).toHaveProperty('screenshot_out_file')
    expect(tool.inputSchema.properties).not.toHaveProperty('screenshot_out_file')
    expect(() => textArguments(tool, { pid: 17, window_id: 7, include_screenshot: true })).toThrow('screenshots are disabled')
    expect(() => textArguments(tool, { pid: 17, window_id: 7, include_accessibility_tree: false })).toThrow('cannot disable the tree')
  })

  it.each(['click', 'type_text', 'set_value', 'press_key', 'scroll'])('requires an exact fresh element token for %s', (name) => {
    const tool = textTool(native(name, {
      pid: {}, window_id: {}, element_token: {}, x: {}, y: {}, target: {}, session: {}, delivery_mode: {},
    }))!
    expect(() => textArguments(tool, { pid: 17, window_id: 7 })).toThrow('element_token')
    expect(() => textArguments(tool, { pid: 17, window_id: 7, element_token: 'token', x: 100 })).toThrow('x is unavailable')
    expect(() => textArguments(tool, { pid: 17, window_id: 7, element_token: 'token', session: 'someone-else' })).toThrow('session is unavailable')
    expect(textArguments(tool, { pid: 17, window_id: 7, element_token: 'token' })).toMatchObject({ delivery_mode: 'background' })
    expect(() => textArguments(tool, { pid: 17, window_id: 7, element_token: 'token', delivery_mode: 'foreground' })).toThrow('foreground escalation')
  })

  it('keeps permission checks read-only and omits visual, clipboard, and global tools', () => {
    const tool = textTool(native('check_permissions', { prompt: {}, probe_direct_capture: {} }))!
    expect(textArguments(tool, {})).toEqual({ prompt: false, probe_direct_capture: false })
    expect(() => textArguments(tool, { prompt: true })).toThrow('host application settings')
    for (const name of ['get_desktop_state', 'drag', 'move_cursor', 'clipboard_read', 'clipboard_write', 'browser_prepare']) expect(textTool(native(name))).toBeUndefined()
    expect(() =>{  rejectTextImages({ content: [{ type: 'image', data: 'unexpected' }] }) }).toThrow('unexpected image')
    expect(() =>{  rejectTextImages({ content: [{ type: 'text', text: 'AX tree' }] }) }).not.toThrow()
  })
})
