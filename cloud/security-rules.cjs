'use strict';
const { collectionNames } = require('./src/core.cjs');
const active = "get(`database.ys_roles.${auth.openid}`).status == 'ACTIVE'";
const admin = `${active} && get(\`database.ys_roles.\${auth.openid}\`).role == 'ADMIN'`;
const rules = Object.fromEntries(collectionNames.map(name => [`ys_${name}`, { read: false, write: false }]));
for (const name of ['people', 'bindings', 'materials', 'tasks', 'notifications', 'jobs', 'audit']) rules[`ys_${name}`].read = admin;
rules.ys_roles.read = 'doc._openid == auth.openid';
rules.ys_assignments.read = `${active} && (get(\`database.ys_roles.\${auth.openid}\`).role == 'ADMIN' || (doc._openid == auth.openid && doc.visible == true))`;
rules.ys_completions.read = `${active} && (get(\`database.ys_roles.\${auth.openid}\`).role == 'ADMIN' || doc._openid == auth.openid)`;
const functionRules = {
  'ys-training': { invoke: "auth.loginType != 'ANONYMOUS' && auth != null" },
  'ys-jobs': { invoke: false },
  'ys-materials': { invoke: false }
};
module.exports = { rules, storage: { read: false, write: false }, functionRules };
