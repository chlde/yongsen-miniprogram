'use strict';
const { check, digest, id, code, text, publicDoc } = require('./core.cjs');
const adminActions = {
  async adminList(user, data) {
    await this.guard(this.repo, user, true);
    check(['people', 'bindings', 'materials', 'tasks', 'assignments', 'notifications', 'audit', 'roles'].includes(data.table), 'INVALID_INPUT', '列表无效');
    const where = {};
    if (['assignments', 'notifications'].includes(data.table) && data.taskId) where.taskId = data.taskId;
    const rows = await this.repo.list(data.table, where, 100, data.after || '');
    return { items: rows.map(row => {
      const result = publicDoc(row);
      delete result.openid; delete result.actor;
      if (data.table === 'roles') { delete result.id; result.accountId = digest(row._id).slice(0, 16); }
      return result;
    }), next: rows.length === 100 ? rows[99]._id : '' };
  },
  async savePerson(user, data) {
    const personNo = text(data.personNo, '工号', 40);
    const personId = digest(personNo).slice(0, 32);
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const existing = await tx.get('people', personId);
      const person = { ...existing, name: text(data.name, '姓名', 40), personNo, unit: String(data.unit || '').slice(0, 80), project: String(data.project || '').slice(0, 80), status: 'ACTIVE', createdAt: existing ? existing.createdAt : this.now() };
      await tx.put('people', personId, person);
      await this.audit(tx, user, 'SAVE_PERSON', personId);
      return { ...publicDoc(person), id: personId };
    });
  },
  async invitePerson(user, data) {
    const inviteCode = code();
    await this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const person = await tx.get('people', data.personId);
      check(person && person.status === 'ACTIVE' && !person.openid, 'PERSON_UNAVAILABLE', '该员工已绑定或不可用');
      if (person.inviteId) {
        const old = await tx.get('invites', person.inviteId);
        if (old) await tx.put('invites', old._id, { ...old, status: 'REVOKED' });
      }
      const inviteId = digest(inviteCode);
      await tx.put('invites', inviteId, { personId: person._id, status: 'ACTIVE', expiresAt: this.now() + 7 * 86400000 });
      await tx.put('people', person._id, { ...person, inviteId });
      await this.audit(tx, user, 'INVITE_PERSON', person._id);
    });
    return { inviteCode, expiresAt: this.now() + 7 * 86400000 };
  },
  async decideBinding(user, data) {
    check(['APPROVE', 'REJECT'].includes(data.decision), 'INVALID_INPUT', '审核决定无效');
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const binding = await tx.get('bindings', data.id);
      check(binding && binding.status === 'PENDING', 'INVALID_STATE', '申请已处理');
      const person = await tx.get('people', binding.personId), target = await tx.get('roles', binding._openid), invite = await tx.get('invites', binding.inviteId);
      const approve = data.decision === 'APPROVE';
      if (approve) check(person && person.status === 'ACTIVE' && !person.openid && invite && invite.status === 'ACTIVE' && invite.expiresAt > this.now(), 'PERSON_UNAVAILABLE', '邀请码已失效或员工已绑定');
      await tx.put('bindings', binding._id, { ...binding, status: approve ? 'APPROVED' : 'REJECTED', reviewedAt: this.now(), reviewedBy: user._id });
      await tx.put('roles', target._id, { ...target, bindingStatus: approve ? 'ACTIVE' : 'UNBOUND', personId: approve ? person._id : '' });
      if (approve) {
        await tx.put('people', person._id, { ...person, openid: target._id });
        await tx.put('invites', invite._id, { ...invite, status: 'USED' });
      }
      await this.audit(tx, user, 'DECIDE_BINDING', binding._id, { decision: data.decision });
      return { status: approve ? 'APPROVED' : 'REJECTED' };
    });
  },
  async setRole(user, data) {
    const sources = [...new Set(data.sources || [])];
    check(sources.every(s => ['PLATFORM_ADMIN', 'PROJECT_MEMBER'].includes(s)), 'INVALID_INPUT', '成员来源无效');
    const evidence = text(data.evidence, '平台成员核验记录', 300);
    return this.repo.tx(async tx => {
      const admin = await this.guard(tx, user, true);
      const request = await tx.get('role_requests', digest(text(data.code, '身份核验码', 32).toUpperCase()));
      check(request && request.status === 'PENDING' && request.expiresAt > this.now(), 'INVALID_CODE', '核验码无效或已过期');
      const target = await tx.get('roles', request._openid);
      check(target && target.status === 'ACTIVE', 'NOT_FOUND', '账号不存在');
      check(target._id !== admin._id || sources.length > 0, 'SELF_DEMOTION', '不能在此移除自己的全部管理权限');
      await tx.put('roles', target._id, { ...target, sources, role: sources.length ? 'ADMIN' : 'USER', platformName: text(data.platformName, '平台成员名称', 80), verifiedAt: this.now(), verifiedBy: admin._id });
      await tx.put('role_requests', request._id, { ...request, status: 'USED' });
      await this.audit(tx, user, 'SET_ROLE', target._id, { sources, evidence });
      return { role: sources.length ? 'ADMIN' : 'USER' };
    });
  },
  async revokeRole(user, data) {
    await this.guard(this.repo, user, true);
    // A stable public account key resolves only within the trusted function.
    let after = '', target;
    do {
      const rows = await this.repo.list('roles', {}, 100, after);
      target = rows.find(row => digest(row._id).slice(0, 16) === data.accountId);
      after = rows.length === 100 ? rows[99]._id : '';
    } while (!target && after);
    check(target && target._id !== user._id, 'INVALID_INPUT', '不能移除本人权限或账号不存在');
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const live = await tx.get('roles', target._id);
      const sources = live.sources.filter(source => source !== data.source);
      await tx.put('roles', target._id, { ...live, sources, role: sources.length ? 'ADMIN' : 'USER', verifiedAt: this.now() });
      await this.audit(tx, user, 'REVOKE_ROLE_SOURCE', target._id, { source: data.source });
      return { role: sources.length ? 'ADMIN' : 'USER' };
    });
  },
  async prepareMaterial(user, data) {
    await this.guard(this.repo, user, true);
    check(['PDF', 'MP4'].includes(data.type), 'INVALID_INPUT', '仅支持 PDF 和 MP4');
    check(Number.isInteger(data.size) && data.size > 0 && data.size <= 100 * 1024 * 1024, 'INVALID_INPUT', '文件需在 100 MB 以内');
    const materialId = id(), uploadPath = `staging/${materialId}/source.${data.type.toLowerCase()}`;
    await this.repo.put('materials', materialId, { title: text(data.title, '教材名称'), type: data.type, expectedSize: data.size, uploadPath, status: 'UPLOADING', createdBy: user._id, createdAt: this.now() });
    return { materialId, uploadPath, ...(await this.provider.uploadTicket(uploadPath)) };
  },
  async finishMaterial(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const material = await tx.get('materials', data.materialId);
      check(material, 'NOT_FOUND', '教材不存在');
      if (material.status !== 'UPLOADING') return publicDoc(material);
      await tx.put('materials', material._id, { ...material, status: 'PROCESSING' });
      await tx.put('jobs', `material-${material._id}`, { type: 'MATERIAL', materialId: material._id, state: 'PENDING', createdAt: this.now(), attempts: 0 });
      return { id: material._id, status: 'PROCESSING' };
    });
  },
  async createTask(user, data, requestId) {
    const taskId = digest(text(requestId, '请求编号', 160)).slice(0, 32), inputHash = digest(data);
    const personIds = [...new Set(data.personIds || [])];
    check(personIds.length > 0 && personIds.length <= 500 && Array.isArray(data.materials) && data.materials.length > 0 && data.materials.length <= 10, 'INVALID_INPUT', '每个任务需 1–500 名员工、1–10 份教材');
    check(new Set(data.materials.map(m => m.materialId)).size === data.materials.length, 'INVALID_INPUT', '教材不能重复');
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const previous = await tx.get('tasks', taskId);
      if (previous) { check(previous.inputHash === inputHash, 'IDEMPOTENCY_CONFLICT', '重复请求内容不一致'); return publicDoc(previous); }
      const materials = [];
      for (const item of data.materials) {
        const material = await tx.get('materials', item.materialId);
        check(material && material.status === 'READY', 'MATERIAL_NOT_READY', '教材尚未处理完成');
        const minPageVisibleMs = Number(item.rule && item.rule.minPageVisibleMs || 30000);
        check(Number.isInteger(minPageVisibleMs) && minPageVisibleMs >= 30000 && minPageVisibleMs <= 600000, 'INVALID_INPUT', '每页学习时间需 30–600 秒');
        materials.push({ id: material._id, taskMaterialId: material._id, title: material.title, type: material.type, fileId: material.fileId || '', durationMs: material.durationMs || 0, pages: material.pages || [], pageCount: (material.pages || []).length, rule: { playbackRate: 1, minPageVisibleMs } });
      }
      const dueAt = data.dueAt ? new Date(data.dueAt).getTime() : null;
      check(!data.dueAt || (Number.isFinite(dueAt) && dueAt > this.now()), 'INVALID_INPUT', '截止时间需晚于当前时间');
      const task = { title: text(data.title, '任务名称'), description: String(data.description || '').slice(0, 2000), dueAt, personIds, materials, status: 'DRAFT', inputHash, createdBy: user._id, createdAt: this.now(), assignedCount: 0 };
      await tx.put('tasks', taskId, task);
      await this.audit(tx, user, 'CREATE_TASK', taskId);
      return { id: taskId, ...task };
    });
  },
  async publishTask(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const task = await tx.get('tasks', data.taskId);
      check(task, 'NOT_FOUND', '任务不存在');
      if (['PUBLISHING', 'ACTIVE'].includes(task.status)) return publicDoc(task);
      check(task.status === 'DRAFT', 'INVALID_STATE', '该任务不能发布');
      // Recipient and material identities freeze here. Fan-out validates every employee
      // before exposing any assignment; an unbound employee is an actionable failure.
      task.status = 'PUBLISHING'; task.publishedAt = this.now();
      await tx.put('tasks', task._id, task);
      await tx.put('jobs', `publish-${task._id}`, { type: 'PUBLISH', taskId: task._id, state: 'PENDING', cursor: 0, attempts: 0, createdAt: this.now() });
      await this.audit(tx, user, 'PUBLISH_TASK', task._id);
      return publicDoc(task);
    });
  },
  async cancelTask(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const task = await tx.get('tasks', data.taskId);
      check(task, 'NOT_FOUND', '任务不存在');
      await tx.put('tasks', task._id, { ...task, status: 'CANCELED', canceledAt: this.now() });
      await this.audit(tx, user, 'CANCEL_TASK', task._id);
      return { status: 'CANCELED' };
    });
  },
  async saveNotificationSettings(user, data) {
    await this.guard(this.repo, user, true);
    const templateId = text(data.templateId, '订阅模板 ID', 100);
    const fields = data.fields || {};
    check(Object.keys(fields).length > 0 && Object.keys(fields).length <= 5 && Object.entries(fields).every(([key, value]) => /^(thing|date|time|character_string|phrase|number)\d+$/.test(key) && ['title', 'dueAt', 'company'].includes(value)), 'INVALID_INPUT', '请配置模板字段与任务信息的对应关系');
    await this.repo.put('settings', 'notifications', { templateId, fields, updatedAt: this.now() });
    return { configured: true };
  },
  async testOwnNotification(user, data) {
    await this.guard(this.repo, user, true);
    const assignment = await this.assignment(this.repo, user, data.assignmentId);
    const settings = await this.repo.get('settings', 'notifications');
    check(settings && settings.templateId, 'NOT_CONFIGURED', '尚未配置通知模板');
    const noticeId = `${assignment._id}-initial`;
    const notice = await this.repo.tx(async tx => {
      await this.guard(tx, user, true, true);
      const current = await tx.get('notifications', noticeId);
      check(current && current._openid === user._id && current.state === 'PENDING', 'INVALID_STATE', '该通知已经处理');
      await tx.put('notifications', noticeId, { ...current, state: 'SENDING', leaseUntil: this.now() + 60000, testAttempt: true });
      await this.audit(tx, user, 'TEST_OWN_NOTIFICATION', noticeId);
      return current;
    });
    // This self-test never invents an authorization grant. WeChat remains the
    // authority on subscription quota and may reject with 43101.
    let result;
    try { await this.provider.sendNotification(notice, settings); result = { state: 'ACCEPTED', acceptedAt: this.now() }; }
    catch (error) { result = { state: error.errCode ? 'REJECTED' : 'UNKNOWN', errorCode: String(error.errCode || error.code || ''), error: String(error.message).slice(0, 300) }; }
    await this.repo.tx(async tx => {
      const current = await tx.get('notifications', noticeId);
      await tx.put('notifications', noticeId, { ...current, ...result });
    });
    return result;
  },
  async retryJob(user, data) {
    return this.repo.tx(async tx => {
      await this.guard(tx, user, true);
      const job = await tx.get('jobs', data.id);
      check(job && job.state === 'FAILED' && job.type !== 'NOTIFY', 'INVALID_STATE', '任务不支持重试');
      if (job.type === 'MATERIAL') {
        const material = await tx.get('materials', job.materialId);
        if (material && material.status === 'REJECTED') await tx.put('materials', material._id, { ...material, status: 'PROCESSING', error: '', processingUntil: 0 });
      }
      await tx.put('jobs', job._id, { ...job, state: 'PENDING', error: '', attempts: 0 });
      return { queued: true };
    });
  }
};
module.exports = { adminActions };
