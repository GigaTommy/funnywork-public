/** Resolve the installed font used only by package-time Office conversion checks. */
import { existsSync } from 'node:fs'
import { join } from 'node:path'

const LINUX_FONTS = [
  '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
  '/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf',
  '/usr/share/fonts/dejavu/DejaVuSans.ttf',
]

/**
 * Select the platform's qualification font without bundling host fonts in the product.
 * @param platform - Native build platform.
 * @param environment - Windows system-directory setting.
 * @returns Installed TrueType font path; Linux requires the distribution's DejaVu Sans package.
 */
export function desktopSmokeFont(platform: NodeJS.Platform = process.platform, environment: NodeJS.ProcessEnv = process.env): string {
  if (platform === 'darwin') return '/System/Library/Fonts/Supplemental/Arial.ttf'
  if (platform === 'win32') return join(environment.SystemRoot ?? 'C:\\Windows', 'Fonts', 'arial.ttf')
  if (platform !== 'linux') throw new Error(`desktop runtime: unsupported smoke platform ${platform}`)
  const font = LINUX_FONTS.find(path => existsSync(path))
  if (font === undefined) {
    throw new Error('desktop runtime: install the distribution DejaVu Sans TrueType fonts before Linux Office qualification (fonts-dejavu-core on Debian/Ubuntu, dejavu-sans-fonts on RPM systems)')
  }
  return font
}
