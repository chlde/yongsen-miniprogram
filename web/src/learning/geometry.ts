export function visiblePageRange(viewport: { top: number; bottom: number }, image: { top: number; bottom: number; height: number }, originalHeight: number): [number, number] | null {
  if (image.height <= 0 || originalHeight <= 0) return null
  const top = Math.max(viewport.top, image.top), bottom = Math.min(viewport.bottom, image.bottom)
  if (bottom <= top) return null
  const scale = originalHeight / image.height
  return [Math.max(0, Math.floor((top - image.top) * scale)), Math.min(originalHeight, Math.ceil((bottom - image.top) * scale))]
}

export function resumePosition(progress: { missingRangesMs?: number[][]; requirementsMet?: boolean }): number {
  if (progress.requirementsMet) return 0
  return Math.max(0, (progress.missingRangesMs?.[0]?.[0] || 0) - 2000)
}
