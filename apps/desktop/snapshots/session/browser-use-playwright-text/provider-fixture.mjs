/** Replace only the external MCP executable before the real profile-owned controls activate. */
import { registerHooks } from 'node:module'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

export const name = 'desktop-capabilities-fixture'
export const inject = ['browserUse', 'computerUse', 'agents', 'tools', 'systemPrompt', 'workingDirectory', 'profileContext']

export function apply(ctx) {
  if (!ctx.profileContext.startedBundles.includes('@deepseek-ai/dsh-funnywork-desktop-capabilities')) {
    throw new Error('Desktop snapshot must load the real bundle through the selected profile')
  }
  let replaced = false
  ctx.effect(() => {
    const hooks = registerHooks({
      resolve(specifier, context, nextResolve) {
        if (specifier !== '@playwright/mcp/package.json') return nextResolve(specifier, context)
        replaced = true
        return { url: pathToFileURL(resolve('package.json')).href, shortCircuit: true }
      },
    })
    return () => hooks.deregister()
  }, 'desktop-capabilities-fixture.executable')
  ctx.provide('snapshotBrowserBoundary', true)
  ctx.on('agent/pre-step', (_payload, next) => {
    if (!replaced) throw new Error('Desktop profile did not activate its browser controls through the fixture executable')
    return next()
  })
}
