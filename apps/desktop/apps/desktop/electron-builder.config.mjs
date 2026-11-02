/** Ordinary release entry; qualification imports the environment-independent factory. */
import { createElectronBuilderConfig } from './scripts/electron-builder-config.mjs'
import { configureLinuxArtifactUmask } from './scripts/linux-artifact-permissions.mjs'

export { createElectronBuilderConfig }
// The builder also owns generated desktop entries and AppImage staging directories.
configureLinuxArtifactUmask(process.env.DSH_DESKTOP_TARGET_PLATFORM ?? process.platform)
export default createElectronBuilderConfig()
