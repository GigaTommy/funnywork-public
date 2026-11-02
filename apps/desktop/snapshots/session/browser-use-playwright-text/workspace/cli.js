/** Deterministic upstream boundary; this process never launches or inspects a real browser. */
import assert from 'node:assert/strict'
import { createInterface } from 'node:readline'

const args = process.argv.slice(2)
assert.equal(args[args.indexOf('--image-responses') + 1], 'omit')
assert.equal(args[args.indexOf('--snapshot-mode') + 1], 'full')
assert.ok(args.includes('--isolated'))
assert.ok(!args.includes('--headless'))
assert.equal(process.env.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD, '1')
assert.equal(process.env.ELECTRON_RUN_AS_NODE, '1')
assert.equal(process.env.PLAYWRIGHT_MCP_CDP_ENDPOINT, '')
assert.equal(process.env.PLAYWRIGHT_MCP_USER_DATA_DIR, '')
let count = 0
const tool = (name, description, properties = {}, required = []) => ({
  name, description, inputSchema: { type: 'object', properties, required, additionalProperties: false },
})
const tools = [
  tool('browser_snapshot', 'Read the current page Accessibility snapshot.'),
  tool('browser_click', 'Click an exact Accessibility reference.', { element: { type: 'string' }, ref: { type: 'string' } }, ['element', 'ref']),
  tool('browser_take_screenshot', 'Request a screenshot; this text-only connection omits image responses.'),
]
const lines = createInterface({ input: process.stdin })
lines.once('close', () => process.exit(0))
lines.on('line', line => {
  const request = JSON.parse(line)
  if (request.id === undefined) return
  let result
  if (request.method === 'server/discover') {
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32601, message: 'Legacy fixture' } }) + '\n')
    return
  }
  if (request.method === 'initialize') {
    result = { protocolVersion: request.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'browser-text-fixture', version: '1' } }
  } else if (request.method === 'tools/list') result = { tools }
  else if (request.method === 'tools/call') {
    const name = request.params.name
    if (name === 'browser_click') {
      assert.equal(request.params.arguments.ref, 'e1')
      count++
    }
    assert.ok(tools.some(tool => tool.name === name))
    result = { content: [{ type: 'text', text: name === 'browser_take_screenshot'
      ? 'Screenshot captured. Image response omitted by the configured text-only connection.'
      : '- button "Increment" [ref=e1]\n- text "Count: ' + count + '"' }] }
  } else throw new Error('Unexpected browser fixture request: ' + request.method)
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n')
})
