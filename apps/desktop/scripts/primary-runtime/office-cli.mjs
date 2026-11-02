/** Application-owned Office CLI entry using public macOS fonts without probing personal Office caches. */
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const resources = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
const cli = join(resources, 'app.asar.unpacked', 'dsh', 'node_modules', '@deepseek-ai', 'libreoffice-kit', 'lib', 'cli.js')
if (process.platform === 'darwin' && ['convert', 'render', 'recalculate'].includes(process.argv[2])
    && !process.argv.slice(3).some(argument => argument === '--font-directory' || argument.startsWith('--font-directory='))) {
  process.argv.push('--font-directory', '/System/Library/Fonts', '--font-directory', '/Library/Fonts')
}
process.argv[1] = cli
await import(pathToFileURL(cli).href)
