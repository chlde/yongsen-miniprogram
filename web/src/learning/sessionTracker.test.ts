import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionTracker } from './sessionTracker'
import { api } from '../services/api'

vi.mock('../services/api', () => ({ api: vi.fn(), post: vi.fn().mockResolvedValue({}), requestId: (() => { let n = 0; return () => `request-${++n}` })() }))
const apiMock = vi.mocked(api)

describe('学习采集与服务端确认', () => {
  let tracker: SessionTracker | undefined
  beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'] }); apiMock.mockReset() })
  afterEach(() => { tracker?.stop(); vi.useRealTimers() })
  function create() {
    const onProgress = vi.fn(), onPause = vi.fn(), onState = vi.fn()
    tracker = new SessionTracker({ sessionId: 'session-1', sampleType: 'VIDEO_SAMPLE', payload: () => ({ positionMs: 0, playing: true, appVisible: true }), onProgress, onPause, onState })
    return { tracker, onProgress, onPause, onState }
  }
  it('只在服务端确认后更新进度，不按本地采样提前完成', async () => {
    apiMock.mockResolvedValue({ sessionValid: true, confirmedProgress: { requirementsMet: false, effectiveMs: 0 } })
    const x = create(); await x.tracker.start()
    await vi.advanceTimersByTimeAsync(4000)
    expect(x.onProgress).toHaveBeenCalledTimes(1)
    expect(x.onProgress).toHaveBeenLastCalledWith({ requirementsMet: false, effectiveMs: 0 })
    expect(apiMock).toHaveBeenCalledTimes(1)
  })
  it('上报失败后保留原批次与序号重试，不能生成重复学习事实', async () => {
    apiMock.mockRejectedValueOnce(new Error('network')).mockResolvedValue({ sessionValid: true, confirmedProgress: { effectiveMs: 0 } })
    const x = create(); await x.tracker.start(); await x.tracker.flush()
    const first = JSON.parse(apiMock.mock.calls[0][1]!.body as string)
    const retried = JSON.parse(apiMock.mock.calls[1][1]!.body as string)
    expect(retried).toEqual(first)
    expect(retried.events[0].seq).toBe(1)
  })
  it('10秒无确认暂停真实播放器，过期会话不再继续采样', async () => {
    apiMock.mockResolvedValueOnce({ sessionValid: true, confirmedProgress: { effectiveMs: 0 } }).mockImplementation(() => new Promise(() => {}))
    const x = create(); await x.tracker.start(); await vi.advanceTimersByTimeAsync(10001)
    expect(x.onPause).toHaveBeenCalledTimes(1)
    expect(x.onState).toHaveBeenCalledWith('offline', expect.stringContaining('10秒'))
  })
  it('会话已被接管时停止并提示，不把新样本记入旧会话', async () => {
    apiMock.mockResolvedValue({ sessionValid: false, sessionState: 'REPLACED', confirmedProgress: { effectiveMs: 3000 } })
    const x = create(); await x.tracker.start(); await vi.advanceTimersByTimeAsync(10000)
    expect(x.onPause).toHaveBeenCalledTimes(1)
    expect(x.onState).toHaveBeenCalledWith('closed', expect.any(String))
    expect(apiMock).toHaveBeenCalledTimes(1)
  })
})
