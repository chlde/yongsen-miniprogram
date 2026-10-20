'use strict';
const { collectionNames } = require('./core.cjs');
const clone = value => value === undefined ? undefined : structuredClone(value);
class MemoryRepository {
  constructor() { this.rows = new Map(); this.tail = Promise.resolve(); }
  key(table, id) { return `${table}/${id}`; }
  async get(table, id) { return clone(this.rows.get(this.key(table, id))) || null; }
  async put(table, id, doc) { this.rows.set(this.key(table, id), clone({ ...doc, _id: id })); }
  async remove(table, id) { this.rows.delete(this.key(table, id)); }
  async list(table, where = {}, limit = 100, after = '') {
    return [...this.rows.entries()].filter(([k, v]) => k.startsWith(`${table}/`) && v._id > after && Object.entries(where).every(([f, x]) => v[f] === x))
      .map(([, v]) => clone(v)).sort((a, b) => a._id.localeCompare(b._id)).slice(0, limit);
  }
  async tx(fn) {
    const previous = this.tail;
    let release;
    this.tail = new Promise(resolve => { release = resolve; });
    await previous;
    const saved = clone(this.rows);
    try { return await fn(this); } catch (error) { this.rows = saved; throw error; } finally { release(); }
  }
}
class CloudRepository {
  constructor(database, handle = database, transactional = false) { this.db = database; this.handle = handle; this.transactional = transactional; }
  collection(table) {
    if (!collectionNames.includes(table)) throw new Error('Unknown collection');
    return this.handle.collection(`ys_${table}`);
  }
  async get(table, id) {
    try {
      const result = await this.collection(table).doc(id).get();
      return Array.isArray(result.data) ? result.data[0] || null : result.data || null;
    } catch (error) {
      if (/document.*(not exist|not found)|does not exist|DATABASE_DOCUMENT_NOT_EXIST/i.test(error.message)) return null;
      throw error;
    }
  }
  async put(table, id, doc) {
    const { _id, ...data } = doc;
    await this.collection(table).doc(id).set({ data });
  }
  async remove(table, id) { await this.collection(table).doc(id).remove(); }
  async list(table, where = {}, limit = 100, after = '') {
    const query = after ? { ...where, _id: this.db.command.gt(after) } : where;
    const result = await this.collection(table).where(query).orderBy('_id', 'asc').limit(limit).get();
    return result.data;
  }
  async tx(fn) {
    if (this.transactional) return fn(this);
    return this.db.runTransaction(transaction => fn(new CloudRepository(this.db, transaction, true)));
  }
}
module.exports = { MemoryRepository, CloudRepository };
