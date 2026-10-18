import { describe, expect, it } from 'vitest'
import { resumePosition, visiblePageRange } from './geometry'
describe('PDF页图可见范围', () => {
  it('将被工具栏遮挡后的可见范围转换为原始页图坐标', () => { expect(visiblePageRange({ top: 100, bottom: 500 }, { top: -100, bottom: 900, height: 1000 }, 2000)).toEqual([400, 1200]) })
  it('完全不可见或未渲染时不返回有效区域', () => { expect(visiblePageRange({ top: 0, bottom: 500 }, { top: 600, bottom: 1600, height: 1000 }, 2000)).toBeNull(); expect(visiblePageRange({ top: 0, bottom: 500 }, { top: 0, bottom: 0, height: 0 }, 2000)).toBeNull() })
  it('图像末端裁切不产生超出原图的覆盖', () => { expect(visiblePageRange({ top: 0, bottom: 800 }, { top: -500, bottom: 500, height: 1000 }, 2000)).toEqual([1000, 2000]) })
})
describe('视频补学定位', () => {
  it('回到第一个未确认区间前2秒，不能跳至已播放末尾', () => { expect(resumePosition({ missingRangesMs: [[20000, 40000], [80000, 90000]] })).toBe(18000); expect(resumePosition({ missingRangesMs: [[500, 4000]] })).toBe(0) })
})
