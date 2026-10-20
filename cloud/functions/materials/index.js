'use strict';
const { cloud, repo, sdk, errorResult } = require('./src/runtime.cjs');
const { processMaterial } = require('./src/materials.cjs');
exports.main = async event => {
  try {
    if (cloud.getWXContext().OPENID) return { code: 'FORBIDDEN', message: '仅允许云端处理教材' };
    if (event.action === 'health') {
      const canvas = require('@napi-rs/canvas').createCanvas(1, 1);
      return { code: 'OK', build: require('./build-info.json'), rendererReady: canvas.width === 1 };
    }
    await processMaterial({ repo, sdk, materialId: event.materialId });
    return { code: 'OK', build: require('./build-info.json') };
  } catch (error) { return errorResult(error); }
};
