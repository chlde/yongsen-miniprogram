'use strict';
const { cloud, service, errorResult } = require('./src/runtime.cjs');
const build = require('./build-info.json');
exports.main = async event => {
  try {
    const result = await service().dispatch(cloud.getWXContext(), event.action, event.data, event.requestId);
    return { code: 'OK', data: result, build };
  } catch (error) { return { ...errorResult(error), build }; }
};
