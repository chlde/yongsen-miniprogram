'use strict';
const { cloud, service, errorResult } = require('./src/runtime.cjs');
const { runJobs } = require('./src/jobs.cjs');
exports.main = async event => {
  try {
    // Function permissions also deny client invocation. Never trust event.Type.
    if (cloud.getWXContext().OPENID) return { code: 'FORBIDDEN', message: '仅允许云端调度' };
    if (event.action === 'health') return { code: 'OK', build: require('./build-info.json') };
    return { code: 'OK', data: await runJobs.call(service()), build: require('./build-info.json') };
  } catch (error) { return errorResult(error); }
};
