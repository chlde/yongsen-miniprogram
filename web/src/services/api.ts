import type { AuthSession, JsonRecord } from '../types'
import { API_BASE_URL } from './environment'

const base = API_BASE_URL
let currentToken = ''
const errors: Record<string, string> = {
  UNAUTHORIZED: '登录已失效，请退出后重新登录。', FORBIDDEN: '没有访问这项记录的权限。',
  INVALID_CREDENTIALS: '账号或密码不正确。', LOGIN_FAILED: '账号或密码不正确。',
  BINDING_REQUIRED: '请先使用个人邀请码绑定身份，并等待管理员核对。', BINDING_PENDING: '身份正在等待管理员核对。',
  BINDING_EXISTS: '此人员或微信身份已有绑定，请联系管理员核对或换绑。',
  PERSON_INACTIVE: '该人员已停用，请联系管理员。', INVITE_INVALID: '邀请码无效、已使用或已被替换。', INVITE_EXPIRED: '邀请码已过期，请管理员重新生成。',
  VERSION_CONFLICT: '记录已更新，请刷新页面后再操作。', TASK_NOT_ACTIVE: '该任务已取消或尚未发布。',
  MATERIAL_NOT_READY: '材料尚未完成处理或兼容性确认。', SESSION_IN_USE: '当前身份正在其他页面学习，请明确选择是否在此继续。',
  SESSION_EXPIRED: '学习会话已过期，请重新进入。已确认进度会保留。', SESSION_REVOKED: '学习会话已结束或被其他设备接管。',
  LEARNING_NOT_COMPLETE: '还有必学材料未达到要求，请完成后再确认。', PROGRESS_CHANGED: '学习结果已变化，请刷新任务详情后重新核对。',
  SUBSCRIPTION_REQUIRED: '需要学员重新主动订阅消息后再发送。', CONFIRMATION_REQUIRED: '请由本人主动勾选确认声明。',
  NEW_SUBSCRIPTION_REQUIRED: '所选人员需要重新点击接收微信提醒，取得新授权后才能再次提醒。',
  UNKNOWN_REQUIRES_CONFIRMATION: '原通知结果未知，请先让学员重新接收微信提醒，再勾选可能重复送达的确认。',
}

export class ApiError extends Error {
  constructor(message: string, public code: string, public status: number, public details?: JsonRecord) { super(message) }
}

export function setToken(token = '') { currentToken = token }
export const requestId = () => crypto.randomUUID()

export async function api<T = JsonRecord>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (currentToken) headers.set('Authorization', `Bearer ${currentToken}`)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (options.method && !['GET', 'HEAD'].includes(options.method)) headers.set('Idempotency-Key', headers.get('Idempotency-Key') || requestId())
  let response: Response
  try { response = await fetch(`${base}${path}`, { ...options, headers }) }
  catch { throw new ApiError('无法连接服务，请检查后端是否已启动。', 'NETWORK_ERROR', 0) }
  const data = await response.json().catch(() => null)
  if (!response.ok || (data?.code && data.code !== 'OK')) {
    throw new ApiError(errors[data?.code] || data?.message || `请求失败（${response.status}）`, data?.code || 'REQUEST_FAILED', response.status, data?.details)
  }
  return (data && Object.prototype.hasOwnProperty.call(data, 'data') ? data.data : data) as T
}

export const get = <T = JsonRecord>(path: string) => api<T>(path)
export const post = <T = JsonRecord>(path: string, body: unknown = {}, key?: string) => api<T>(path, { method: 'POST', body: JSON.stringify(body), ...(key ? { headers: { 'Idempotency-Key': key } } : {}) })
export const patch = <T = JsonRecord>(path: string, body: unknown) => api<T>(path, { method: 'PATCH', body: JSON.stringify(body) })
export const put = <T = JsonRecord>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) })
export function items<T = JsonRecord>(value: any): T[] { return Array.isArray(value) ? value : value?.items || value?.content || [] }
export async function getAll<T = JsonRecord>(path: string): Promise<T[]> {
  const result: T[] = [], cursors = new Set<string>()
  let cursor: string | null = null
  do {
    const page: JsonRecord = await get(`${path}${path.includes('?') ? '&' : '?'}limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    result.push(...items<T>(page))
    cursor = page.nextCursor || null
    if (cursor && cursors.has(cursor)) throw new Error('分页游标异常，未显示不完整数据，请刷新重试。')
    if (cursor) cursors.add(cursor)
  } while (cursor)
  return result
}
export function revision(value: JsonRecord) { return value.rowVersion ?? value.revision ?? 0 }

export async function download(path: string, filename: string) {
  const response = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${currentToken}` } })
  if (!response.ok) throw new Error('下载失败，请检查权限或稍后重试。')
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function printRecord(path: string) {
  const popup = window.open('', '_blank')
  const response = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${currentToken}` } })
  if (!response.ok) { popup?.close(); throw new Error('打印记录加载失败。') }
  const blob = new Blob([await response.text()], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  if (popup) popup.location.href = url
  else { URL.revokeObjectURL(url); throw new Error('请允许打开打印窗口后重试。') }
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}

export function saveAuth(session: AuthSession) { localStorage.setItem('yongsen.session', JSON.stringify(session)); setToken(session.token) }
export function loadAuth(): AuthSession | null { try { const item = JSON.parse(localStorage.getItem('yongsen.session') || 'null'); if (item?.token) { setToken(item.token); return item } } catch {} return null }
export function clearAuth() { localStorage.removeItem('yongsen.session'); setToken('') }
