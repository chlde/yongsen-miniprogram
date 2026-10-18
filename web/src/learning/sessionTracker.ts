import type { JsonRecord, LearningEvent } from '../types'
import { api, post, requestId } from '../services/api'

export interface TrackerOptions {
  sessionId: string
  initialSeq?: number
  sampleType: 'VIDEO_SAMPLE' | 'DOC_SAMPLE'
  payload: () => JsonRecord
  onProgress: (progress: JsonRecord) => void
  onState: (state: 'syncing' | 'synced' | 'offline' | 'closed', message?: string) => void
  onPause: () => void
}

/** Collects browser observations only. Server responses are the sole source of confirmed progress. */
export class SessionTracker {
  private origin = performance.now()
  private seq: number
  private queue: LearningEvent[] = []
  private pending: { id: string; events: LearningEvent[] } | null = null
  private sending = false
  private sampleTimer?: ReturnType<typeof setInterval>
  private uploadTimer?: ReturnType<typeof setInterval>
  private lastAck = performance.now()
  private stopped = false
  private noAckPaused = false
  private abort?: AbortController

  constructor(private options: TrackerOptions) { this.seq = (options.initialSeq || 1) - 1 }

  async start(): Promise<boolean> {
    this.event(this.options.sampleType, false)
    await this.flush()
    if (this.stopped) return false
    const baselineConfirmed = this.pending === null
    if (!baselineConfirmed) { this.noAckPaused = true; this.options.onPause() }
    this.sampleTimer = setInterval(() => {
      if (performance.now() - this.lastAck >= 10000 && !this.noAckPaused) {
        this.noAckPaused = true; this.options.onPause(); this.options.onState('offline', '10秒未收到服务端确认，已暂停。网络恢复后请继续学习。')
      }
      this.event(this.options.sampleType, false)
    }, 1000)
    this.uploadTimer = setInterval(() => { void this.flush() }, 5000)
    return baselineConfirmed
  }

  event(type: string, immediate = true) {
    if (this.stopped) return
    if (this.queue.length >= 200) { this.options.onPause(); this.options.onState('offline', '待同步记录过多，请联网后重新进入学习。'); return }
    this.queue.push({ eventId: requestId(), sessionId: this.options.sessionId, seq: ++this.seq, type, elapsedMs: Math.round(performance.now() - this.origin), payload: this.options.payload() })
    if (immediate) void this.flush()
  }

  async flush(): Promise<void> {
    if (this.sending || this.stopped || (!this.pending && !this.queue.length)) return
    this.sending = true
    if (!this.pending) this.pending = { id: requestId(), events: this.queue.splice(0, 50) }
    const batch = this.pending
    this.abort = new AbortController()
    const timeout = setTimeout(() => this.abort?.abort(), 12000)
    this.options.onState('syncing')
    try {
      const data = await api(`/learning/sessions/${this.options.sessionId}/events:batch`, { method: 'POST', body: JSON.stringify({ batchId: batch.id, clientVersion: 'web-local-1.0.0', events: batch.events }), headers: { 'Idempotency-Key': batch.id }, signal: this.abort.signal })
      this.pending = null; this.lastAck = performance.now(); this.noAckPaused = false
      if (data.confirmedProgress) this.options.onProgress(data.confirmedProgress)
      if (data.sessionValid === false || ['EXPIRED', 'CLOSED', 'REPLACED', 'REVOKED'].includes(data.sessionState)) {
        this.options.onPause(); this.options.onState('closed', '本次学习会话已结束，请重新进入以继续。'); this.stop(); return
      }
      this.options.onState('synced')
    } catch (e) {
      this.options.onState('offline', e instanceof Error ? e.message : '进度待同步，请检查网络。')
    } finally { clearTimeout(timeout); this.sending = false; this.abort = undefined }
  }

  async finish(reason = 'USER_EXIT') {
    this.event('CLOSE', false)
    if (this.sending) { const deadline = performance.now() + 13000; while (this.sending && performance.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50)) }
    await this.flush(); this.stop()
    await post(`/learning/sessions/${this.options.sessionId}/close`, { reason }).catch(() => {})
  }

  stop() { this.stopped = true; clearInterval(this.sampleTimer); clearInterval(this.uploadTimer); this.abort?.abort() }
}
