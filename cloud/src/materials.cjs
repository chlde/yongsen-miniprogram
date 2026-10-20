'use strict';
const path = require('node:path');
const { createHash } = require('node:crypto');
const { check, id } = require('./core.cjs');
async function processMaterial({ repo, sdk, materialId }) {
  const owner = id();
  const material = await repo.tx(async tx => {
    const current = await tx.get('materials', materialId);
    check(current, 'NOT_FOUND', '教材不存在');
    if (current.status === 'READY') return current;
    check(current.status === 'PROCESSING', 'INVALID_STATE', '教材尚未上传完成');
    check(!current.processingUntil || current.processingUntil < Date.now(), 'PROCESSING_BUSY', '教材正在处理');
    const claimed = { ...current, processingOwner: owner, processingUntil: Date.now() + 240000 };
    await tx.put('materials', materialId, claimed);
    return claimed;
  });
  if (material.status === 'READY') return material;
  try {
    const metadata = await sdk.getUploadMetadata({ cloudPath: material.uploadPath });
    const file = await sdk.downloadFile({ fileID: metadata.data.fileId });
    const bytes = file.fileContent;
    check(bytes && bytes.length === material.expectedSize, 'INVALID_FILE', '上传文件大小不一致，请重新上传');
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const prefix = `materials/${materialId}/${sha256}`;
    let details;
    if (material.type === 'PDF') details = await renderPdf(sdk, prefix, bytes);
    else details = await inspectMp4(sdk, prefix, bytes);
    await repo.tx(async tx => {
      const current = await tx.get('materials', materialId);
      check(current.processingOwner === owner && current.status === 'PROCESSING', 'PROCESSING_BUSY', '教材处理会话已更新');
      await tx.put('materials', materialId, { ...material, ...details, sha256, status: 'READY', processingUntil: 0, processedAt: Date.now() });
    });
    try { await sdk.deleteFile({ fileList: [metadata.data.fileId] }); }
    catch (_) { console.warn('Staging cleanup deferred for material', materialId); }
    return details;
  } catch (error) {
    await repo.tx(async tx => {
      const current = await tx.get('materials', materialId);
      if (current && current.processingOwner === owner && current.status === 'PROCESSING') {
        await tx.put('materials', materialId, { ...current, status: 'REJECTED', processingUntil: 0, error: String(error.message).slice(0, 300), processedAt: Date.now() });
      }
    });
    throw error;
  }
}
async function renderPdf(sdk, prefix, bytes) {
  check(bytes.subarray(0, 5).toString() === '%PDF-', 'INVALID_FILE', '文件不是有效 PDF');
  const { createCanvas, DOMMatrix, Path2D, ImageData } = require('@napi-rs/canvas');
  Object.assign(globalThis, { DOMMatrix, Path2D, ImageData });
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  class CanvasFactory {
    create(width, height) { const canvas = createCanvas(Math.ceil(width), Math.ceil(height)); return { canvas, context: canvas.getContext('2d') }; }
    reset(target, width, height) { target.canvas.width = width; target.canvas.height = height; }
    destroy(target) { target.canvas.width = 0; target.canvas.height = 0; target.canvas = null; target.context = null; }
  }
  const pdfRoot = path.dirname(require.resolve('pdfjs-dist/package.json'));
  const loading = pdfjs.getDocument({ data: new Uint8Array(bytes), isEvalSupported: false, useSystemFonts: false, standardFontDataUrl: `${pdfRoot}/standard_fonts/`, cMapUrl: `${pdfRoot}/cmaps/`, cMapPacked: true, CanvasFactory });
  const pdf = await loading.promise;
  try {
    check(pdf.numPages > 0 && pdf.numPages <= 80, 'INVALID_FILE', 'PDF 需包含 1–80 页');
    const pages = [];
    for (let pageNo = 1; pageNo <= pdf.numPages; pageNo++) {
      const page = await pdf.getPage(pageNo), original = page.getViewport({ scale: 1 });
      check(original.width > 0 && original.height > 0 && original.height / original.width < 10, 'INVALID_FILE', 'PDF 页面尺寸不支持');
      const viewport = page.getViewport({ scale: Math.min(1.5, 1200 / original.width) });
      const canvas = createCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height));
      await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise;
      const uploaded = await sdk.uploadFile({ cloudPath: `${prefix}/page-${pageNo}.png`, fileContent: canvas.toBuffer('image/png') });
      pages.push({ pageNo, width: canvas.width, height: canvas.height, fileId: uploaded.fileID });
      page.cleanup();
    }
    return { pages, pageCount: pages.length };
  } finally { await pdf.destroy(); }
}
async function inspectMp4(sdk, prefix, bytes) {
  check(bytes.subarray(4, 8).toString() === 'ftyp', 'INVALID_FILE', '文件不是有效 MP4');
  const MP4Box = await import('mp4box');
  const info = await new Promise((resolve, reject) => {
    const file = MP4Box.createFile();
    file.onReady = resolve; file.onError = error => reject(new Error(String(error)));
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength); buffer.fileStart = 0;
    file.appendBuffer(buffer); file.flush();
    if (!file.moov) reject(new Error('MP4 元数据缺失'));
  });
  const video = info.videoTracks[0];
  check(video && /^avc[13]/.test(video.codec) && info.videoTracks.length === 1, 'INVALID_FILE', '请上传 H.264 编码的 MP4 视频');
  const durationMs = Math.round(video.duration / video.timescale * 1000);
  check(durationMs >= 1000 && durationMs <= 4 * 3600000, 'INVALID_FILE', '视频时长需在 1 秒至 4 小时之间');
  const uploaded = await sdk.uploadFile({ cloudPath: `${prefix}/video.mp4`, fileContent: bytes });
  return { fileId: uploaded.fileID, durationMs, codec: video.codec, width: video.video.width, height: video.video.height };
}
module.exports = { processMaterial, renderPdf, inspectMp4 };
