'use strict';
const { check, digest, id } = require('./core.cjs');
const math = require('./learning-math.cjs');
const jobActions = {
  async runJobs(user) { await this.guard(this.repo, user, true); return runJobs.call(this); },
  async remindTask(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const task = await tx.get('tasks', data.taskId);
      check(task && task.status === 'ACTIVE', 'TASK_NOT_ACTIVE', '任务尚未发布或已停止');
      check(!task.lastRemindedAt || this.now() - task.lastRemindedAt >= 3600000, 'RATE_LIMITED', '同一任务每小时最多提醒一次');
      const round = id();
      await tx.put('jobs', `remind-${round}`, { type: 'REMIND', taskId: task._id, round, state: 'PENDING', cursor: '', attempts: 0, createdAt: this.now() });
      await tx.put('tasks', task._id, { ...task, lastRemindedAt: this.now() });
      await this.audit(tx, user, 'REMIND_TASK', task._id);
      return { queued: true };
    });
  }
};
async function runJobs() {
  const jobs = [...await this.repo.list('jobs', { state: 'PENDING' }, 30), ...await this.repo.list('jobs', { state: 'RUNNING' }, 30)];
  const results = [];
  for (const job of jobs) {
    if (job.state === 'RUNNING' && job.leaseUntil > this.now()) continue;
    const owner = id();
    const claimed = await this.repo.tx(async tx => {
      const live = await tx.get('jobs', job._id);
      if (!live || live.state === 'DONE' || live.state === 'FAILED' || (live.state === 'RUNNING' && live.leaseUntil > this.now())) return null;
      const next = { ...live, state: 'RUNNING', owner, leaseUntil: this.now() + 300000, attempts: (live.attempts || 0) + 1 };
      await tx.put('jobs', live._id, next);
      return next;
    });
    if (!claimed) continue;
    try {
      let done;
      if (claimed.type === 'PUBLISH') done = await publishChunk.call(this, claimed);
      else if (claimed.type === 'MATERIAL') { await this.provider.processMaterial(claimed.materialId); done = true; }
      else if (claimed.type === 'REMIND') done = await remindChunk.call(this, claimed);
      else throw new Error('Unknown job type');
      results.push({ id: claimed._id, done });
    } catch (error) {
      await this.repo.tx(async tx => {
        const live = await tx.get('jobs', claimed._id);
        if (live && live.owner === owner) await tx.put('jobs', live._id, { ...live, state: error.code === 'PROCESSING_BUSY' ? 'PENDING' : 'FAILED', error: String(error.message).slice(0, 300), failedAt: this.now() });
      });
      results.push({ id: claimed._id, error: String(error.message).slice(0, 300) });
    }
    if (claimed.type === 'MATERIAL') await finish.call(this, claimed, true);
  }
  await sendNotifications.call(this);
  return { results };
}
async function finish(job, done) {
  await this.repo.tx(async tx => {
    const live = await tx.get('jobs', job._id);
    if (live && live.owner === job.owner && live.state === 'RUNNING') await tx.put('jobs', live._id, { ...live, state: done ? 'DONE' : 'PENDING', completedAt: done ? this.now() : null });
  });
}
async function publishChunk(job) {
  if (job.phase === 'EXPOSE') {
    const rows = await this.repo.list('assignments', { taskId: job.taskId }, 30, job.after || '');
    return this.repo.tx(async tx => {
      const live = await tx.get('jobs', job._id), task = await tx.get('tasks', job.taskId);
      if (!live || live.owner !== job.owner || live.state !== 'RUNNING') return false;
      if (!task || task.status !== 'ACTIVE') { await tx.put('jobs', live._id, { ...live, state: 'DONE' }); return true; }
      for (const row of rows) {
        const current = await tx.get('assignments', row._id);
        if (current) await tx.put('assignments', row._id, { ...current, visible: true });
      }
      const done = rows.length < 30;
      await tx.put('jobs', live._id, { ...live, state: done ? 'DONE' : 'PENDING', after: rows.length ? rows[rows.length - 1]._id : '' });
      return done;
    });
  }
  return this.repo.tx(async tx => {
    const task = await tx.get('tasks', job.taskId), live = await tx.get('jobs', job._id);
    if (!live || live.owner !== job.owner || live.state !== 'RUNNING') return false;
    if (!task || task.status !== 'PUBLISHING') { await tx.put('jobs', live._id, { ...live, state: 'DONE' }); return true; }
    const end = Math.min(live.cursor + 15, task.personIds.length);
    for (const personId of task.personIds.slice(live.cursor, end)) {
      const person = await tx.get('people', personId);
      check(person && person.status === 'ACTIVE' && person.openid, 'UNBOUND_RECIPIENT', `员工 ${person ? person.name : personId} 尚未绑定有效微信账号`);
      const user = await tx.get('roles', person.openid);
      check(user && user.status === 'ACTIVE' && user.bindingStatus === 'ACTIVE' && user.personId === personId, 'UNBOUND_RECIPIENT', '员工绑定状态无效');
      const assignmentId = digest(`${task._id}:${personId}`).slice(0, 40);
      const existing = await tx.get('assignments', assignmentId);
      if (!existing) {
        const assignment = this.refreshProgress({ _openid: person.openid, visible: false, taskId: task._id, title: task.title, description: task.description, dueAt: task.dueAt, personId, personName: person.name, taskStatus: 'ACTIVE', status: 'NOT_STARTED', materials: task.materials.map(m => ({ ...m, progress: math.initial(m) })), confirmationTextVersion: 'mvp-1', createdAt: this.now() });
        await tx.put('assignments', assignmentId, assignment);
        await tx.put('notifications', `${assignmentId}-initial`, { _openid: person.openid, taskId: task._id, assignmentId, title: task.title, dueAt: task.dueAt, state: 'PENDING', createdAt: this.now() });
      }
    }
    const done = end === task.personIds.length;
    await tx.put('tasks', task._id, { ...task, assignedCount: end, status: done ? 'ACTIVE' : 'PUBLISHING' });
    await tx.put('jobs', live._id, { ...live, cursor: end, phase: done ? 'EXPOSE' : 'CREATE', state: 'PENDING' });
    return false;
  });
}
async function remindChunk(job) {
  const assignments = await this.repo.list('assignments', { taskId: job.taskId }, 30, job.cursor);
  return this.repo.tx(async tx => {
    const live = await tx.get('jobs', job._id), task = await tx.get('tasks', job.taskId);
    if (!live || live.owner !== job.owner || live.state !== 'RUNNING') return false;
    if (!task || task.status !== 'ACTIVE') { await tx.put('jobs', live._id, { ...live, state: 'DONE' }); return true; }
    for (const assignment of assignments) {
      if (assignment.status === 'COMPLETED') continue;
      const notificationId = `${assignment._id}-${job.round}`;
      await tx.put('notifications', notificationId, { _openid: assignment._openid, taskId: task._id, assignmentId: assignment._id, title: task.title, dueAt: task.dueAt, state: 'PENDING', createdAt: this.now() });
    }
    const done = assignments.length < 30;
    await tx.put('jobs', live._id, { ...live, state: done ? 'DONE' : 'PENDING', cursor: assignments.length ? assignments[assignments.length - 1]._id : job.cursor });
    return done;
  });
}
async function sendNotifications() {
  // A timeout after dispatch is UNKNOWN, never silently retried (WeChat has no
  // idempotency key). ACCEPTED means API acceptance, not delivery/read receipt.
  const stuck = await this.repo.list('notifications', { state: 'SENDING' }, 20);
  for (const notice of stuck) if (notice.leaseUntil < this.now()) await this.repo.tx(async tx => {
    const live = await tx.get('notifications', notice._id);
    if (live && live.state === 'SENDING' && live.leaseUntil < this.now()) await tx.put('notifications', live._id, { ...live, state: 'UNKNOWN' });
  });
  const settings = await this.repo.get('settings', 'notifications');
  if (!settings || !settings.templateId) return;
  // Keep scanning past users who have no subscription quota. Otherwise the
  // first twenty pending rows can permanently starve every later recipient.
  const cursor = await this.repo.get('settings', 'notification-scan');
  let notices = await this.repo.list('notifications', { state: 'PENDING' }, 20, cursor && cursor.after || '');
  if (!notices.length && cursor && cursor.after) notices = await this.repo.list('notifications', { state: 'PENDING' }, 20);
  await this.repo.put('settings', 'notification-scan', { after: notices.length === 20 ? notices[19]._id : '' });
  for (const notice of notices) {
    const grant = (await this.repo.list('subscriptions', { _openid: notice._openid, status: 'ACCEPTED' }, 1))[0];
    if (!grant) continue;
    const claimed = await this.repo.tx(async tx => {
      const live = await tx.get('notifications', notice._id), task = await tx.get('tasks', notice.taskId), subscription = await tx.get('subscriptions', grant._id);
      const account = await tx.get('roles', notice._openid), assignment = await tx.get('assignments', notice.assignmentId);
      if (!live || live.state !== 'PENDING' || !task || task.status !== 'ACTIVE' || !subscription || subscription.status !== 'ACCEPTED') return false;
      if (!account || account.status !== 'ACTIVE' || account.bindingStatus !== 'ACTIVE' || !assignment || assignment.status === 'COMPLETED') {
        await tx.put('notifications', live._id, { ...live, state: 'SKIPPED' }); return false;
      }
      if (subscription.templateId !== settings.templateId) {
        await tx.put('subscriptions', subscription._id, { ...subscription, status: 'OBSOLETE' }); return false;
      }
      await tx.put('subscriptions', subscription._id, { ...subscription, status: 'CONSUMED', notificationId: live._id });
      await tx.put('notifications', live._id, { ...live, state: 'SENDING', leaseUntil: this.now() + 60000 });
      return true;
    });
    if (!claimed) continue;
    let result;
    try { await this.provider.sendNotification(notice, settings); result = { state: 'ACCEPTED', acceptedAt: this.now() }; }
    catch (error) { result = { state: Number.isInteger(error.errCode) && error.errCode !== 0 ? 'REJECTED' : 'UNKNOWN', errorCode: String(error.errCode || ''), error: String(error.message).slice(0, 180) }; }
    await this.repo.tx(async tx => {
      const live = await tx.get('notifications', notice._id);
      if (live && ['SENDING', 'UNKNOWN'].includes(live.state)) await tx.put('notifications', live._id, { ...live, ...result });
    });
  }
}
module.exports = { jobActions, runJobs };
