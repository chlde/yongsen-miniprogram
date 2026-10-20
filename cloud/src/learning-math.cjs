'use strict';
function union(ranges, start, end) {
  const sorted = [...ranges, [start, end]].filter(r => r[1] > r[0]).sort((a, b) => a[0] - b[0]);
  const result = [];
  for (const range of sorted) {
    const last = result[result.length - 1];
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
    else result.push([...range]);
  }
  return result;
}
const length = ranges => ranges.reduce((total, range) => total + range[1] - range[0], 0);
function missing(ranges, end) {
  const result = []; let start = 0;
  for (const range of ranges) { if (range[0] > start) result.push([start, range[0]]); start = Math.max(start, range[1]); }
  if (start < end) result.push([start, end]);
  return result;
}
function initial(material) {
  if (material.type === 'MP4') return { requirementsMet: false, effectiveMs: 0, coveredRangesMs: [], missingRangesMs: [[0, material.durationMs]], coverageRatio: 0 };
  return { requirementsMet: false, effectiveMs: 0, coverageRatio: 0, pageProgress: material.pages.map(p => ({ pageNo: p.pageNo, visibleMs: 0, ranges: [], coverageRatio: 0, requirementsMet: false })) };
}
const boundaries = new Set(['SEEK', 'HIDE', 'WAITING', 'PAGE_LEAVE', 'CLOSE']);
const eventTypes = new Set(['VIDEO_SAMPLE', 'DOC_SAMPLE', 'PLAY', 'PAUSE', 'ENDED', 'SEEK', 'HIDE', 'SHOW', 'WAITING', 'CANPLAY', 'PAGE_ENTER', 'PAGE_LEAVE', 'CLOSE']);
function apply(material, progress, previous, current, budget) {
  if (!previous || boundaries.has(current.type)) return { used: 0, reason: 'BASELINE' };
  const before = previous.payload, after = current.payload;
  const dt = current.elapsedMs - previous.elapsedMs;
  if (!(dt > 0 && dt <= 3000) || ![before, after].every(p => p.appVisible === true && p.pageVisible === true)) return { used: 0, reason: 'NOT_VISIBLE_OR_GAP' };
  if (material.type === 'MP4') {
    const start = before.positionMs, end = after.positionMs, dp = end - start;
    if (![before, after].every(p => p.playerVisible === true && p.playbackRate === 1 && p.buffering === false) ||
      before.playing !== true || !(after.playing === true || ['PAUSE', 'ENDED'].includes(current.type)) ||
      !Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end > material.durationMs || dp <= 0 || dp > 1.2 * dt + 300 ||
      Math.min(dt, dp) > budget + 300) return { used: 0, reason: 'INVALID_PLAYBACK' };
    const used = Math.min(dt, dp, budget);
    if (used <= 0) return { used: 0, reason: 'NO_SERVER_TIME' };
    progress.coveredRangesMs = union(progress.coveredRangesMs, start, end);
    progress.missingRangesMs = missing(progress.coveredRangesMs, material.durationMs);
    progress.effectiveMs += used;
    progress.coverageRatio = length(progress.coveredRangesMs) / material.durationMs;
    progress.requirementsMet = progress.missingRangesMs.every(([a, b]) => (a === 0 || b === material.durationMs) && b - a <= 1000) &&
      length(progress.missingRangesMs) <= material.durationMs / 100;
    return { used, advance: dp, reason: 'COUNTED' };
  }
  const pageNo = after.pageNo, page = material.pages.find(p => p.pageNo === pageNo);
  if (!page || before.pageNo !== pageNo || ![before, after].every(p => p.rendered === true && p.readerUnobscured === true)) return { used: 0, reason: 'PAGE_NOT_READY' };
  const top = Math.max(before.visibleTopPx, after.visibleTopPx), bottom = Math.min(before.visibleBottomPx, after.visibleBottomPx);
  if (!Number.isFinite(top) || !Number.isFinite(bottom) || top < 0 || bottom > page.height || top >= bottom || dt > budget + 300) return { used: 0, reason: 'INVALID_VIEWPORT' };
  const used = Math.min(dt, budget);
  if (!used) return { used: 0, reason: 'NO_SERVER_TIME' };
  const p = progress.pageProgress.find(item => item.pageNo === pageNo);
  p.ranges = union(p.ranges, top, bottom); p.visibleMs += used;
  p.coverageRatio = length(p.ranges) / page.height;
  p.requirementsMet = p.visibleMs >= (material.rule.minPageVisibleMs || 30000) && p.coverageRatio >= 1;
  progress.effectiveMs += used;
  progress.coverageRatio = progress.pageProgress.reduce((n, item) => n + item.coverageRatio, 0) / material.pages.length;
  progress.requirementsMet = progress.pageProgress.every(item => item.requirementsMet);
  return { used, reason: 'COUNTED' };
}
module.exports = { union, length, missing, initial, apply, boundaries, eventTypes };
