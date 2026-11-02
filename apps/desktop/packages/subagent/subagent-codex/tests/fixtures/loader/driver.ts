#!/usr/bin/env node
/** Inspect the public Codex provider composition without invoking the product. */

import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveConfigPath } from '@deepseek-ai/dsh-app-boot'
import type {} from '@deepseek-ai/dsh-subagent'
import type {} from '@deepseek-ai/dsh-tools'
import type {} from '../../../src/index.ts'
import { bootProductionProfile } from '../../../../../test-support/loader-smoke/tests/fixtures/production-profile.ts'

const configPath = process.argv[2]
if (configPath === undefined) {
  throw new Error('subagent-codex Loader composition driver requires a config path')
}

let starts = 0
const ctx = await bootProductionProfile({
  binName: 'subagent-codex-loader-composition',
  profile: 'headless',
  overlayPaths: [
    resolveConfigPath(configPath, undefined),
  ],
  prepare: (hostCtx) => {
    hostCtx.on('subagent/start', () => {
      starts += 1
    })
  },
})

try {
  const providerNames = ['codex', 'codex-primary', 'codex-secondary'] as const
  const toolNames = [
    'subagent_codex',
    'subagent_codex_primary',
    'subagent_codex_secondary',
  ] as const
  const providers = providerNames.map((providerName) => {
    const provider = ctx.subagents.getProvider(providerName)
    if (provider === undefined) {
      throw new Error(`${providerName} provider was not registered`)
    }
    return {
      name: provider.name,
      capabilities: provider.capabilities,
      inheritsParentContext: provider.inheritsParentContext,
    }
  })
  const tools = toolNames.map((toolName) => {
    const tool = ctx.tools.schemas().find(schema => schema.name === toolName)
    if (tool === undefined) throw new Error(`${toolName} tool was not registered`)
    const properties = tool.parameters.properties
    if (
      typeof properties !== 'object'
      || properties === null
      || Array.isArray(properties)
    ) {
      throw new Error(`${toolName} has invalid parameter properties`)
    }
    return {
      name: tool.name,
      parameterNames: Object.keys(properties).sort(),
      required: tool.parameters.required,
    }
  })
  const jobTools = ctx.tools.schemas()
    .map(schema => schema.name)
    .filter(name => name === 'job_kill' || name === 'job_list' || name === 'job_output')
    .sort()

  const maintenance = ctx.get('codexMaintenance')
  if (!maintenance) throw new Error('Maintenance service did not register')
  const workspacePath = await mkdtemp(join(tmpdir(), 'codex-loader-workspace-'))
  let maintenanceWorkspaceResolved = false
  const spawn = ctx.subprocess.spawn.bind(ctx.subprocess)
  try {
    const workspace = await ctx.workspaceRegistry.create(workspacePath)
    ctx.subprocess.spawn = (spec) => {
      maintenanceWorkspaceResolved = spec.cwd === workspace.path
      throw new Error('Composition fixture does not launch a product process')
    }
    const run = maintenance.start({ requestId: 'loader-probe', workspaceId: workspace.id,
      baselineSha: 'a'.repeat(40), runtimeEpoch: 'loader-epoch', timeoutMs: 100, maxOutputBytes: 1024 })
    await maintenance.cancel({ runId: run.runId, runtimeEpoch: run.runtimeEpoch })
  } finally {
    ctx.subprocess.spawn = spawn
    await rm(workspacePath, { recursive: true, force: true })
  }

  process.stdout.write(`${JSON.stringify({
    providers: providerNames.filter(providerName => ctx.subagents.getProvider(providerName) !== undefined),
    providerDetails: providers,
    tools,
    jobTools,
    starts,
    maintenanceRegistered: ctx.get('codexMaintenance') !== undefined,
    maintenanceWorkspaceResolved,
  })}\n`)
} finally {
  await ctx.fiber.dispose()
}
