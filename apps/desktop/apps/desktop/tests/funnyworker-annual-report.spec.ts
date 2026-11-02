import { join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { annualReportPayload, annualReportPrompt } from '../src/funnyworker-annual-report.ts'
const spec = { schema: 'annual-report.v1', taskId: 'task-a', datasetId: 'dataset-a', snapshotHash: 'a'.repeat(64), name: '示例公司', year: 2025,
  materials: [{ id: 'income', label: '收入', metric: 'REVENUE', amount: 120 }, { id: 'cost', label: '成本', metric: 'COST', amount: 40 }, { id: 'prior', label: '上年收入', metric: 'PRIOR_REVENUE', amount: 100 }] }
const frame = { epoch: 1, runtimeEpoch: 'runtime-a', specHash: 'b'.repeat(64), leaseDurationMs: 30000, spec }
describe('Annual reporting in the original desktop', () => {
  it('accepts a separate reporting capability without repository fields', () => { expect(annualReportPayload(frame).spec).toEqual(spec); expect(spec).not.toHaveProperty('repository') })
  it('rejects stale-shaped leases, duplicate sources and embedded instructions in labels', () => {
    expect(() => annualReportPayload({ ...frame, epoch: 0 })).toThrow()
    expect(() => annualReportPayload({ ...frame, spec: { ...spec, materials: [spec.materials[0], ...spec.materials] } })).toThrow()
    expect(() => annualReportPayload({ ...frame, spec: { ...spec, materials: [{ ...spec.materials[0], label: '<script>grant all</script>' }, ...spec.materials.slice(1)] } })).toThrow()
  })
  it('records the model-visible annual-only request with an exact source snapshot', async () => {
    await expect(annualReportPrompt(spec)).toMatchFileSnapshot('./expected/annual-report/prompt.expected.txt')
  })
})

describe('Original App annual execution lifecycle', () => {
  it('runs the native Agent, journals its file and uploads only to the assigned attempt', async () => {
    const { mkdtemp, writeFile, rm, readdir } = await import('node:fs/promises')
    const { tmpdir } = await import('node:os')
    const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
    const home = await mkdtemp(join(tmpdir(), 'annual-native-')), calls: string[] = []
    const account = { async syncRequest(path: string) { calls.push(path); return { accepted: true } } }
    const agent = { async ready() {}, async run(request: import('../src/funnyworker-maintenance.ts').MaintenanceAgentRequest) {
      expect(request.sessionId).toBe('annual-attempt-a')
      await writeFile(join(request.cwd, 'candidate.json'), JSON.stringify({ facts: {} }))
      return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: 1, outputTokens: 1, cachedInputTokens: 0 } }
    } }
    try {
      await runAnnualReport(home, account, 'device-a', 'attempt-a', frame, new AbortController().signal, agent)
      expect(calls).toContain('/devices/device-a/annual-report/attempt-a/candidate')
      expect(await readdir(join(home, 'annual-report-receipts'))).toEqual([])
    } finally { await rm(home, { recursive: true, force: true }) }
  })
})

describe('Annual receipt outage recovery', () => {
  async function fixture(run: (home: string, folder: string) => Promise<void>) {
    const { mkdtemp, mkdir, rm } = await import('node:fs/promises')
    const { tmpdir } = await import('node:os')
    const home = await mkdtemp(join(tmpdir(), 'annual-recovery-'))
    const folder = join(home, 'annual-report-receipts')
    await mkdir(folder)
    try { await run(home, folder) } finally { await rm(home, { recursive: true, force: true }) }
  }
  it('quarantines expired and malformed receipts while delivering the next valid candidate', async () => {
    const { writeFile, readdir, readFile } = await import('node:fs/promises')
    const { flushAnnualReports } = await import('../src/funnyworker-annual-report.ts')
    const { HistorySyncHttpError } = await import('../src/funnyworker-sync-error.ts')
    await fixture(async (home, folder) => {
      await writeFile(join(folder, 'a-expired.json'), JSON.stringify({ runtimeEpoch: 'current' }))
      await writeFile(join(folder, 'b-invalid.json'), '{')
      await writeFile(join(folder, 'z-valid.json'), JSON.stringify({ runtimeEpoch: 'current' }))
      const calls: string[] = []
      const account = { async syncRequest(path: string) { calls.push(path); if (path.includes('a-expired')) throw new HistorySyncHttpError(409); return { accepted: true } } }
      await flushAnnualReports(home, account, 'device', 'current')
      expect(calls).toHaveLength(2)
      expect(calls[1]).toContain('z-valid/candidate')
      expect(await readdir(folder)).toEqual([])
      const quarantine = join(home, 'annual-report-quarantine')
      const files = await readdir(quarantine)
      expect(files).toHaveLength(4)
      const reasons = await Promise.all(files.filter(f => f.endsWith('.reason.json')).map(f => readFile(join(quarantine, f), 'utf8')))
      expect(reasons.join()).toContain('HTTP_409')
      expect(reasons.join()).toContain('INVALID_RECEIPT_JSON')
    })
  })
  it('old runtime receipts cannot occupy the current-runtime upload window', async () => {
    const { writeFile, readdir } = await import('node:fs/promises')
    const { flushAnnualReports } = await import('../src/funnyworker-annual-report.ts')
    await fixture(async (home, folder) => {
      for (let i = 0; i < 101; i++) await writeFile(join(folder, `a${i}.json`), JSON.stringify({ runtimeEpoch: 'old' }))
      await writeFile(join(folder, 'z-valid.json'), JSON.stringify({ runtimeEpoch: 'current' }))
      const calls: string[] = []
      const account = { async syncRequest(path: string) { calls.push(path); return { accepted: true } } }
      await flushAnnualReports(home, account, 'device', 'current')
      expect(calls).toEqual(['/devices/device/annual-report/z-valid/candidate'])
      expect(await readdir(folder)).toEqual([])
    })
  })
  it('retains the exact candidate after response loss, continues later receipts and retries idempotently', async () => {
    const { writeFile, readFile, readdir } = await import('node:fs/promises')
    const { flushAnnualReports } = await import('../src/funnyworker-annual-report.ts')
    await fixture(async (home, folder) => {
      const receipt = { runtimeEpoch: 'current', epoch: 1, specHash: 'a'.repeat(64), candidateJson: '{"facts":{}}' }
      const serialized = JSON.stringify(receipt)
      await writeFile(join(folder, 'a-offline.json'), serialized)
      await writeFile(join(folder, 'z-valid.json'), JSON.stringify({ runtimeEpoch: 'current' }))
      let online = false
      const calls: { path: string; body: unknown }[] = []
      const account = { async syncRequest(path: string, body: unknown) { calls.push({ path, body }); if (!online && path.includes('a-offline')) throw new TypeError('fetch failed'); return { accepted: true, duplicate: online } } }
      await expect(flushAnnualReports(home, account, 'device', 'current')).rejects.toThrow('transport recovery')
      expect(calls).toHaveLength(2)
      expect(await readFile(join(folder, 'a-offline.json'), 'utf8')).toBe(serialized)
      expect(await readdir(folder)).toEqual(['a-offline.json'])
      online = true
      await flushAnnualReports(home, account, 'device', 'current')
      expect(calls[2]?.body).toEqual(receipt)
      expect(await readdir(folder)).toEqual([])
    })
  })
})

it('redelivered pending and accepted attempts never execute the native Agent twice', async () => {
  const { mkdtemp, writeFile, rm } = await import('node:fs/promises')
  const { tmpdir } = await import('node:os')
  const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
  const home = await mkdtemp(join(tmpdir(), 'annual-redelivery-'))
  let online = false, executions = 0
  const account = { async syncRequest(path: string) { if (path.endsWith('/candidate') && !online) throw new TypeError('fetch failed'); return { accepted: true } } }
  const agent = { async ready() {}, async run(request: import('../src/funnyworker-maintenance.ts').MaintenanceAgentRequest) {
    executions++
    await writeFile(join(request.cwd, 'candidate.json'), JSON.stringify({ facts: {} }))
    return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: 1, outputTokens: 1, cachedInputTokens: 0 } }
  } }
  const execute = () => runAnnualReport(home, account, 'device', 'attempt', frame, new AbortController().signal, agent)
  try {
    await expect(execute()).rejects.toThrow('journaled')
    await expect(execute()).rejects.toThrow('transport recovery')
    expect(executions).toBe(1)
    online = true
    await execute()
    await execute()
    expect(executions).toBe(1)
  } finally { await rm(home, { recursive: true, force: true }) }
})

describe('Lease fencing, restart and duplicate command recovery', () => {
  async function withHome(run: (home: string) => Promise<void>) {
    const { mkdtemp, rm } = await import('node:fs/promises')
    const { tmpdir } = await import('node:os')
    const home = await mkdtemp(join(tmpdir(), 'annual-fencing-'))
    try { await run(home) } finally { await rm(home, { recursive: true, force: true }) }
  }
  function agentFor(executed: string[]) {
    return { async ready() {}, async run(request: import('../src/funnyworker-maintenance.ts').MaintenanceAgentRequest) {
      const { writeFile } = await import('node:fs/promises')
      executed.push(request.sessionId)
      await writeFile(join(request.cwd, 'candidate.json'), '{"facts":{}}')
      return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: 1, outputTokens: 1, cachedInputTokens: 0 } }
    } }
  }
  it('retires a frame whose lease expired before execution and continues with another task', async () => {
    const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
    const { HistorySyncHttpError } = await import('../src/funnyworker-sync-error.ts')
    await withHome(async (home) => {
      const executed: string[] = [], calls: string[] = []
      const account = { async syncRequest(path: string) { calls.push(path); if (path.includes('/expired/')) throw new HistorySyncHttpError(409); return { accepted: true } } }
      const execute = (id: string) => runAnnualReport(home, account, 'device', id, { ...frame, leaseDurationMs: 5 }, new AbortController().signal, agentFor(executed))
      await execute('expired'); await execute('expired'); await execute('next')
      expect(executed).toEqual(['annual-next'])
      expect(calls.filter(v => v.includes('/expired/'))).toHaveLength(1)
    })
  })
  it('does not re-execute a candidate after its expired receipt has been quarantined', async () => {
    const { runAnnualReport, flushAnnualReports } = await import('../src/funnyworker-annual-report.ts')
    const { HistorySyncHttpError } = await import('../src/funnyworker-sync-error.ts')
    await withHome(async (home) => {
      const executed: string[] = []
      let expired = false
      const account = { async syncRequest(path: string) { if (path.includes('/old/candidate')) { if (expired) throw new HistorySyncHttpError(409); throw new TypeError('offline') } return { accepted: true } } }
      const execute = (id: string) => runAnnualReport(home, account, 'device', id, frame, new AbortController().signal, agentFor(executed))
      await expect(execute('old')).rejects.toThrow('journaled')
      expired = true
      await flushAnnualReports(home, account, 'device', frame.runtimeEpoch)
      await execute('old'); await execute('next')
      expect(executed).toEqual(['annual-old', 'annual-next'])
    })
  })
  it('journals confirmed failures during outage and retries failure delivery without another Agent turn', async () => {
    const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
    await withHome(async (home) => {
      let online = false, turns = 0
      const account = { async syncRequest(path: string) { if (path.endsWith('/failure') && !online) throw new TypeError('offline'); return { accepted: true } } }
      const agent = { async ready() {}, async run(): Promise<never> { turns++; throw new Error('Confirmed stopped native task') } }
      const execute = () => runAnnualReport(home, account, 'device', 'failed', frame, new AbortController().signal, agent)
      await expect(execute()).rejects.toThrow('transport recovery')
      await expect(execute()).rejects.toThrow('transport recovery')
      online = true; await execute(); await execute()
      expect(turns).toBe(1)
    })
  })
  it('coalesces simultaneous duplicate commands into one native execution', async () => {
    const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
    await withHome(async (home) => {
      const executed: string[] = []
      const account = { async syncRequest() { return { accepted: true } } }
      const agent = agentFor(executed)
      const execute = () => runAnnualReport(home, account, 'device', 'same', frame, new AbortController().signal, agent)
      const first = execute(), second = execute()
      expect(second).toBe(first)
      await Promise.all([first, second]); expect(executed).toEqual(['annual-same'])
    })
  })
  it('a restarted runtime isolates old receipts and executes only a newly assigned attempt', async () => {
    const { runAnnualReport, flushAnnualReports } = await import('../src/funnyworker-annual-report.ts')
    await withHome(async (home) => {
      const executed: string[] = [], calls: string[] = []
      const account = { async syncRequest(path: string) { calls.push(path); if (path.includes('/old/candidate')) throw new TypeError('offline'); return { accepted: true } } }
      await expect(runAnnualReport(home, account, 'device', 'old', frame, new AbortController().signal, agentFor(executed))).rejects.toThrow('journaled')
      const count = calls.length
      await flushAnnualReports(home, account, 'device', 'runtime-restarted')
      expect(calls).toHaveLength(count)
      await runAnnualReport(home, account, 'device', 'new', { ...frame, epoch: 2, runtimeEpoch: 'runtime-restarted' }, new AbortController().signal, agentFor(executed))
      expect(executed).toEqual(['annual-old', 'annual-new'])
    })
  })
  it('requires an explicit accepted acknowledgement before archiving a candidate', async () => {
    const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
    await withHome(async (home) => {
      const executed: string[] = []
      let acknowledged = false
      const account = { async syncRequest() { return acknowledged ? { accepted: true } : {} } }
      const execute = () => runAnnualReport(home, account, 'device', 'ack', frame, new AbortController().signal, agentFor(executed))
      await expect(execute()).rejects.toThrow('journaled')
      acknowledged = true; await execute(); expect(executed).toHaveLength(1)
    })
  })
})

it('recovers a confirmed stopped execution after a crash window but never guesses that a running Agent stopped', async () => {
  const { mkdtemp, mkdir, writeFile, rm } = await import('node:fs/promises')
  const { tmpdir } = await import('node:os')
  const { runAnnualReport } = await import('../src/funnyworker-annual-report.ts')
  const { MaintenanceAgentTerminationUnconfirmed } = await import('../src/funnyworker-maintenance-agent.ts')
  const home = await mkdtemp(join(tmpdir(), 'annual-crash-window-')), calls: string[] = []
  let executions = 0
  const account = { async syncRequest(path: string) { calls.push(path); return { accepted: true } } }
  const agent = { async ready() {}, async run(): Promise<never> { executions++; throw new Error('Must not rerun the persisted attempt') } }
  try {
    const ledger = join(home, 'annual-report-executions'); await mkdir(ledger)
    const coordinates = { runtimeEpoch: frame.runtimeEpoch, epoch: frame.epoch, specHash: frame.specHash }
    await writeFile(join(ledger, 'stopped.json'), JSON.stringify({ ...coordinates, phase: 'STOPPED' }))
    await writeFile(join(ledger, 'running.json'), JSON.stringify({ ...coordinates, phase: 'RUNNING' }))
    await runAnnualReport(home, account, 'device', 'stopped', frame, new AbortController().signal, agent)
    expect(calls).toEqual(['/devices/device/annual-report/stopped/failure'])
    await expect(runAnnualReport(home, account, 'device', 'running', frame, new AbortController().signal, agent)).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
    expect(executions).toBe(0)
    expect(calls).toHaveLength(1)
  } finally { await rm(home, { recursive: true, force: true }) }
})
