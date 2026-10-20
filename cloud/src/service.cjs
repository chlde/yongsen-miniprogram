'use strict';
const { check, digest, id, code, text, publicDoc } = require('./core.cjs');
const { learningActions } = require('./sessions.cjs');
const { adminActions } = require('./admin.cjs');
const { jobActions } = require('./jobs.cjs');
class TrainingService {
  constructor({ repo, provider = {}, now = Date.now, appId = 'wx505c1dc50f1f7099' }) {
    this.repo = repo; this.provider = provider; this.now = now; this.appId = appId;
  }
  async identity(context) {
    check(context && context.OPENID && context.APPID === this.appId, 'UNAUTHENTICATED', '请从永森油服小程序登录');
    return this.repo.tx(async tx => {
      let user = await tx.get('roles', context.OPENID);
      if (!user) {
        user = { _id: context.OPENID, _openid: context.OPENID, role: 'USER', status: 'ACTIVE', bindingStatus: 'UNBOUND', sources: [], createdAt: this.now() };
        await tx.put('roles', user._id, user);
      }
      check(user.status === 'ACTIVE', 'ACCOUNT_DISABLED', '账号已停用，请联系管理员');
      return user;
    });
  }
  async guard(tx, user, admin = false, learner = false) {
    const live = await tx.get('roles', user._id);
    check(live && live.status === 'ACTIVE', 'ACCOUNT_DISABLED', '账号已停用');
    if (admin) check(live.role === 'ADMIN' && live.sources.some(s => ['PLATFORM_ADMIN', 'PROJECT_MEMBER'].includes(s)), 'FORBIDDEN', '需要管理员权限');
    if (learner) {
      check(live.bindingStatus === 'ACTIVE' && live.personId, 'BINDING_REQUIRED', '请先完成员工身份绑定');
      const person = await tx.get('people', live.personId);
      check(person && person.status === 'ACTIVE' && person.openid === live._id, 'PERSON_INACTIVE', '员工身份已停用或解绑');
    }
    return live;
  }
  async audit(tx, user, action, targetId, details = {}) {
    await tx.put('audit', id(), { actor: user._id, action, targetId, details, createdAt: this.now() });
  }
  async dispatch(context, action, data = {}, requestId) {
    check(typeof action === 'string' && action.length <= 80, 'INVALID_INPUT', '操作无效');
    const user = await this.identity(context);
    const fn = this.actions[action];
    check(fn, 'NOT_FOUND', '不支持的操作');
    return fn.call(this, user, data, requestId);
  }
  async assignment(tx, user, assignmentId, allowStopped = false) {
    const live = await this.guard(tx, user, false, true);
    const assignment = await tx.get('assignments', text(assignmentId, '任务编号', 100));
    check(assignment && assignment._openid === live._id && assignment.personId === live.personId, 'NOT_FOUND', '任务不存在');
    const task = await tx.get('tasks', assignment.taskId);
    check(task && (task.status === 'ACTIVE' || (allowStopped && task.status === 'CANCELED')), 'TASK_NOT_ACTIVE', '任务已停止或尚未发布');
    assignment.taskStatus = task.status;
    return assignment;
  }
  refreshProgress(assignment) {
    assignment.progressDigest = digest(assignment.materials.map(m => ({ id: m.id, progress: m.progress })));
    if (assignment.status !== 'COMPLETED') assignment.status = assignment.materials.every(m => m.progress.requirementsMet) ? 'READY_TO_CONFIRM' :
      assignment.materials.some(m => m.progress.effectiveMs > 0) ? 'IN_PROGRESS' : 'NOT_STARTED';
    assignment.updatedAt = this.now();
    return assignment;
  }
}
TrainingService.prototype.actions = {
  async me(user) {
    const live = await this.guard(this.repo, user);
    return { accountId: live._id, role: live.role, bindingStatus: live.bindingStatus, person: live.personId ? sanitizePerson(await this.repo.get('people', live.personId)) : null, providerMode: 'wechat-cloud' };
  },
  async requestRoleCode(user) {
    const claimCode = code(), requestId = digest(claimCode);
    await this.repo.put('role_requests', requestId, { _openid: user._id, status: 'PENDING', expiresAt: this.now() + 600000, createdAt: this.now() });
    return { code: claimCode, expiresAt: this.now() + 600000 };
  },
  async bind(user, data) {
    const key = digest(text(data.inviteCode, '邀请码', 32).toUpperCase());
    return this.repo.tx(async tx => {
      const live = await this.guard(tx, user), invite = await tx.get('invites', key);
      check(live.bindingStatus === 'UNBOUND', 'ALREADY_BOUND', '当前账号已绑定或正在审核');
      check(invite && invite.status === 'ACTIVE' && invite.expiresAt > this.now(), 'INVALID_INVITE', '邀请码无效或已过期');
      const person = await tx.get('people', invite.personId);
      check(person && person.status === 'ACTIVE' && !person.openid, 'PERSON_UNAVAILABLE', '员工已绑定或已停用');
      await tx.put('bindings', user._id, { _openid: user._id, personId: person._id, personName: person.name, status: 'PENDING', inviteId: key, createdAt: this.now() });
      await tx.put('roles', user._id, { ...live, bindingStatus: 'PENDING' });
      return { bindingStatus: 'PENDING' };
    });
  },
  async assignmentDetail(user, data) {
    return publicDoc(await this.assignment(this.repo, user, data.assignmentId, true));
  },
  async entered(user, data) {
    return this.repo.tx(async tx => {
      const assignment = await this.assignment(tx, user, data.assignmentId);
      assignment.enteredAt = assignment.enteredAt || this.now();
      await tx.put('assignments', assignment._id, assignment);
      return { entered: true };
    });
  },
  async mediaTicket(user, data) {
    const assignment = await this.assignment(this.repo, user, data.assignmentId);
    const material = assignment.materials.find(m => m.id === data.taskMaterialId);
    check(material, 'NOT_FOUND', '材料不存在');
    const fileId = material.type === 'MP4' ? material.fileId : (material.pages.find(p => p.pageNo === Number(data.pageNo)) || {}).fileId;
    check(fileId, 'NOT_FOUND', '页面不存在');
    return { url: await this.provider.mediaUrl(fileId) };
  },
  async confirm(user, data) {
    check(data.accepted === true && data.confirmationTextVersion === 'mvp-1', 'CONFIRMATION_REQUIRED', '请本人勾选完成确认');
    return this.repo.tx(async tx => {
      const assignment = await this.assignment(tx, user, data.assignmentId);
      if (assignment.status === 'COMPLETED') return publicDoc(assignment);
      check(assignment.materials.every(m => m.progress.requirementsMet), 'INCOMPLETE', '请先完成全部学习要求');
      check(data.progressDigest === assignment.progressDigest, 'PROGRESS_CHANGED', '学习进度已变化，请刷新后确认');
      assignment.status = 'COMPLETED'; assignment.confirmedAt = this.now();
      await tx.put('completions', assignment._id, { _openid: user._id, personId: assignment.personId, taskId: assignment.taskId, materialProgress: assignment.materials.map(m => ({ id: m.id, progress: m.progress })), progressDigest: assignment.progressDigest, confirmationTextVersion: 'mvp-1', confirmedAt: this.now() });
      await tx.put('assignments', assignment._id, assignment);
      return publicDoc(assignment);
    });
  },
  async notificationSettings() {
    const settings = await this.repo.get('settings', 'notifications');
    return { configured: Boolean(settings && settings.templateId), templateId: settings && settings.templateId, providerMode: 'wechat-cloud' };
  },
  async prepareSubscription(user) {
    const settings = await this.actions.notificationSettings.call(this);
    check(settings.configured, 'NOT_CONFIGURED', '尚未配置微信通知模板');
    const requestId = id(), nonce = code();
    await this.repo.put('subscriptions', requestId, { _openid: user._id, templateId: settings.templateId, nonceHash: digest(nonce), status: 'PREPARED', expiresAt: this.now() + 600000 });
    return { id: requestId, nonce, templateId: settings.templateId };
  },
  async subscriptionResult(user, data) {
    return this.repo.tx(async tx => {
      const request = await tx.get('subscriptions', data.id);
      check(request && request._openid === user._id && request.nonceHash === digest(data.nonce) && request.expiresAt > this.now(), 'INVALID_SUBSCRIPTION', '订阅请求已失效');
      if (request.status === 'PREPARED') await tx.put('subscriptions', request._id, { ...request, status: data.result === 'accept' ? 'ACCEPTED' : 'REJECTED', reportedAt: this.now() });
      return { recorded: true };
    });
  },
  ...learningActions, ...adminActions, ...jobActions
};
function sanitizePerson(person) {
  return person ? { id: person._id, name: person.name, personNo: person.personNo, unit: person.unit, project: person.project, status: person.status } : null;
}
module.exports = { TrainingService, sanitizePerson };
