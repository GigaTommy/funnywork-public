/** Original account feedback UI; credentials and submission remain in the existing Electron main process. */
import { app, BrowserWindow, shell } from 'electron'
import { randomUUID } from 'node:crypto'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import type { DesktopMessages } from './locale.ts'
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'

const escape = (value: string): string => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] ?? char)
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
let active: BrowserWindow | undefined
/**
 * Open account feedback, persistent history and follow-up information.
 * @param account Original authenticated account transport.
 * @param messages Typed localized shell copy.
 * @returns When the initial feedback window is loaded.
 */
export async function openFeedbackWindow(account: FunnyWorkerAccount, messages: DesktopMessages): Promise<void> {
  if (active !== undefined && !active.isDestroyed()) { active.show(); active.focus(); return }
  const window = new BrowserWindow({ width: 580, height: 710, minWidth: 420, minHeight: 580, title: messages.feedbackTitle, backgroundColor: '#F8F6F1', webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } })
  active = window; window.setMenu(null); window.webContents.setWindowOpenHandler(() => ({ action: 'deny' })); window.webContents.on('will-redirect', (event) => { event.preventDefault() })
  let repositories: { id: string; slug: string }[] = [], closed = false, busy = false, intake: string | undefined
  let view: 'new' | 'history' | 'detail' = 'new'
  let currentDetail: Record<string, unknown> | undefined
  let history: { id: string; title: string; state: string; repairState?:string }[] = [], after = '', next = ''
  let previous: string[] = []
  let supplement = '', supplementRequest = randomUUID(), supplementAttempt: string | undefined
  const validId = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(value)
  let prUrl: string | undefined
  let requestId = randomUUID(), draft: Record<string, string> = {}, polling: ReturnType<typeof setInterval> | undefined
  const render = async (message = '', detail?: Record<string, unknown>): Promise<void> => {
    if (closed || window.isDestroyed()) return
    if (detail !== undefined) currentDetail = detail
    detail = view === 'detail' ? currentDetail : undefined
    const field = (name: string, label: string, multiline = false): string => `<label>${escape(label)}${multiline ? `<textarea name="${name}" rows="3" maxlength="6000" required>${escape(draft[name] ?? '')}</textarea>` : `<input name="${name}" maxlength="160" value="${escape(draft[name] ?? '')}" required>`}</label>`
    const states: Record<string, string> = {
      RECEIVED: messages.feedbackReceived, ANALYZING: messages.feedbackAnalyzing, ADMITTED: messages.feedbackAdmitted,
      DUPLICATE: messages.feedbackDuplicate, NEEDS_INFORMATION: messages.feedbackNeedInfo, DECLINED: messages.feedbackDeclined,
      READY_FOR_REVIEW: messages.feedbackReview, SOURCE_CHANGED: messages.feedbackReview, RUNTIME_FAILED: messages.feedbackError,
    }
    const analysis = object(detail?.analysis) && object(detail.analysis.analysis) ? detail.analysis.analysis : undefined
    const repair = object(detail?.repair) ? detail.repair : undefined
    prUrl = typeof repair?.prUrl === 'string' && /^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/pull\/[1-9][0-9]*$/.test(repair.prUrl) ? repair.prUrl : undefined
    const repairStatus = detail?.state === 'SOURCE_CHANGED' ? messages.feedbackReview : repair?.deliveryState === 'DELIVERED' ? messages.feedbackDelivered : repair?.state === 'BLOCKED' ? messages.feedbackError : repair?.state === 'ACCEPTED' ? messages.feedbackAccepted : repair?.state === 'AWAITING_ACCEPTANCE' ? messages.feedbackAwaiting : undefined
    const resolution=object(detail?.resolution)?detail.resolution:undefined
    const resolutionText=typeof resolution?.title==='string'&&resolution.code==='FIX_MERGED'?messages.feedbackFixMerged.replace('{title}',resolution.title):typeof resolution?.title==='string'&&resolution.code==='FIX_ACCEPTED'?messages.feedbackFixAccepted.replace('{title}',resolution.title):undefined
    const status = detail === undefined ? '' : `<h2>${escape(repairStatus ?? states[String(detail.state)] ?? messages.feedbackReview)}</h2><p>${escape(resolutionText ?? (typeof analysis?.reason === 'string' ? analysis.reason : ''))}</p>${typeof analysis?.requirement === 'string' && analysis.requirement !== '' ? `<h3>${escape(messages.feedbackRequirement)}</h3><p>${escape(analysis.requirement)}</p>` : ''}${prUrl === undefined ? '' : `<a href="https://feedback.funnywork.invalid/pr">${escape(messages.feedbackPr)}</a> · `}<a href="https://feedback.funnywork.invalid/refresh">${escape(messages.historyRefresh)}</a>`
    const notes = Array.isArray(detail?.messages) ? detail.messages.flatMap(note => object(note) && typeof note.content === 'string' ? [`<p class="note">${note.review_state==='HELD'?`<strong>${escape(messages.feedbackNoteHeld)}</strong><br>`:''}${escape(note.content)}</p>`] : []).join('') : ''
    const followUp = detail?.state === 'NEEDS_INFORMATION' && detail.canSupplement!==false && detail.repair === undefined ? `<form action="https://feedback.funnywork.invalid/supplement" method="get"><label>${escape(messages.feedbackSupplement)}<textarea name="content" rows="5" maxlength="6000" placeholder="${escape(messages.feedbackSupplementHint)}" required>${escape(supplement)}</textarea></label><button>${escape(messages.feedbackSupplementSubmit)}</button></form>` : ''
    const historyPage = `<h2>${escape(messages.feedbackHistory)}</h2>${history.length === 0 ? `<p>${escape(messages.feedbackEmpty)}</p>` : history.map(row => `<article><a href="https://feedback.funnywork.invalid/detail?id=${encodeURIComponent(row.id)}">${escape(row.title)}</a><p>${escape(row.state!=='SOURCE_CHANGED'&&row.repairState==='DELIVERED'?messages.feedbackDelivered:row.state!=='SOURCE_CHANGED'&&row.repairState==='ACCEPTED'?messages.feedbackAccepted:states[row.state] ?? messages.feedbackReview)}</p></article>`).join('')}<nav>${previous.length === 0 ? '' : `<a href="https://feedback.funnywork.invalid/previous">${escape(messages.feedbackPrevious)}</a>`} ${next === '' ? '' : `<a href="https://feedback.funnywork.invalid/next">${escape(messages.feedbackNext)}</a>`}</nav><a href="https://feedback.funnywork.invalid/history">${escape(messages.historyRefresh)}</a>`
    const form = view === 'history' ? historyPage : view === 'new' ? `<form action="https://feedback.funnywork.invalid/submit" method="get"><label>${escape(messages.feedbackRepository)}<select name="repositoryId" required>${repositories.map(r => `<option value="${escape(r.id)}" ${draft.repositoryId === r.id ? 'selected' : ''}>${escape(r.slug)}</option>`).join('')}</select></label>${field('title', messages.feedbackSubject)}${field('actual', messages.feedbackActual, true)}${field('expected', messages.feedbackExpected, true)}${field('steps', messages.feedbackSteps, true)}<button ${repositories.length === 0 ? 'disabled' : ''}>${escape(messages.feedbackSubmit)}</button></form>` : `${typeof detail?.title === 'string' ? `<h3>${escape(detail.title)}</h3>` : ''}${status}${notes}${followUp}`
    await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(`<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; form-action https://feedback.funnywork.invalid"><style>body{margin:0;background:#F8F6F1;color:#292724;font:14px system-ui}main{padding:24px}h1{font-size:22px}h2{font-size:18px}p{white-space:pre-wrap;line-height:1.55}label{display:block;margin:12px 0}input,textarea,select{box-sizing:border-box;width:100%;margin-top:6px;padding:9px;border:1px solid #DDD9D2;border-radius:7px;background:#FFFDFA;color:inherit;font:inherit}button{padding:10px 18px;background:#292724;color:white;border:0;border-radius:7px}button:disabled{opacity:.45}a{color:inherit}nav{display:flex;gap:16px;margin:16px 0}article,.note{border-bottom:1px solid #DDD9D2;padding:12px 0}article p{margin-bottom:0}@media(prefers-color-scheme:dark){body{background:#201F1D;color:#EEEAE2}input,textarea,select{background:#2A2825;border-color:#45413C}button{background:#EEEAE2;color:#24221F}}</style></head><body><main><h1>${escape(messages.feedbackTitle)}</h1><p>${escape(messages.feedbackHint)}</p><nav><a href="https://feedback.funnywork.invalid/new">${escape(messages.feedbackNew)}</a><a href="https://feedback.funnywork.invalid/history">${escape(messages.feedbackHistory)}</a></nav><p role="status">${escape(message)}</p>${form}</main></body></html>`)}`)
  }
  const loadDetail = async (id: string): Promise<void> => {
    const detail: unknown = await account.syncRequest(`/feedback/${id}`)
    if (!object(detail) || typeof detail.state !== 'string') throw new Error('Invalid feedback receipt')
    intake = id; view = 'detail'; currentDetail = detail; supplement = ''; supplementAttempt = undefined
    await render()
  }
  const loadHistory = async (cursor: string): Promise<void> => {
    const result: unknown = await account.syncRequest(`/feedback?after=${encodeURIComponent(cursor)}&limit=20`)
    if (!object(result) || !Array.isArray(result.items) || typeof result.nextCursor !== 'string' || (result.nextCursor !== '' && !validId(result.nextCursor))) throw new Error('Invalid feedback history')
    const rows: typeof history = []
    for (const row of result.items) {
      if (!object(row) || !validId(row.id) || typeof row.title !== 'string' || typeof row.state !== 'string') throw new Error('Invalid feedback history item')
      rows.push({ id: row.id, title: row.title, state: row.state, ...(typeof row.repair_state==='string'?{ repairState:row.repair_state }:{}) })
    }
    history = rows; after = cursor; next = result.nextCursor; view = 'history'; intake = undefined; currentDetail = undefined
  }
  const failed = async (error: unknown): Promise<void> => {
    if (error instanceof HistorySyncHttpError && [401, 403, 404].includes(error.status)) {
      currentDetail = undefined; intake = undefined; history = []; next = ''; previous = []; repositories = []; draft = {}; supplement = ''; view = 'history'
    }
    await render(messages.feedbackError)
  }
  const refresh = async (): Promise<void> => {
    if (busy || closed || view !== 'detail' || intake === undefined) return
    busy = true
    try { await loadDetail(intake) }
    catch (error: unknown) { await failed(error) }
    finally { busy = false }
  }
  window.webContents.on('will-navigate', (event, target) => {
    event.preventDefault(); const url = new URL(target)
    if (url.origin !== 'https://feedback.funnywork.invalid' || busy || closed) return
    if (url.pathname === '/pr' && prUrl !== undefined) { void shell.openExternal(prUrl).catch(failed); return }
    if (url.pathname === '/refresh') { void refresh(); return }
    busy = true
    void (async () => {
      try {
        if (url.pathname === '/new') {
          view = 'new'; intake = undefined; currentDetail = undefined; await render(); return
        }
        if (url.pathname === '/history') { await loadHistory(''); previous = []; await render(); return }
        if (url.pathname === '/next' && view === 'history' && next !== '') {
          const prior = after; await loadHistory(next); previous.push(prior); await render(); return
        }
        const priorCursor = previous.at(-1)
        if (url.pathname === '/previous' && view === 'history' && priorCursor !== undefined) {
          await loadHistory(priorCursor); previous.pop(); await render(); return
        }
        const detailId = url.searchParams.get('id')
        if (url.pathname === '/detail' && validId(detailId)) { await loadDetail(detailId); return }
        if (url.pathname === '/supplement' && view === 'detail' && intake !== undefined && currentDetail?.state === 'NEEDS_INFORMATION' && currentDetail.repair === undefined) {
          supplement = url.searchParams.get('content') ?? ''
          if (supplement.trim() === '' || supplement.length > 6000) { await render(messages.feedbackSupplementHint); return }
          if (supplementAttempt !== undefined && supplementAttempt !== supplement) supplementRequest = randomUUID()
          supplementAttempt = supplement
          const receipt: unknown = await account.syncRequest(`/feedback/${intake}/supplements`, { requestId: supplementRequest, content: supplement })
          if (!object(receipt) || receipt.accepted !== true || typeof receipt.reused !== 'boolean' || (receipt.held!==undefined&&typeof receipt.held!=='boolean')) throw new Error('Invalid feedback follow-up receipt')
          supplement = ''; supplementAttempt = undefined; supplementRequest = randomUUID()
          const held=receipt.held===true
          const notes: unknown[] = Array.isArray(currentDetail.messages) ? currentDetail.messages : []
          currentDetail = { ...currentDetail, state: held?'NEEDS_INFORMATION':'RECEIVED', messages: [...notes, { content: url.searchParams.get('content'), review_state:held?'HELD':'APPLIED' }] }
          await render(held?messages.feedbackSupplementHeld:messages.feedbackSupplementSaved); return
        }
        if (url.pathname !== '/submit' || view !== 'new') return
        draft = Object.fromEntries(url.searchParams)
        const receipt: unknown = await account.syncRequest('/feedback', { ...draft, requestId, platform: process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'linux', clientVersion: app.getVersion() })
        if (!object(receipt) || !validId(receipt.intakeId)) throw new Error('Invalid feedback receipt')
        intake = receipt.intakeId; view = 'detail'; draft = {}; requestId = randomUUID()
        await render(messages.feedbackReceived, { title: url.searchParams.get('title') ?? '', state: 'RECEIVED' })
      } catch (error: unknown) { await failed(error) }
      finally { busy = false }
    })()
  })
  window.on('closed', () => { closed = true; clearInterval(polling); if (active === window) active = undefined })
  try {
    const values: unknown = await account.syncRequest('/feedback/repositories')
    if (!Array.isArray(values) || values.some(r => !object(r) || typeof r.id !== 'string' || typeof r.slug !== 'string')) throw new Error('Invalid feedback repository catalog')
    repositories = values.flatMap(r => object(r) && typeof r.id === 'string' && typeof r.slug === 'string' ? [{ id: r.id, slug: r.slug }] : [])
    await render(repositories.length === 0 ? messages.feedbackNoRepository : '')
    if (!window.isDestroyed()) polling = setInterval(() => {
      // Reloading the sandboxed document would discard information being typed.
      if (currentDetail?.state !== 'NEEDS_INFORMATION') void refresh()
    }, 5000)
  } catch (error: unknown) { await failed(error) }
}
