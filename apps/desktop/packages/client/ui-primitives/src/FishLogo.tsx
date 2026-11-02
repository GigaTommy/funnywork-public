import type { IconProps } from './icons/props.ts'
import { FUNNYWORKER_BRAND_IMAGE } from './brand-image.ts'
/** Square coordinate space; legacy export name retained. */
export const FISH_LOGO_VIEWBOX = { width: 24, height: 24 }
/** Monochrome F silhouette for consumers composing masks. */
export const FISH_LOGO_PATH = 'M6 3H21L17 8H6ZM9 9A3 3 0 1 1 3 9A3 3 0 1 1 9 9ZM4 13H20L16 17H9L7 23H1Z'
/**
 * Render the approved FunnyWorker mark through the existing brand API.
 * @param props.size - square edge in pixels.
 * @param props.className - extra layout class.
 * @returns decorative brand SVG.
 */
export function FishLogo({ size = 24, className }: IconProps) {
  return <svg width={size} height={size} className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <image href={FUNNYWORKER_BRAND_IMAGE} width="24" height="24" />
  </svg>
}
