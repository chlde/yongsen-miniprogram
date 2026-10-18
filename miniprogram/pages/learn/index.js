const api = require('../../lib/api');
const { enrichMaterial, toastError } = require('../../lib/view');
const { SessionTracker, visiblePdfRange, visibleFraction } = require('../../lib/session-tracker');
Page({
  data: { loading: true, material: null, isVideo: false, mediaUrl: '', mediaReady: false, error: '', sessionStarted: false, needsResume: false, playing: false, coverage: 0, effectiveSeconds: 0, currentSeconds: 0, totalSeconds: 0, pageNo: 1, pageCount: 0, pageSeconds: 0, pageRequiredSeconds: 30, pageCoverage: 0, pdfViewportHeight: 400, scrollTop: 0, syncText: '尚未开始', completed: false, busy: false },
  onLoad(query) {
    this.assignmentId = query.assignmentId; this.taskMaterialId = query.taskMaterialId;
    this._positionMs = 0; this._playing = false; this._buffering = false; this._pageVisible = true; this._rendered = false; this._range = null; this._pageNo = 1; this._playerVisible = false; this._scrollTop = 0; this._geometryVersion = 0;
    this._clock = (() => { try { const p = wx.getPerformance(); if (p && typeof p.now === 'function') return () => p.now(); } catch (_) {} return () => Date.now(); })();
    this.load();
  },
  onShow() { this._pageVisible = true; /* Resume requires a newly acknowledged baseline. */ },
  onHide() { this.pauseForBackground(); },
  onLoginExpired() {
    this._pageVisible = false; this._playing = false;
    if (this.tracker) this.tracker.dispose();
    this.tracker = null; this.sessionId = null;
    if (this.video) this.video.pause();
    if (!this._unloaded) this.setData({ playing: false, needsResume: true, syncText: '登录已过期，学习已暂停' });
  },
  onUnload() { this._unloaded = true; this.closeSession('PAGE_EXIT'); clearTimeout(this._geometryTimer); },
  onPageScroll(e) {
    this._scrollTop = e.scrollTop; this._geometryVersion++; this._playerVisible = false; this._range = null;
    this.refreshGeometry();
  },
  onResize() {
    this._geometryVersion++; this._playerVisible = false; this._range = null;
    this.refreshGeometry();
    if (this.material && !this.data.isVideo) {
      this.emit('PAGE_LEAVE', this.docPayload());
      if (this.tracker) this.tracker.setVisible(false);
      this._range = null; this.setData({ needsResume: true, syncText: '显示区域变化，请继续学习' }); this.refreshPdfGeometry();
    }
  },
  async load() {
    try {
      const task = await api.request(`/me/assignments/${this.assignmentId}`);
      this.material = (task.materials || []).find(item => String(item.taskMaterialId || item.id) === String(this.taskMaterialId));
      if (!this.material) throw new Error('未找到本任务的学习材料');
      if (['CANCELED', 'CANCELLED', 'CLOSED'].includes(task.taskStatus)) throw new Error('任务已停止，不能继续学习');
      const m = enrichMaterial(this.material);
      const p = m.progress || {};
      const missing = p.missingRangesMs || [];
      this.resumePositionMs = Math.max(0, (missing.length ? missing[0][0] : 0) - 2000);
      this._positionMs = this.resumePositionMs;
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      this.setData({ material: m, isVideo: m.type === 'MP4', pageCount: m.pageCount || 0, totalSeconds: Math.ceil((m.durationMs || 0) / 1000), initialTime: this.resumePositionMs / 1000, pageRequiredSeconds: (m.rule && m.rule.minPageVisibleMs || 30000) / 1000, pdfViewportHeight: Math.max(220, windowInfo.windowHeight - 340) });
      this.updateProgress(p);
      await this.loadMedia(1);
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); }
  },
  async loadMedia(pageNo) {
    this._geometryVersion++; this._rendered = false; this._range = null;
    this.setData({ mediaReady: false, error: '', scrollTop: 0 });
    const data = { taskMaterialId: this.taskMaterialId };
    if (this.material.type === 'PDF') data.pageNo = pageNo;
    const ticket = await api.post(`/me/assignments/${this.assignmentId}/media-ticket`, data);
    this.setData({ mediaUrl: api.absoluteUrl(ticket.url) }, () => {
      if (this.material.type === 'MP4') { this.video = wx.createVideoContext('learning-video', this); this.video.playbackRate(1); }
    });
  },
  videoPayload(extra) {
    return Object.assign({ positionMs: Math.max(0, Math.round(this._positionMs)), playing: this._playing, playbackRate: 1, buffering: this._buffering, appVisible: getApp().globalData.appVisible, pageVisible: this._pageVisible, playerVisible: this._pageVisible && this._playerVisible }, extra || {});
  },
  docPayload(extra) {
    const page = this.pageMeta();
    return Object.assign({ pageNo: this._pageNo, rendered: this._rendered && Boolean(this._range), visibleTopPx: this._range ? this._range.visibleTopPx : 0, visibleBottomPx: this._range ? this._range.visibleBottomPx : Math.min(1, page.height || 1), appVisible: getApp().globalData.appVisible, pageVisible: this._pageVisible, readerUnobscured: this._pageVisible }, extra || {});
  },
  snapshot() {
    this.refreshGeometry();
    if (this.data.isVideo) return { type: 'VIDEO_SAMPLE', payload: this.videoPayload() };
    return { type: 'DOC_SAMPLE', payload: this.docPayload() };
  },
  async startLearning() {
    if (this.data.busy || !this.data.mediaReady) return;
    this.setData({ busy: true, error: '' });
    try {
      await this.closeSession('RESUME');
      await this.showLearningArea();
      if (this.data.isVideo ? !this._playerVisible : !this._range) throw new Error('请将学习内容显示在屏幕内后再开始');
      let session;
      try { session = await api.post('/learning/sessions', { assignmentId: this.assignmentId, taskMaterialId: this.taskMaterialId }); }
      catch (error) {
        if (error.code !== 'SESSION_IN_USE') throw error;
        const answer = await new Promise(resolve => wx.showModal({ title: '已有学习会话', content: '其他页面或设备正在学习。是否在此设备继续？原会话将停止计时。', confirmText: '在此继续', success: resolve }));
        if (!answer.confirm) return;
        const existingId = error.details.sessionId || error.details.activeSessionId;
        if (!existingId) throw new Error('无法识别原会话，请稍后重试');
        session = await api.post(`/learning/sessions/${existingId}/takeover`, { assignmentId: this.assignmentId, taskMaterialId: this.taskMaterialId, expectedSessionId: existingId });
      }
      this.sessionId = session.sessionId;
      this.tracker = new SessionTracker({
        clock: this._clock, makeId: () => api.requestId('learn'), sample: () => this.snapshot(),
        transport: (id, batch) => api.post(`/learning/sessions/${id}/events:batch`, batch, batch.batchId),
        onProgress: (progress, meta) => { if (this._unloaded) return; this.updateProgress(progress); this.setData({ syncText: meta.pendingCount ? '最新进度待同步' : '进度已由服务端确认' }); },
        onStall: message => { this._playing = false; if (this.video) this.video.pause(); if (!this._unloaded) this.setData({ playing: false, needsResume: true, syncText: message }); },
        onError: error => { if (!this._unloaded && error.code === 'NETWORK_ERROR') this.setData({ syncText: '网络波动，进度待同步' }); }
      });
      const baseline = this.snapshot();
      await this.tracker.start(this.sessionId, baseline.type, baseline.payload);
      this.setData({ sessionStarted: true, needsResume: false, syncText: '正在学习，进度自动同步' });
      if (this.data.isVideo) { this.video.playbackRate(1); this.video.play(); }
    } catch (error) { this.setData({ error: error.message, needsResume: true }); if (this.tracker) this.tracker.dispose(); }
    finally { this.setData({ busy: false }); }
  },
  async closeSession(reason) {
    if (this._closing) return this._closing;
    const tracker = this.tracker, id = this.sessionId;
    this.tracker = null; this.sessionId = null;
    this._playing = false; if (this.video) this.video.pause();
    this._closing = (async () => {
      if (tracker) { try { await tracker.finish(this.data.isVideo ? this.videoPayload({ playing: false }) : this.docPayload()); } catch (_) {} }
      if (id) { try { await api.post(`/learning/sessions/${id}/close`, { reason }); } catch (_) {} }
    })();
    try { await this._closing; } finally { this._closing = null; }
  },
  emit(type, payload) { if (this.tracker) this.tracker.emit(type, payload).catch(() => {}); },
  pauseForBackground() {
    if (!this._pageVisible) return;
    this._pageVisible = false; this._playing = false;
    if (this.video) this.video.pause();
    this.emit('HIDE', this.data.isVideo ? this.videoPayload({ pageVisible: false, playing: false }) : this.docPayload({ pageVisible: false }));
    if (this.tracker) this.tracker.setVisible(false);
    if (!this._unloaded && this.data.sessionStarted) this.setData({ playing: false, needsResume: true, syncText: '离开页面后已暂停，请继续学习' });
  },
  onVideoReady() { this.setData({ mediaReady: true }); this.refreshGeometry(); },
  onVideoTime(e) {
    const next = Math.round(Number(e.detail.currentTime || 0) * 1000);
    const previous = this._positionMs;
    const now = this._clock();
    if (this._lastPositionClock && this.tracker && Math.abs(next - previous) > Math.max(5000, (now - this._lastPositionClock) * 1.5 + 1000)) this.emit('SEEK', this.videoPayload({ fromMs: previous, toMs: next, positionMs: next }));
    this._positionMs = next; this._lastPositionClock = now;
    if (this._buffering && this._playing && next > previous) { this._buffering = false; this.emit('PLAY', this.videoPayload()); }
    this.setData({ currentSeconds: Math.floor(next / 1000) });
  },
  onVideoPlay() {
    if (!this.tracker || this.data.needsResume || !this._playerVisible) { if (this.video) this.video.pause(); return; }
    this._playing = true; this._buffering = false; this.setData({ playing: true });
    this.emit('PLAY', this.videoPayload());
  },
  onVideoPause() { this._playing = false; this.setData({ playing: false }); this.emit('PAUSE', this.videoPayload()); },
  onVideoWaiting() { this._buffering = true; this.emit('WAITING', this.videoPayload()); },
  onVideoProgress() { if (this._playing && this._buffering) { this._buffering = false; this.emit('PLAY', this.videoPayload()); } },
  onVideoEnded() {
    const lastObservedPositionMs = this._positionMs;
    const durationMs = Number(this.material.durationMs);
    // Natural ended is a terminal observation, not proof of coverage before the last sample.
    // Preserve the last timeupdate position; only a near-tail terminal may report the metadata end.
    if (durationMs >= lastObservedPositionMs && durationMs - lastObservedPositionMs <= 1000) this._positionMs = durationMs;
    this._playing = false; this.setData({ playing: false, currentSeconds: Math.floor(this._positionMs / 1000) });
    this.emit('ENDED', this.videoPayload({ lastObservedPositionMs, positionSource: 'NATIVE_ENDED' }));
  },
  onVideoError(e) { this._playing = false; this.setData({ error: '视频加载失败，请检查网络后重新加载', mediaReady: false, playing: false }); this.emit('PAUSE', this.videoPayload()); },
  async togglePlay() {
    if (!this.tracker || this.data.needsResume) return this.startLearning();
    if (this._playing) this.video.pause(); else { await this.showLearningArea(); if (this._playerVisible) { this.video.playbackRate(1); this.video.play(); } }
  },
  async replayMissing() {
    if (this.data.busy) return;
    const detail = await api.request(`/me/assignments/${this.assignmentId}/progress`).catch(toastError);
    if (!detail) return;
    const material = (detail.materials || []).find(item => String(item.taskMaterialId || item.id) === String(this.taskMaterialId));
    const ranges = material && material.progress && material.progress.missingRangesMs || [];
    const target = Math.max(0, (ranges.length ? ranges[0][0] : 0) - 2000);
    this._playing = false; if (this.video) { this.video.pause(); this.emit('SEEK', this.videoPayload({ fromMs: this._positionMs, toMs: target, positionMs: target })); this._positionMs = target; this.video.seek(target / 1000); }
    this.setData({ needsResume: true, syncText: '已定位缺失部分，请继续学习' });
  },
  pageMeta() { return (this.material && this.material.pages || []).find(page => Number(page.pageNo) === this._pageNo) || { height: this._imageHeight || 1, width: this._imageWidth || 1 }; },
  onPdfLoad(e) { this._imageHeight = e.detail.height; this._imageWidth = e.detail.width; this._rendered = true; this.setData({ mediaReady: true }); this.refreshPdfGeometry(true); },
  onPdfError() { this._rendered = false; this.setData({ mediaReady: false, error: '当前页加载失败，请重新加载' }); },
  screenRect() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    return { left: 0, top: 0, width: info.windowWidth, height: info.windowHeight };
  },
  queryRects(selectors) {
    return new Promise(resolve => {
      const query = wx.createSelectorQuery().in(this);
      selectors.forEach(selector => query.select(selector).boundingClientRect());
      query.exec(rects => resolve(rects || []));
    });
  },
  async showLearningArea() {
    const selector = this.data.isVideo ? '#learning-video' : '#pdf-viewport';
    const rects = await this.queryRects([selector]);
    if (rects[0]) await new Promise(resolve => wx.pageScrollTo({ scrollTop: Math.max(0, this._scrollTop + rects[0].top - 16), duration: 0, complete: resolve }));
    await this.refreshGeometry();
  },
  async refreshGeometry() {
    if (!this.material || this._unloaded) return;
    if (!this.data.isVideo) return this.refreshPdfGeometry();
    const version = ++this._geometryVersion;
    const rects = await this.queryRects(['#learning-video']);
    if (this._unloaded || version !== this._geometryVersion) return;
    this._playerVisible = visibleFraction(rects[0], this.screenRect()) >= 0.5;
    if (!this._playerVisible && this._playing) {
      this._playing = false; if (this.video) this.video.pause();
      this.emit('PAUSE', this.videoPayload());
      this.setData({ playing: false, syncText: '播放区域离开屏幕，已暂停' });
    }
  },
  async refreshPdfGeometry(enter) {
    if (this.data.isVideo || !this.material || !this._rendered) return;
    const pageNo = this._pageNo, version = ++this._geometryVersion;
    const rects = await this.queryRects(['#pdf-viewport', '#pdf-page-image']);
    if (this._unloaded || version !== this._geometryVersion || pageNo !== this._pageNo || !rects[0] || !rects[1]) return;
    const viewport = rects[0], image = rects[1], page = this.pageMeta(), screen = this.screenRect();
    this._range = visibleFraction(image, screen) > 0 ? visiblePdfRange({ imageTop: image.top, imageHeight: image.height, viewportTop: viewport.top, viewportHeight: viewport.height, sourceHeight: page.height, screenTop: 0, screenHeight: screen.height }) : null;
    if (enter && this.tracker) this.emit('PAGE_ENTER', this.docPayload());
  },
  onPdfScroll() { this._geometryVersion++; this._range = null; clearTimeout(this._geometryTimer); this._geometryTimer = setTimeout(() => this.refreshPdfGeometry(), 60); },
  async changePage(e) {
    const target = this._pageNo + Number(e.currentTarget.dataset.direction);
    if (target < 1 || target > this.data.pageCount || this.data.busy) return;
    this.emit('PAGE_LEAVE', this.docPayload());
    this._pageNo = target; this.setData({ pageNo: target, scrollTop: 0, pageSeconds: 0, pageCoverage: 0 });
    try { await this.loadMedia(target); await this.showLearningArea(); } catch (error) { this.setData({ error: error.message }); }
  },
  updateProgress(progress) {
    this.progress = progress;
    const pages = progress.pageProgress || {};
    const page = Array.isArray(pages) ? pages.find(item => Number(item.pageNo) === this._pageNo) || {} : pages[String(this._pageNo)] || {};
    this.setData({ coverage: Math.floor((progress.coverageRatio || 0) * 100), effectiveSeconds: Math.floor((progress.effectiveMs || 0) / 1000), completed: Boolean(progress.requirementsMet), pageSeconds: Math.floor((page.effectiveMs || page.visibleMs || 0) / 1000), pageCoverage: Math.floor((page.coverageRatio || 0) * 100) });
  },
  async reloadMedia() { try { await this.loadMedia(this._pageNo); } catch (error) { toastError(error); } },
  async backToTask() { if (this.data.busy) return; this.setData({ busy: true }); await this.closeSession('RETURN_TO_TASK'); wx.navigateBack({ delta: 1 }); }
});
