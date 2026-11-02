/** Text-only desktop tools retain native Accessibility targets and never request screenshots. */

/** One validated entry from the native driver's JSON tool catalog. */
export interface NativeTool {
  name: string
  description?: string | undefined
  inputSchema: Record<string, unknown>
  outputSchema?: unknown
}

const TEXT_DESCRIPTIONS: Readonly<Record<string, string>> = {
  check_permissions: 'Read the launching host application\'s Accessibility and Screen Recording permission status without requesting permissions or capturing the screen.',
  list_apps: 'Discover running and installed native applications. Select the exact application requested by the user before inspecting its windows.',
  list_windows: 'List windows belonging to one exact process. Use its pid and window_id in a fresh Accessibility observation before acting.',
  launch_app: 'Launch one exact application bundle id in the background. This does not grant permission to inspect or control unrelated applications.',
  get_window_state: 'Read a fresh Accessibility tree and snapshot-bound element tokens for one exact native window. This text mode always includes the tree and disables screenshots. Empty or degraded trees require an explicit limitation report; never guess coordinates.',
  verify_state: 'Verify text, element, or window predicates against fresh Accessibility state in one exact window. Screenshots are disabled.',
  click: 'Invoke the Accessibility action of an element_token from a fresh observation of this exact pid and window_id. Coordinate and global pointer input are unavailable in text mode. Verify the result from a new observation.',
  set_value: 'Set the value of an editable Accessibility element_token from a fresh observation of this exact pid and window_id. Read fresh state to verify the resulting value.',
  type_text: 'Insert text into an observed Accessibility element_token in this exact native window using background delivery. Verify the resulting value from a fresh observation.',
  press_key: 'Send a key to an observed Accessibility element_token in this exact native window using background delivery. Verify the resulting state.',
  scroll: 'Scroll an observed Accessibility element_token in this exact native window using background delivery. No model-supplied coordinates are accepted.',
  invoke_menu: 'Invoke an exact application menu path through Accessibility in one exact pid and window_id. Verify the result from fresh state.',
}

const ELEMENT_TOOLS = new Set(['click', 'set_value', 'type_text', 'press_key', 'scroll'])
const WINDOW_TOOLS = new Set(['get_window_state', 'verify_state', 'invoke_menu', ...ELEMENT_TOOLS])
const HIDDEN_FIELDS = new Set([
  'session', 'target', 'scope', 'x', 'y', 'from_zoom', 'debug_image_out',
  'element_index', 'snapshot_id', 'capture_mode', 'screenshot_out_file', 'max_dimension',
])

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/**
 * Publish the bounded Accessibility vocabulary instead of the driver's visual and global-input tools.
 * @param tool - validated upstream tool entry.
 * @returns a text-mode entry, or undefined for an unavailable text-mode operation.
 */
export function textTool(tool: NativeTool): NativeTool | undefined {
  const description = TEXT_DESCRIPTIONS[tool.name]
  if (description === undefined) return undefined
  const original = record(tool.inputSchema.properties) ? tool.inputSchema.properties : {}
  const properties = Object.fromEntries(Object.entries(original).filter(([key]) => !HIDDEN_FIELDS.has(key)))
  const required = new Set(Array.isArray(tool.inputSchema.required)
    ? tool.inputSchema.required.filter((key): key is string => typeof key === 'string' && key in properties)
    : [])
  if (WINDOW_TOOLS.has(tool.name) || tool.name === 'list_windows') {
    properties.pid = { type: 'integer', minimum: 1, description: 'Exact target process id.' }
    required.add('pid')
  }
  if (WINDOW_TOOLS.has(tool.name)) {
    properties.window_id = { type: 'integer', minimum: 1, description: 'Exact native window id from list_windows.' }
    required.add('window_id')
  }
  if (ELEMENT_TOOLS.has(tool.name)) {
    properties.element_token = { type: 'string', minLength: 1, description: 'Opaque element_token from a fresh get_window_state observation of this exact window.' }
    required.add('element_token')
  }
  if ('delivery_mode' in properties) properties.delivery_mode = { type: 'string', const: 'background', default: 'background' }
  if (tool.name === 'click') {
    delete properties.count
    delete properties.modifier
    if ('button' in properties) properties.button = { type: 'string', enum: ['left', 'right'] }
  }
  if (tool.name === 'launch_app') {
    for (const key of Object.keys(properties)) {
      if (key !== 'bundle_id' && key !== 'creates_new_application_instance') Reflect.deleteProperty(properties, key)
    }
    required.add('bundle_id')
  }
  if (tool.name === 'get_window_state') properties.include_accessibility_tree = { type: 'boolean', const: true, default: true }
  if (tool.name === 'get_window_state' || tool.name === 'verify_state') properties.include_screenshot = { type: 'boolean', const: false, default: false }
  if (tool.name === 'check_permissions') {
    properties.prompt = { type: 'boolean', const: false, default: false }
    properties.probe_direct_capture = { type: 'boolean', const: false, default: false }
  }
  return {
    ...tool,
    description,
    inputSchema: { type: 'object', additionalProperties: false, properties, required: [...required] },
  }
}

/**
 * Enforce text-mode restrictions at the tool JSON boundary and resolve native defaults explicitly.
 * @param tool - the published text-mode entry.
 * @param args - model-provided arguments; hidden sessions and pixel targets are rejected.
 * @returns detached arguments with screenshots disabled and background delivery selected.
 */
export function textArguments(tool: NativeTool, args: Record<string, unknown>): Record<string, unknown> {
  const properties = record(tool.inputSchema.properties) ? tool.inputSchema.properties : {}
  for (const key of Object.keys(args)) {
    if (!(key in properties)) throw new Error(`COMPUTER_TEXT_INPUT: ${key} is unavailable in Accessibility text mode`)
  }
  for (const key of ['pid', 'window_id']) {
    if (Array.isArray(tool.inputSchema.required) && tool.inputSchema.required.includes(key)
      && (!Number.isSafeInteger(args[key]) || Number(args[key]) < 1)) {
      throw new Error(`COMPUTER_TEXT_TARGET: ${key} must identify one exact running window`)
    }
  }
  if (ELEMENT_TOOLS.has(tool.name) && (typeof args.element_token !== 'string' || args.element_token.length === 0)) {
    throw new Error('COMPUTER_TEXT_TARGET: an element_token from a fresh window observation is required')
  }
  const resolved = { ...args }
  if (tool.name === 'get_window_state' || tool.name === 'verify_state') {
    if (args.include_screenshot !== undefined && args.include_screenshot !== false) throw new Error('COMPUTER_TEXT_IMAGE: screenshots are disabled in Accessibility text mode')
    resolved.include_screenshot = false
  }
  if (tool.name === 'get_window_state') {
    if (args.include_accessibility_tree !== undefined && args.include_accessibility_tree !== true) throw new Error('COMPUTER_TEXT_TREE: Accessibility observations cannot disable the tree')
    resolved.include_accessibility_tree = true
  }
  if ('delivery_mode' in properties) {
    if (args.delivery_mode !== undefined && args.delivery_mode !== 'background') throw new Error('COMPUTER_TEXT_DELIVERY: foreground escalation requires a different host configuration')
    resolved.delivery_mode = 'background'
  }
  if (tool.name === 'check_permissions') {
    if (args.prompt === true || args.probe_direct_capture === true) throw new Error('COMPUTER_PERMISSION_SETUP: grant OS permissions through the host application settings')
    resolved.prompt = false
    resolved.probe_direct_capture = false
  }
  if (tool.name === 'click' && args.button !== undefined && args.button !== 'left' && args.button !== 'right') {
    throw new Error('COMPUTER_TEXT_INPUT: only Accessibility left and right clicks are available')
  }
  return resolved
}

/**
 * Reject native image output before the MCP adapter admits it to a text-only model.
 * @param result - untrusted parsed native MCP response.
 */
export function rejectTextImages(result: unknown): void {
  if (record(result) && Array.isArray(result.content) && result.content.some(block => record(block) && block.type === 'image')) {
    throw new Error('COMPUTER_TEXT_IMAGE: the native driver returned an unexpected image; no screenshot was admitted')
  }
}
