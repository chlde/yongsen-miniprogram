'use strict';
const { check, digest, text } = require('./core.cjs');
const math = require('./learning-math.cjs');
async function start(user, data, requestId, takeover) {
  const sessionId = digest(`${user._id}:${text(requestId, '请求编号', 160)}`).slice(0, 40);
  return this.repo.tx(async tx => {
    const assignment = await this.assignment(tx, user, data.assignmentId);
    check(assignment.status !== 'COMPLETED', 'ALREADY_COMPLETED', '该任务已完成');
    const material = assignment.materials.find(m => m.id === data.taskMaterialId);
    check(material, 'NOT_FOUND', '学习材料不存在');
    const same = await tx.get('sessions', sessionId);
    if (same) {
      check(same.assignmentId === assignment._id && same.materialId === material.id, 'IDEMPOTENCY_CONFLICT', '重复请求内容不一致');
      return sessionResponse(same, material.progress, this.now());
    }
    const lock = await tx.get('session_locks', user._id);
    if (lock && lock.expiresAt > this.now()) {
      check(takeover && data.expectedSessionId === lock.sessionId, 'SESSION_IN_USE', '已有学习会话，请确认是否在此设备继续', { sessionId: lock.sessionId });
      const old = await tx.get('sessions', lock.sessionId);
      if (old) await tx.put('sessions', old._id, { ...old, state: 'REPLACED' });
    } else if (takeover) {
      check(!lock || lock.sessionId === data.expectedSessionId, 'SESSION_REPLACED', '会话已变化，请重新进入');
    }
    const session = {
      _id: sessionId, _openid: user._id, assignmentId: assignment._id, materialId: material.id,
      state: 'ACTIVE', startedAt: this.now(), expiresAt: this.now() + 30000, lastReceivedAt: this.now(),
      receivedSeq: 0, verifiedSeq: 0, hashes: {}, pending: {}, previous: null, admittedAdvance: 0, speedWindow: []
    };
    await tx.put('sessions', sessionId, session);
    await tx.put('session_locks', user._id, { sessionId, expiresAt: session.expiresAt });
    return sessionResponse(session, material.progress, this.now());
  });
}
function sessionResponse(session, progress, now, extra = {}) {
  return { sessionId: session._id, sessionState: session.expiresAt > now ? session.state : 'EXPIRED', sessionValid: session.state === 'ACTIVE' && session.expiresAt > now,
    expiresAt: session.expiresAt, receivedThroughSeq: session.receivedSeq, verifiedSeq: session.verifiedSeq, confirmedProgress: progress, ...extra };
}
const learningActions = {
  async startSession(user, data, requestId) { return start.call(this, user, data, requestId, false); },
  async takeoverSession(user, data, requestId) { return start.call(this, user, data, requestId, true); },
  async closeSession(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, false, true);
      const session = await tx.get('sessions', data.sessionId);
      check(session && session._openid === user._id, 'NOT_FOUND', '会话不存在');
      const lock = await tx.get('session_locks', user._id);
      await tx.put('sessions', session._id, { ...session, state: session.state === 'ACTIVE' ? 'CLOSED' : session.state, closedAt: this.now() });
      if (lock && lock.sessionId === session._id) await tx.put('session_locks', user._id, { ...lock, expiresAt: 0 });
      return { closed: true };
    });
  },
  async ingest(user, data) {
    check(Array.isArray(data.events) && data.events.length > 0 && data.events.length <= 50, 'INVALID_INPUT', '每批需包含 1–50 条学习事件');
    check(Buffer.byteLength(JSON.stringify(data)) <= 100000, 'INVALID_INPUT', '学习事件过大');
    const batchId = digest(`${user._id}:${data.sessionId}:${text(data.batchId, '批次编号', 160)}`), hash = digest(data);
    return this.repo.tx(async tx => {
      const session = await tx.get('sessions', data.sessionId);
      check(session && session._openid === user._id, 'NOT_FOUND', '学习会话不存在');
      const assignment = await this.assignment(tx, user, session.assignmentId);
      const material = assignment.materials.find(m => m.id === session.materialId);
      const receipt = await tx.get('batches', batchId);
      if (receipt) {
        check(receipt.hash === hash, 'BATCH_CONFLICT', '批次内容发生变化');
        return sessionResponse(session, material.progress, this.now(), { eventResults: receipt.result.eventResults });
      }
      const lock = await tx.get('session_locks', user._id);
      check(session.state === 'ACTIVE' && lock && lock.sessionId === session._id, 'SESSION_REPLACED', '学习会话已结束或已切换');
      check(session.expiresAt > this.now(), 'SESSION_EXPIRED', '学习会话已过期，请重新开始');
      check(assignment.status !== 'COMPLETED', 'ALREADY_COMPLETED', '该任务已完成');
      let budget = Math.max(0, this.now() - session.lastReceivedAt);
      if (budget > 15000) { budget = 0; session.previous = null; session.speedWindow = []; }
      const results = [];
      for (const event of data.events) {
        const valid = Number.isInteger(event.seq) && event.seq > 0 && event.seq <= session.verifiedSeq + 200 &&
          Number.isInteger(event.elapsedMs) && event.elapsedMs >= 0 && event.elapsedMs <= this.now() - session.startedAt + 1000 &&
          math.eventTypes.has(event.type) && event.payload && typeof event.payload === 'object' && !Array.isArray(event.payload);
        if (!valid) { results.push({ seq: event.seq, accepted: false, counted: false, reason: 'INVALID_EVENT' }); continue; }
        const eventHash = digest(event);
        if (session.hashes[event.seq] && session.hashes[event.seq] !== eventHash) {
          results.push({ seq: event.seq, accepted: false, counted: false, reason: 'EVENT_SEQ_CONFLICT' }); continue;
        }
        if (event.seq <= session.verifiedSeq || session.pending[event.seq]) {
          results.push({ seq: event.seq, accepted: true, counted: false, reason: 'DUPLICATE' }); continue;
        }
        session.hashes[event.seq] = eventHash;
        session.pending[event.seq] = { ...event, receivedAt: this.now() };
        session.receivedSeq = Math.max(session.receivedSeq, event.seq);
        results.push({ seq: event.seq, accepted: true, counted: false, reason: 'PERSISTED' });
      }
      let next = session.verifiedSeq + 1;
      const pendingSequences = () => Object.keys(session.pending).map(Number).sort((a, b) => a - b);
      while (pendingSequences().length) {
        if (!session.pending[next]) {
          const first = session.pending[pendingSequences()[0]];
          if (this.now() - first.receivedAt < 15000) break;
          session.verifiedSeq = first.seq - 1; next = first.seq; session.previous = null; session.speedWindow = []; budget = 0;
        }
        const event = session.pending[next];
        const prior = session.previous;
        const snapshot = structuredClone(material.progress);
        let counted = math.apply(material, material.progress, prior, event, budget);
        if (material.type === 'MP4' && counted.used > 0) {
          const window = [...session.speedWindow.filter(item => item.at > event.elapsedMs - 30000), { at: event.elapsedMs, dp: counted.advance, dt: event.elapsedMs - prior.elapsedMs }];
          const tooFast = window.reduce((s, item) => s + item.dp - item.dt, 0) > 1000 ||
            session.admittedAdvance + counted.advance > this.now() - session.startedAt + Math.min(1000, material.durationMs / 100);
          if (tooFast) { material.progress = snapshot; counted = { used: 0, reason: 'SERVER_CLOCK_LIMIT' }; }
          else { session.speedWindow = window; session.admittedAdvance += counted.advance; }
        }
        budget -= counted.used;
        const result = results.find(item => item.seq === next);
        if (result) { result.counted = counted.used > 0; result.reason = counted.reason; }
        session.previous = math.boundaries.has(event.type) ? null : event;
        if (math.boundaries.has(event.type)) session.speedWindow = [];
        session.verifiedSeq = next; delete session.pending[next]; next++;
      }
      for (const seq of Object.keys(session.hashes)) if (Number(seq) < session.verifiedSeq - 200) delete session.hashes[seq];
      session.lastReceivedAt = this.now(); session.expiresAt = this.now() + 30000;
      this.refreshProgress(assignment);
      const response = sessionResponse(session, material.progress, this.now(), { eventResults: results });
      await tx.put('sessions', session._id, session);
      await tx.put('session_locks', user._id, { sessionId: session._id, expiresAt: session.expiresAt });
      await tx.put('assignments', assignment._id, assignment);
      await tx.put('batches', batchId, { _openid: user._id, sessionId: session._id, hash, events: data.events, result: response, createdAt: this.now() });
      return response;
    });
  }
};
module.exports = { learningActions };
