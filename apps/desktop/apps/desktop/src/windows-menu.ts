/** Windows caption menus expose the same file and account actions as the Mac application menu. */
import type { MenuItemConstructorOptions } from 'electron'

/**
 * Compose the caption popup with account actions before application commands, including Quit.
 * @param file File submenu with the current close-page binding.
 * @param account Authenticated history, device connection and sign-out actions; empty before login.
 * @param application About, updates, command registration and exit actions.
 * @returns Native popup items retaining the original action callbacks.
 */
export function windowsApplicationMenu(
  file: MenuItemConstructorOptions,
  account: readonly MenuItemConstructorOptions[],
  application: readonly MenuItemConstructorOptions[],
): MenuItemConstructorOptions[] {
  return [file, ...account, { type: 'separator' }, ...application]
}
