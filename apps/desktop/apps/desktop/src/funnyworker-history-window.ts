/** Account history and remote requests; authenticated operations stay in the main process. */
import { BrowserWindow, dialog } from 'electron'
import { createHash, randomUUID } from 'node:crypto'
import { open, rename, rm } from 'node:fs/promises'
import { basename } from 'node:path'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'

const escape = (value: unknown): string => (typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] ?? char)

export interface HistoryMessages {
  readonly historyTitle: string
  readonly historyRefresh: string
  readonly historyEmpty: string
  readonly historyError: string
  readonly historyReadOnly: string
  readonly historyMore: string
  readonly historyRecords: string
  readonly historyUntitled: string
  readonly historyYou: string
  readonly historyAssistant: string
  readonly historyActivity: string
  readonly historyOnline: string
  readonly historyOffline: string
  readonly historySend: string
  readonly historyStop: string
  readonly historyPrompt: string
  readonly historyReply: string
  readonly historyAllowOnce: string
  readonly historyReject: string
  readonly historyAttachments: string
  readonly historyDownload: string
  readonly historyPauseDevice: string
  readonly historyResumeDevice: string
  readonly historyQueue: string
  readonly historySteer: string
  readonly historyCommandQueued: string
  readonly historyCommandClaimed: string
  readonly historyCommandAccepted: string
  readonly historyCommandRejected: string
  readonly historyCommandExpired: string
  readonly historyCommandUncertain: string
}

function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }

function questionFields(questions: unknown[]): string {
  return questions.filter(record).map((item) => {
    const options = Array.isArray(item.options) ? item.options.filter(record) : []
    const choices = options.map(option => `<label><input type="${item.multiSelect === true ? 'checkbox' : 'radio'}" name="selected_${escape(item.id)}" value="${escape(option.label)}">${escape(option.label)} <small>${escape(option.description)}</small></label>`).join('')
    return `<fieldset><legend>${escape(item.question)}</legend><p>${escape(item.detail)}</p>${choices}<textarea name="answer_${escape(item.id)}" maxlength="8000" ${options.length === 0 ? 'required' : ''}></textarea></fieldset>`
  }).join('')
}

/** Open bounded history pages and explicitly submitted remote operations. */
export async function openHistoryWindow(account: FunnyWorkerAccount, messages: HistoryMessages, state: () => string): Promise<void> {
  const window = new BrowserWindow({ width: 940, height: 740, title: messages.historyTitle, backgroundColor: '#F8F6F1', webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true } })
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  let revision = 0
  let active = { id: '', next: 0, after: '' }
  let saving = false
  const draftProbe = "Boolean(document.querySelector('textarea:focus,select:focus') || [...document.querySelectorAll('textarea')].some(input => input.value.length > 0))"
  const load = async (id = '', next = 0, after = '', background = false): Promise<void> => {
    const generation = ++revision
    active = { id, next, after }
    let body = ''
    try {
      if (id === '') {
        const rows = await account.syncRequest(after === '' ? '/sessions' : `/sessions?after=${after}`)
        if (!Array.isArray(rows)) throw new Error('Invalid history directory')
        body = rows.length === 0 ? `<p>${escape(messages.historyEmpty)}</p>` : rows.map((row) => {
          if (!record(row) || typeof row.id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(row.id)) throw new Error('Invalid history row')
          const device = typeof row.device_id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(row.device_id) ? `<a href="https://history.funnywork.invalid/${row.revoked === true ? 'restore' : 'revoke'}/${row.device_id}">${escape(row.revoked === true ? messages.historyResumeDevice : messages.historyPauseDevice)}</a>` : ''
          return `<a class="session" href="https://history.funnywork.invalid/${row.id}"><strong>${escape(row.title || messages.historyUntitled)}</strong><small>${escape(row.device_name)} · ${escape(row.online === true ? messages.historyOnline : messages.historyOffline)} · ${escape(new Date(Number(row.updated_at)).toLocaleString())}</small></a>${device}`
        }).join('')
        const last: unknown = rows.at(-1)
        if (rows.length === 100 && record(last) && typeof last.id === 'string') body += `<a class="session" href="https://history.funnywork.invalid/?after=${escape(last.id)}">${escape(messages.historyMore)}</a>`
      } else {
        const page = await account.syncRequest(`/sessions/${id}/events?offset=${next}`)
        if (!record(page) || !Array.isArray(page.events) || !Number.isSafeInteger(page.nextSeq) || Number(page.nextSeq) < next) throw new Error('Invalid history page')
        const entries = page.events.map((event) => {
          if (!record(event)) throw new Error('Invalid history event')
          const data = record(event.data) ? event.data : {}
          const message = record(data.message) ? data.message : data
          const content = Array.isArray(message.content) ? message.content.filter(record).map(part => typeof part.text === 'string' ? part.text : '').filter(Boolean).join('\n') : ''
          const label = event.type === 'user/message' ? messages.historyYou : event.type === 'assistant/message' ? messages.historyAssistant : messages.historyActivity
          const detail = `<details><summary>${escape(messages.historyRecords)}</summary><pre>${escape(JSON.stringify(event, null, 2))}</pre></details>`
          return `<article><small>${escape(label)}</small>${content === '' ? '' : `<pre class="message">${escape(content)}</pre>`}${detail}</article>`
        }).join('')
        if (generation !== revision || window.isDestroyed()) return
        const offset = Number(page.nextSeq)
        body = entries + (offset < Number(page.durableNextSeq) && offset > next ? `<a class="session" href="https://history.funnywork.invalid/${id}?offset=${offset}">${escape(messages.historyMore)}</a>` : '')
        const remote = await account.syncRequest(`/sessions/${id}/state`)
        const commands = await account.syncRequest(`/sessions/${id}/commands`)
        if (record(remote) && remote.online === true && typeof remote.epoch === 'string' && record(remote.state) && remote.state.controllable !== false) {
          const hidden = () => `<input type="hidden" name="epoch" value="${escape(remote.epoch)}"><input type="hidden" name="requestId" value="${randomUUID()}">`
          const action = `https://history.funnywork.invalid/command/${id}`
          body += `<form action="${action}" method="get">${hidden()}<input type="hidden" name="kind" value="prompt"><input type="hidden" name="expectedTurn" value="${escape(remote.state.turnStart)}"><textarea name="text" maxlength="16000" required placeholder="${escape(messages.historyPrompt)}"></textarea><select name="mode"><option value="queue">${escape(messages.historyQueue)}</option>${Number.isSafeInteger(remote.state.turnStart) ? `<option value="steer">${escape(messages.historySteer)}</option>` : ''}</select><button>${escape(messages.historySend)}</button></form>`
          if (remote.state.running === true && Number.isSafeInteger(remote.state.turnStart)) body += `<form action="${action}" method="get">${hidden()}<input type="hidden" name="kind" value="cancel"><input type="hidden" name="expectedTurn" value="${Number(remote.state.turnStart)}"><button>${escape(messages.historyStop)}</button></form>`
          const projections = record(remote.state.projections) ? remote.state.projections : {}
          const questions = record(projections.userQuestions) && Array.isArray(projections.userQuestions.active)
            ? projections.userQuestions.active : []
          for (const question of questions) {
            if (!record(question) || question.state !== 'continued' || typeof question.callId !== 'string' || !Array.isArray(question.questions)) continue
            const fields = questionFields(question.questions)
            body += `<form action="${action}" method="get">${hidden()}<input type="hidden" name="kind" value="answer"><input type="hidden" name="callId" value="${escape(question.callId)}">${fields}<button>${escape(messages.historyReply)}</button></form>`
          }
          for (const pending of Array.isArray(remote.state.pending) ? remote.state.pending : []) {
            if (!record(pending) || typeof pending.eventId !== 'string' || !record(pending.request)) continue
            const correlation = `<input type="hidden" name="kind" value="decision"><input type="hidden" name="eventId" value="${escape(pending.eventId)}">`
            if (pending.event === 'approval/request') {
              body += `<form action="${action}" method="get">${hidden()}${correlation}<strong>${escape(pending.request.toolName)}</strong><p>${escape(pending.request.reason)}</p><pre>${escape(JSON.stringify(pending.request, null, 2))}</pre><button name="outcome" value="allowed-once">${escape(messages.historyAllowOnce)}</button><button name="outcome" value="rejected">${escape(messages.historyReject)}</button></form>`
            } else if (pending.event === 'user-questions/request' && Array.isArray(pending.request.questions)) {
              const fields = questionFields(pending.request.questions)
              body += `<form action="${action}" method="get">${hidden()}${correlation}${fields}<button>${escape(messages.historyReply)}</button></form>`
            }
          }
        }
        if (Array.isArray(commands)) {
          const labels: Record<string, string> = {
            queued: messages.historyCommandQueued, claimed: messages.historyCommandClaimed,
            accepted: messages.historyCommandAccepted, rejected: messages.historyCommandRejected,
            expired: messages.historyCommandExpired, uncertain: messages.historyCommandUncertain,
          }
          body += commands.filter(record).slice(0, 10).map(command => `<p class="notice">${escape(labels[String(command.status)] ?? messages.historyError)} · ${escape(new Date(Number(command.createdAt)).toLocaleString())}</p>`).join('')
        }
        const manifest = await account.syncRequest(`/sessions/${id}/attachments${after === '' ? '' : `?after=${after}`}`)
        if (record(manifest) && Array.isArray(manifest.items)) {
          const files = manifest.items.filter(record).filter(item => item.complete === true && typeof item.hash === 'string' && /^[a-f0-9]{64}$/.test(item.hash)).map((item) => {
            const metadata = record(item.metadata) && record(item.metadata.attachment) ? item.metadata.attachment : {}
            return `<a class="session" href="https://history.funnywork.invalid/download/${id}/${escape(item.hash)}">${escape(metadata.name || messages.historyDownload)} · ${escape(metadata.bytes)}</a>`
          }).join('')
          if (files !== '') body += `<h2>${escape(messages.historyAttachments)}</h2>${files}`
          if (typeof manifest.after === 'string' && /^[a-f0-9]{64}$/.test(manifest.after)) body += `<a href="https://history.funnywork.invalid/${id}?offset=${next}&after=${manifest.after}">${escape(messages.historyMore)}</a>`
        }
      }
    } catch { body = `<p>${escape(messages.historyError)}</p>` }
    if (generation !== revision || window.isDestroyed()) return
    const html = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action https://history.funnywork.invalid"><style>body{margin:0;background:#F8F6F1;color:#292724;font:15px system-ui}main{max-width:780px;margin:36px auto;padding:0 24px}header{display:flex;align-items:center;justify-content:space-between}h1{font-size:24px}a{color:inherit}.session,article,form{display:block;background:#FFFDFA;border:1px solid #DDD9D2;border-radius:12px;padding:18px;margin:12px 0;text-decoration:none}fieldset{border:0;padding:0;margin:12px 0}fieldset label{display:block;padding:6px 0}textarea{box-sizing:border-box;width:100%;min-height:80px;resize:vertical;background:transparent;color:inherit;border:1px solid #DDD9D2;border-radius:8px;padding:12px;font:inherit}button,select{margin:8px 8px 0 0;padding:8px 14px;border:1px solid #DDD9D2;border-radius:8px;font:inherit}button{background:#292724;color:#F8F6F1}.session span,.session small{display:block;margin-top:8px}small,summary,.notice{color:#69645c}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:13px ui-monospace}.message{font:15px system-ui}a:focus-visible{outline:2px solid #292724}@media(prefers-color-scheme:dark){body{background:#201F1D;color:#EEEAE2}.session,article,form{background:#2A2825;border-color:#45413C}small,summary,.notice{color:#b7b0a5}}</style></head><body><main><header><h1>${escape(messages.historyTitle)}</h1><a href="https://history.funnywork.invalid/${id}">${escape(messages.historyRefresh)}</a></header><p class="notice">${escape(messages.historyReadOnly)} · ${escape(state())}</p>${body}</main></body></html>`
    const scroll: unknown = background ? await window.webContents.executeJavaScript('window.scrollY') : 0
    if (background && await window.webContents.executeJavaScript(draftProbe) !== false) return
    if (generation !== revision || window.isDestroyed()) return
    await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
    if (background && typeof scroll === 'number' && Number.isFinite(scroll) && scroll >= 0 && generation === revision) await window.webContents.executeJavaScript(`window.scrollTo(0, ${scroll})`)
  }
  window.webContents.on('will-navigate', (event, target) => {
    event.preventDefault()
    const url = new URL(target)
    if (url.origin !== 'https://history.funnywork.invalid') return
    if (/^\/(revoke|restore)\/[A-Za-z0-9_-]{1,64}$/.test(url.pathname)) {
      const [, action, device] = url.pathname.split('/')
      void account.syncRequest(`/devices/${device}/${action}`, {}).then(() => load()).catch(() => { void dialog.showMessageBox(window, { type: 'error', message: messages.historyError }) })
      return
    }
    if (url.pathname.startsWith('/download/')) {
      const parts = url.pathname.split('/')
      const session = parts[2]; const hash = parts[3]
      if (saving || session === undefined || hash === undefined || parts.length !== 4
        || !/^[A-Za-z0-9_-]{1,64}$/.test(session) || !/^[a-f0-9]{64}$/.test(hash)) return
      saving = true
      void (async () => {
        const manifest = await account.syncRequest(`/sessions/${session}/attachments?hash=${hash}`)
        if (!record(manifest) || !Array.isArray(manifest.items)) throw new Error('Invalid attachment manifest')
        const item = manifest.items.filter(record).find(item => item.hash === hash && item.complete === true)
        const ref = item !== undefined && record(item.metadata) && record(item.metadata.attachment) ? item.metadata.attachment : undefined
        if (ref === undefined || typeof ref.bytes !== 'number' || !Number.isSafeInteger(ref.bytes)) throw new Error('Missing attachment')
        const size = ref.bytes
        const chosen = await dialog.showSaveDialog(window, { title: messages.historyDownload, defaultPath: basename(typeof ref.name === 'string' ? ref.name.replaceAll('\\', '/') : hash) })
        if (chosen.canceled) return
        const temporary = `${chosen.filePath}.${randomUUID()}.tmp`
        const digest = createHash('sha256')
        const file = await open(temporary, 'wx', 0o600)
        try {
          let offset = 0
          do {
            if (window.isDestroyed()) throw new Error('Download window closed')
            const chunk = await account.syncRequest(`/sessions/${session}/attachments/${hash}?offset=${offset}`)
            if (!record(chunk) || typeof chunk.data !== 'string' || typeof chunk.nextOffset !== 'number' || !Number.isSafeInteger(chunk.nextOffset)) throw new Error('Invalid attachment chunk')
            const bytes = Buffer.from(chunk.data, 'base64')
            if (offset + bytes.length !== chunk.nextOffset || chunk.nextOffset > size || (bytes.length === 0 && chunk.complete !== true)) throw new Error('Invalid attachment cursor')
            await file.writeFile(bytes); digest.update(bytes); offset = chunk.nextOffset
            if (chunk.complete === true) break
          } while (offset < size)
          if (offset !== size || digest.digest('hex') !== hash) throw new Error('Attachment verification failed')
          await file.sync()
          await file.close()
          await rename(temporary, chosen.filePath)
        } finally { await file.close(); await rm(temporary, { force: true }) }
      })().catch(() => { void dialog.showMessageBox(window, { type: 'error', message: messages.historyError }) }).finally(() => { saving = false })
      return
    }
    if (url.pathname.startsWith('/command/')) {
      const session = url.pathname.slice('/command/'.length)
      const kind = url.searchParams.get('kind')
      const epoch = url.searchParams.get('epoch')
      const requestId = url.searchParams.get('requestId')
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(session) || epoch === null || requestId === null
        || !/^[A-Za-z0-9_-]{1,64}$/.test(epoch) || !/^[A-Za-z0-9_-]{1,64}$/.test(requestId)) return
      let payload: object
      if (kind === 'prompt') payload = { text: url.searchParams.get('text'), mode: url.searchParams.get('mode'), expectedTurn: Number(url.searchParams.get('expectedTurn')) }
      else if (kind === 'cancel') payload = { expectedTurn: Number(url.searchParams.get('expectedTurn')) }
      else if (kind === 'answer') payload = { callId: url.searchParams.get('callId'), answer: { answers: [...url.searchParams].filter(([key]) => key.startsWith('answer_')).map(([key, custom]) => ({ id: key.slice(7), selected: url.searchParams.getAll(`selected_${key.slice(7)}`), custom })) } }
      else if (kind === 'decision') payload = { eventId: url.searchParams.get('eventId'), outcome: url.searchParams.get('outcome'), answer: { answers: [...url.searchParams].filter(([key]) => key.startsWith('answer_')).map(([key, custom]) => ({ id: key.slice(7), selected: url.searchParams.getAll(`selected_${key.slice(7)}`), custom })) } }
      else return
      void account.syncRequest(`/sessions/${session}/commands`, { id: requestId, epoch, kind, payload }).then(() => load(session)).catch(() => { void dialog.showMessageBox(window, { type: 'error', message: messages.historyError }) })
      return
    }
    const id = url.pathname.slice(1)
    const next = Number(url.searchParams.get('offset') ?? 0)
    const after = url.searchParams.get('after') ?? ''
    if ((id === '' || /^[A-Za-z0-9_-]+$/.test(id)) && (after === '' || /^[A-Za-z0-9_-]+$/.test(after)) && Number.isSafeInteger(next) && next >= 0) void load(id, next, after)
  })
  await load()
  let refreshing = false
  const refresh = setInterval(() => {
    if (window.isDestroyed() || saving || refreshing) return
    refreshing = true
    void window.webContents.executeJavaScript(draftProbe).then((draft: unknown) => {
      if (draft !== false || window.isDestroyed()) return
      return load(active.id, active.next, active.after, true)
    }).catch((_error: unknown) => { /* A closed or navigating window ends this refresh attempt. */ }).finally(() => { refreshing = false })
  }, 5000)
  refresh.unref()
  window.once('closed', () => { clearInterval(refresh) })
}
