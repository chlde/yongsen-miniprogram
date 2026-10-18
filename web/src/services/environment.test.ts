import { describe, expect, it } from 'vitest'
import { allowsLocalPreview } from './environment'

describe('LOCAL 预览边界', () => {
  it('仅网页与API均为回环地址时开放，包括本地生产预览和IPv6', () => {
    expect(allowsLocalPreview('http://127.0.0.1:15173', 'http://127.0.0.1:18080/api/v1')).toBe(true)
    expect(allowsLocalPreview('http://localhost:4173', '/api/v1')).toBe(true)
    expect(allowsLocalPreview('http://[::1]:5173', 'http://[::1]:8080/api/v1')).toBe(true)
  })
  it('拒绝远程网页、远程API、伪装域名和非HTTP协议', () => {
    expect(allowsLocalPreview('https://training.example.com', '/api/v1')).toBe(false)
    expect(allowsLocalPreview('http://localhost:5173', 'https://api.example.com/api/v1')).toBe(false)
    expect(allowsLocalPreview('http://127.0.0.1.evil.example', 'http://127.0.0.1:8080/api/v1')).toBe(false)
    expect(allowsLocalPreview('file:///training/index.html', 'http://127.0.0.1:8080/api/v1')).toBe(false)
  })
})
