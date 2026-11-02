/** Set only this Linux build process's umask; return its previous value for restoration. */
export function configureLinuxArtifactUmask(platform: NodeJS.Platform): number | undefined

/** Normalize a physical, exclusively owned linux-unpacked output; reject unsafe entries before changes. */
export function normalizeLinuxArtifactPermissions(directory: string): void
