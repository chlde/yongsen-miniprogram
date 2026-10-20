'use strict';
const cloud = require('wx-server-sdk');
const tcb = require('@cloudbase/node-sdk');
const { CloudRepository } = require('./repository.cjs');
const { TrainingService } = require('./service.cjs');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const sdk = tcb.init({ env: process.env.TCB_ENV || process.env.SCF_NAMESPACE });
const repo = new CloudRepository(cloud.database());
const provider = {
  async mediaUrl(fileId) {
    const result = await cloud.getTempFileURL({ fileList: [{ fileID: fileId, maxAge: 600 }] });
    const file = result.fileList[0];
    if (!file || file.status !== 0 || !file.tempFileURL) throw new Error('教材暂时无法访问，请稍后重试');
    return file.tempFileURL;
  },
  async uploadTicket(cloudPath) {
    const result = await sdk.getUploadMetadata({ cloudPath });
    if (!result.data) throw new Error('上传凭据获取失败');
    const { url, token, authorization, fileId, cosFileId } = result.data;
    return { uploadUrl: url, fileId, uploadHeaders: { authorization, 'x-cos-security-token': token, 'x-cos-meta-fileid': cosFileId } };
  },
  async processMaterial(materialId) {
    const result = await cloud.callFunction({ name: 'ys-materials', data: { materialId } });
    if (!result.result || result.result.code !== 'OK') throw Object.assign(new Error(result.result && result.result.message || '教材处理失败'), { code: result.result && result.result.code });
  },
  async sendNotification(notice, settings) {
    const values = { title: notice.title.slice(0, 20), company: '永森油服', dueAt: notice.dueAt ? new Date(notice.dueAt + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ') : '请及时学习' };
    const data = Object.fromEntries(Object.entries(settings.fields).map(([key, value]) => [key, { value: values[value] }]));
    const result = await cloud.openapi({ appid: 'wx505c1dc50f1f7099' }).subscribeMessage.send({
      touser: notice._openid, templateId: settings.templateId, page: `pages/task/index?assignmentId=${notice.assignmentId}`, data,
      miniprogramState: process.env.WECHAT_RELEASE_STATE || 'formal', lang: 'zh_CN'
    });
    if (result.errCode) throw Object.assign(new Error(result.errMsg), { errCode: result.errCode });
  }
};
function service() { return new TrainingService({ repo, provider }); }
function errorResult(error) {
  if (error.code && error.name !== 'CloudSDKError') return { code: error.code, message: error.message, details: error.details || {} };
  console.error('Cloud operation failed', String(error.code || error.name), String(error.message).replace(/https?:\/\/\S+/g, '[url]').slice(0, 400));
  return { code: 'CLOUD_ERROR', message: '云端处理未完成，请稍后重试' };
}
module.exports = { cloud, sdk, repo, service, errorResult };
