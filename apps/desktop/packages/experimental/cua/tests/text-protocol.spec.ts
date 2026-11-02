/** Text mode publishes semantic AX operations without image or coordinate escape paths. */

import { expect, it } from 'vitest'
import { Request, TextRequest } from '../src/protocol.ts'

it('accepts a fresh AX snapshot and element index for text-mode clicks and value changes', () => {
  expect(TextRequest.parse({ method: 'getState', params: { appHandle: 'fixture', details: true } }).method).toBe('getState')
  expect(TextRequest.parse({ method: 'click', params: { appHandle: 'fixture', snapshotId: 'fresh', elementIndex: 3 } }).method).toBe('click')
  expect(TextRequest.parse({ method: 'setValue', params: { appHandle: 'fixture', snapshotId: 'fresh', elementIndex: 2, value: '中文' } }).method).toBe('setValue')
})

it.each([
  { method: 'screenshot', params: { appHandle: 'fixture', windowId: 7 } },
  { method: 'click', params: { appHandle: 'fixture', windowId: 7, x: 30, y: 40 } },
  { method: 'click', params: { appHandle: 'fixture', windowId: 7, x: 300, y: 400, relative: true } },
  { method: 'hover', params: { appHandle: 'fixture', snapshotId: 'fresh', elementIndex: 3 } },
  { method: 'paste', params: { appHandle: 'fixture', text: 'clipboard content' } },
])('rejects visual or clipboard operations in text mode: $method', (request) => {
  expect(Request.safeParse(request).success).toBe(true)
  expect(TextRequest.safeParse(request).success).toBe(false)
})
