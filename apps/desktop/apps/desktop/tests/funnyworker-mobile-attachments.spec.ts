import { createHash } from 'node:crypto'
import { expect, it, vi } from 'vitest'
import { downloadMobileAttachments } from '../src/funnyworker-mobile-attachments.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'

function fixture() {
  const data = Buffer.from('Phone file')
  const hash = createHash('sha256').update(data).digest('hex')
  const request = vi.fn(async (): Promise<unknown> => ({ data: data.toString('base64'), nextOffset: data.length, complete: true }))
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: request }
  return { data, hash, request, account, files: [{ hash, name: 'phone.txt', bytes: data.length, mediaType: 'text/plain' }] }
}
it('verifies bytes before exposing an attachment to the native upload provider', async () => {
  const { account, files, data, hash, request } = fixture()
  expect(await downloadMobileAttachments(account, 'cloud', files, () => {})).toEqual([{ hash, name: 'phone.txt', data }])
  expect(request).toHaveBeenCalledWith(`/sessions/cloud/attachments/${hash}?offset=0`)
})
it('rejects corrupted chunks, invalid cursors, and noncanonical encodings', async () => {
  for (const changed of [{ data: Buffer.from('Other file').toString('base64') }, { nextOffset: 99 }, { complete: false }, { data: 'UGhvbmUgZmlsZQ' }]) {
    const { account, files, request, data } = fixture()
    request.mockResolvedValue({ data: data.toString('base64'), nextOffset: data.length, complete: true, ...changed })
    await expect(downloadMobileAttachments(account, 'cloud', files, () => {})).rejects.toThrow()
  }
})
it('bounds descriptors before downloading and stops on Host replacement', async () => {
  const { account, files, request } = fixture()
  await expect(downloadMobileAttachments(account, 'cloud', [files[0], files[0]], () => {})).rejects.toThrow()
  await expect(downloadMobileAttachments(account, 'cloud', [{ ...files[0], bytes: 33_554_433 }], () => {})).rejects.toThrow()
  await expect(downloadMobileAttachments(account, 'cloud', [{ ...files[0], name: '../file' }], () => {})).rejects.toThrow()
  await expect(downloadMobileAttachments(account, 'cloud', files, () => { throw new Error('Retired') })).rejects.toThrow('Retired')
  expect(request).not.toHaveBeenCalled()
})
it('follows stored chunk boundaries and accepts a verified empty file', async () => {
  const { account, files, request, data, hash } = fixture()
  request.mockResolvedValueOnce({ data: data.subarray(0, 3).toString('base64'), nextOffset: 3, complete: false })
  request.mockResolvedValueOnce({ data: data.subarray(3).toString('base64'), nextOffset: data.length, complete: true })
  expect(await downloadMobileAttachments(account, 'cloud', files, () => {})).toEqual([{ hash, name: 'phone.txt', data }])
  expect(request).toHaveBeenLastCalledWith(`/sessions/cloud/attachments/${hash}?offset=3`)
  const emptyHash = createHash('sha256').digest('hex')
  request.mockResolvedValue({ data: '', nextOffset: 0, complete: true })
  expect(await downloadMobileAttachments(account, 'cloud', [{ hash: emptyHash, name: 'empty.txt', bytes: 0 }], () => {}))
    .toEqual([{ hash: emptyHash, name: 'empty.txt', data: Buffer.alloc(0) }])
})
