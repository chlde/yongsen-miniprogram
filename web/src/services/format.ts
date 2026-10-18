const labels: Record<string, string> = {
  ACTIVE: '有效', INACTIVE: '已停用', PENDING: '待核对', UNBOUND: '未绑定', REJECTED: '已拒绝', REVOKED: '已撤销',
  DRAFT: '草稿', CANCELLED: '已取消', NOT_STARTED: '待学习', IN_PROGRESS: '学习中', READY_TO_CONFIRM: '待本人确认', COMPLETED: '已完成',
  PROCESSING: '处理中', READY: '已就绪', UPLOADED: '已上传', WAITING_BINDING: '待绑定', WAITING_SUBSCRIPTION: '待订阅授权',
  WAITING_CONFIGURATION: '待配置通知模板', SENDING: '发送中', ACCEPTED: '接口已受理', FAILED: '失败', UNKNOWN: '结果未知', CANCELED: '已取消', NOT_SENT: '未发送',
  DELIVERED: '微信确认送达', DELIVERY_FAILED: '微信下发失败', SATISFIED: '学习达标', APPROVED: '已核对', BOUND: '已绑定',
}
export const label = (value?: string) => value ? labels[value] || value : '—'
export const date = (value?: string | number) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
export const duration = (ms = 0) => `${Math.floor(ms / 60000)}分${Math.floor(ms % 60000 / 1000)}秒`
export const percent = (value = 0) => `${Math.min(100, Math.max(0, value * 100)).toFixed(1)}%`
