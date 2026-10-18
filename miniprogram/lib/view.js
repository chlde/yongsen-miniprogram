const statuses = { NOT_STARTED: '待学习', IN_PROGRESS: '学习中', READY_TO_CONFIRM: '待本人确认', COMPLETED: '已完成' };
function dateLabel(value) {
  if (!value) return '未设置';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '未设置';
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}
function enrichAssignment(item) {
  return Object.assign({}, item, { stateLabel: statuses[item.status] || item.status, dueLabel: dateLabel(item.dueAt), completedLabel: dateLabel(item.confirmedAt || item.completedAt), overdue: item.status !== 'COMPLETED' && Boolean(item.dueAt) && new Date(item.dueAt).getTime() < Date.now() });
}
function enrichMaterial(item) {
  const progress = item.progress || {};
  return Object.assign({}, item, { isVideo: item.type === 'MP4', coveragePercent: Math.floor((progress.coverageRatio || 0) * 100), done: Boolean(progress.requirementsMet), subtitle: item.type === 'MP4' ? `${Math.ceil((item.durationMs || 0) / 60000)} 分钟 · 1 倍速` : `${item.pageCount || 0} 页 · 逐页学习`, typeLabel: item.type === 'MP4' ? '视频' : '文档' });
}
function toastError(error) { if (!error.authExpired) wx.showToast({ title: error.message || '操作未完成，请重试', icon: 'none', duration: 2600 }); }
module.exports = { dateLabel, enrichAssignment, enrichMaterial, toastError };
