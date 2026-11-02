import type { IconProps } from './icons/props.ts'
import { FUNNYWORKER_BRAND_IMAGE, FUNNYWORKER_BRAND_NAME } from './brand-image.ts'
/** FunnyWorker wordmark display options. */
export interface BrandWordmarkProps extends IconProps {
  /** Include the leading mark; defaults to true. */
  includeMark?: boolean | undefined
}
/**
 * Render the FunnyWorker name with its optional mark.
 * @param props.size - artwork height in pixels.
 * @param props.className - extra layout class.
 * @param props.includeMark - include the leading mark.
 * @returns decorative SVG wordmark.
 */
export function BrandWordmark({ size = 24, className, includeMark = true }: BrandWordmarkProps) {
  const width = includeMark ? 182 : 156
  return <svg width={(size * width) / 24} height={size} className={className}
    viewBox={includeMark ? '0 0 182 24' : '26 0 156 24'} fill="none" aria-hidden="true">
    {includeMark && <image href={FUNNYWORKER_BRAND_IMAGE} width="24" height="24" />}
    <text x="30" y="18" fill="currentColor" fontFamily="Arial Rounded MT Bold, Arial, sans-serif" fontSize="21" fontWeight="600">{FUNNYWORKER_BRAND_NAME}</text>
  </svg>
}
