/* Protocol-only collector. All completion decisions belong to the server. */
class SessionTracker {
  constructor(options) {
    this.transport = options.transport;
    this.clock = options.clock || (() => Date.now());
    this.makeId = options.makeId || (() => `${Date.now()}-${Math.random().toString(16).slice(2)}`);
    this.sample = options.sample;
    this.onProgress = options.onProgress || (() => {});
    this.onStall = options.onStall || (() => {});
    this.onError = options.onError || (() => {});
    this.setIntervalFn = options.setInterval || setInterval;
    this.clearIntervalFn = options.clearInterval || clearInterval;
    this.queue = []; this.diagnostics = []; this.pending = null; this.inFlight = null;
    this.nextSeq = 1; this.started = false; this.stalled = false; this.disposed = false;
    this.visible = true; this.lastFlush = 0;
  }
  async start(sessionId, baselineType, payload) {
    this.sessionId = sessionId; this.origin = this.clock(); this.lastClock = this.origin;
    this.lastAck = this.origin; this.lastFlush = this.origin;
    this.enqueue(baselineType, payload);
    const result = await this.flush();
    if (!result || this.disposed || this.stalled || result.sessionValid === false || (result.sessionState && result.sessionState !== 'ACTIVE')) throw new Error('无法确认学习会话，请联网后重新开始');
    this.started = true;
    this.timer = this.setIntervalFn(() => this.tick(), 1000);
    return result;
  }
  now() {
    const value = this.clock();
    if (value < this.lastClock) { this.stopFor('设备计时发生变化，请重新开始学习'); return null; }
    this.lastClock = value;
    return value;
  }
  enqueue(type, payload) {
    if (this.disposed || !this.sessionId) return null;
    const now = this.now();
    if (now === null) return null;
    const event = { eventId: this.makeId(), seq: this.nextSeq++, type, elapsedMs: Math.round(now - this.origin), payload: JSON.parse(JSON.stringify(payload || {})) };
    this.queue.push(event);
    return event;
  }
  emit(type, payload) {
    if (this.disposed) return Promise.resolve(null);
    this.enqueue(type, payload);
    return this.flush();
  }
  tick() {
    if (this.disposed || !this.started || !this.visible) return;
    const now = this.now();
    if (now === null) return;
    if (now - this.lastAck >= 10000) this.stopFor('网络异常，进度待同步。请联网后继续');
    if (!this.stalled) {
      const sample = this.sample();
      if (sample) this.enqueue(sample.type, sample.payload);
    }
    if (now - this.lastFlush >= 5000) { this.lastFlush = now; this.flush().catch(() => {}); }
  }
  setVisible(visible) { this.visible = visible; }
  async finish(payload) {
    // Stop the timer before draining; an existing request must finish before CLOSE is sent.
    this.visible = false; this.started = false;
    if (this.timer) this.clearIntervalFn(this.timer);
    this.timer = null;
    this.enqueue('CLOSE', payload);
    try {
      if (this.inFlight) await this.inFlight;
      while (this.queue.length && !this.disposed) {
        const before = this.queue.length;
        await this.flush();
        if (this.queue.length >= before) break;
      }
    } finally { this.dispose(); }
  }
  stopFor(message) {
    if (this.stalled || this.disposed) return;
    this.stalled = true; this.onStall(message);
  }
  async flush() {
    if (this.disposed) return null;
    if (this.inFlight) return this.inFlight;
    if (!this.pending && !this.queue.length) return null;
    if (!this.pending) this.pending = { batchId: this.makeId(), clientVersion: '1.0.0', events: this.queue.slice(0, 50) };
    const batch = this.pending;
    this.inFlight = (async () => {
      try {
        const result = await this.transport(this.sessionId, batch);
        if (this.disposed) return result;
        // Persistence ACK and progress ACK are distinct; only server progress is displayed.
        const responseSeq = Math.max(Number(result.receivedThroughSeq === undefined ? result.receivedSeq : result.receivedThroughSeq) || 0, Number(result.verifiedSeq) || 0);
        this.diagnostics.push({ batch, response: result });
        if (this.diagnostics.length > 12) this.diagnostics.shift();
        const accepted = new Set((result.eventResults || []).filter(item => item.accepted).map(item => item.seq));
        const rejected = new Set((result.eventResults || []).filter(item => item.accepted === false).map(item => item.seq));
        const sent = new Set(batch.events.map(event => event.seq));
        this.queue = this.queue.filter(event => !sent.has(event.seq) || !(event.seq <= responseSeq || accepted.has(event.seq) || rejected.has(event.seq)));
        this.pending = null;
        this.lastAck = this.clock();
        if (result.sessionValid === false || (result.sessionState && result.sessionState !== 'ACTIVE')) this.stopFor('学习会话已结束，请重新进入继续');
        this.onProgress(result.confirmedProgress || {}, { pendingCount: this.queue.length, response: result });
        const conflict = (result.eventResults || []).find(item => item.reason === 'EVENT_SEQ_CONFLICT');
        if (conflict) this.stopFor('学习记录需要重新同步，请重新进入继续');
        return result;
      } catch (error) {
        if (['SESSION_EXPIRED', 'SESSION_REVOKED', 'SESSION_REPLACED', 'TASK_NOT_ACTIVE', 'PERSON_INACTIVE', 'BINDING_REQUIRED'].includes(error.code)) this.stopFor(error.message || '学习会话不可用，请重新进入');
        this.onError(error);
        throw error;
      } finally { this.inFlight = null; }
    })();
    return this.inFlight;
  }
  dispose() {
    this.disposed = true; this.started = false;
    if (this.timer) this.clearIntervalFn(this.timer);
    this.timer = null;
  }
}
function visiblePdfRange({ imageTop, imageHeight, viewportTop, viewportHeight, sourceHeight, screenTop = 0, screenHeight }) {
  if (!(imageHeight > 0 && viewportHeight > 0 && sourceHeight > 0 && screenHeight > 0)) return null;
  const top = Math.max(imageTop, viewportTop, screenTop);
  const bottom = Math.min(imageTop + imageHeight, viewportTop + viewportHeight, screenTop + screenHeight);
  if (bottom <= top) return null;
  return { visibleTopPx: Math.max(0, Math.floor((top - imageTop) / imageHeight * sourceHeight)), visibleBottomPx: Math.min(sourceHeight, Math.ceil((bottom - imageTop) / imageHeight * sourceHeight)) };
}
function visibleFraction(rect, screen) {
  if (!rect || !screen || !(rect.width > 0 && rect.height > 0 && screen.width > 0 && screen.height > 0)) return 0;
  const width = Math.max(0, Math.min(rect.left + rect.width, screen.left + screen.width) - Math.max(rect.left, screen.left));
  const height = Math.max(0, Math.min(rect.top + rect.height, screen.top + screen.height) - Math.max(rect.top, screen.top));
  return Math.min(1, width * height / (rect.width * rect.height));
}
module.exports = { SessionTracker, visiblePdfRange, visibleFraction };
