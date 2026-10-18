export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080/api/v1'

function loopback(hostname: string): boolean {
  if (['localhost', '::1', '[::1]'].includes(hostname)) return true
  return /^127(?:\.\d{1,3}){3}$/.test(hostname) && hostname.split('.').every(part => Number(part) <= 255)
}

export function allowsLocalPreview(pageAddress: string, apiAddress: string): boolean {
  try {
    const page = new URL(pageAddress), api = new URL(apiAddress, page)
    return [page, api].every(url => ['http:', 'https:'].includes(url.protocol) && loopback(url.hostname))
  } catch { return false }
}

export const localPreview = typeof window !== 'undefined' && allowsLocalPreview(window.location.href, API_BASE_URL)
