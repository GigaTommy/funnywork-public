/** Short-lived connection codes use the desktop's existing synchronized device identity. */
import QRCode from 'qrcode'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'

/** Phone connection requires an HTTPS origin so the mobile client can verify its server. */
export class DeviceLinkHttpsRequiredError extends Error {}

/** Main-process connection code. Never persist or log the secret. */
export interface DeviceLink {
  readonly id: string
  readonly secret: string
  readonly expiresAt: number
  readonly uri: string
}

/**
 * Creates a same-account code for an online desktop over HTTPS.
 * @param account Authenticated account, with its configured server origin.
 * @param deviceId Existing history device identifier.
 * @returns A code whose local deadline conservatively includes request latency.
 */
export async function createDeviceLink(account: FunnyWorkerAccount, deviceId: string): Promise<DeviceLink> {
  if (account.serverOrigin === undefined || !/^[A-Za-z0-9_-]{1,64}$/.test(deviceId)) throw new Error('Device connection unavailable')
  const server = new URL(account.serverOrigin)
  if (server.protocol !== 'https:') throw new DeviceLinkHttpsRequiredError('Device connection requires HTTPS')
  if (server.username || server.password || server.pathname !== '/' || server.search || server.hash) throw new Error('Device connection requires HTTPS')
  const started = Date.now()
  const value = await account.syncRequest(`/devices/${deviceId}/link`, {})
  if (typeof value !== 'object' || value === null || !('id' in value) || typeof value.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value.id) || !('secret' in value) || typeof value.secret !== 'string' || !/^[a-f0-9]{64}$/.test(value.secret) || !('expiresIn' in value) || typeof value.expiresIn !== 'number' || !Number.isInteger(value.expiresIn) || value.expiresIn <= 0 || value.expiresIn > 120000) throw new Error('Invalid device connection response')
  const parameters = new URLSearchParams({ v: '1', server: server.origin, id: value.id, secret: value.secret })
  return { id: value.id, secret: value.secret, expiresAt: started + value.expiresIn, uri: `funnywork://connect?${parameters}` }
}

/**
 * Renders a local PNG with the standard quiet zone; no external image service receives the code.
 * @param link Active connection code.
 * @returns A PNG data URL suitable for the isolated connection window.
 */
export async function deviceLinkImage(link: DeviceLink): Promise<string> {
  return QRCode.toDataURL(link.uri, { errorCorrectionLevel: 'M', margin: 4, width: 320, color: { dark: '#292724FF', light: '#FFFDFAFF' } })
}
