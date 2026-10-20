'use strict';
const { createHash, randomBytes } = require('node:crypto');
class BusinessError extends Error {
  constructor(code, message, details = {}) { super(message); this.code = code; this.details = details; }
}
function check(condition, code, message, details) {
  if (!condition) throw new BusinessError(code, message, details);
}
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  return JSON.stringify(value);
}
const digest = value => createHash('sha256').update(canonical(value)).digest('hex');
const id = () => randomBytes(16).toString('hex');
const code = () => randomBytes(9).toString('base64url').toUpperCase();
function text(value, label, max = 120) {
  check(typeof value === 'string' && value.trim().length > 0 && value.trim().length <= max, 'INVALID_INPUT', `${label}需为 1–${max} 个字符`);
  return value.trim();
}
function publicDoc(doc) {
  if (!doc) return null;
  const { _id, _openid, ...rest } = doc;
  return { id: _id, ...rest };
}
const collectionNames = ['roles', 'people', 'invites', 'bindings', 'role_requests', 'materials', 'tasks', 'assignments', 'sessions', 'session_locks', 'batches', 'completions', 'jobs', 'notifications', 'subscriptions', 'settings', 'audit', 'commands'];
module.exports = { BusinessError, check, canonical, digest, id, code, text, publicDoc, collectionNames };
