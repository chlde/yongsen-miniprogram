import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const target = path.join(root, 'tests', 'fixtures', 'generated');
await mkdir(target, { recursive: true });

const pdf = await PDFDocument.create();
const font = await pdf.embedFont(StandardFonts.Helvetica);
for (let pageNumber = 1; pageNumber <= 2; pageNumber += 1) {
    const page = pdf.addPage([360, 460]);
    page.drawRectangle({ x: 0, y: 0, width: 360, height: 460, color: rgb(0.95, 0.97, 0.98) });
    page.drawText(`YONGSEN TRAINING - TEST PAGE ${pageNumber}`, {
        x: 20, y: 410, size: 14, font, color: rgb(0.08, 0.22, 0.32)
    });
    page.drawText('Local E2E fixture. Not an official training material.', {
        x: 20, y: 375, size: 10, font
    });
    ['1. Check the work area.', '2. Follow the approved procedure.', '3. Report hazards before starting.'].forEach((line, index) => {
        page.drawText(line, { x: 24, y: 300 - index * 45, size: 13, font });
    });
    page.drawText(`END OF PAGE ${pageNumber}`, { x: 24, y: 35, size: 10, font });
}
await writeFile(path.join(target, 'training.pdf'), await pdf.save());
await writeFile(path.join(target, 'invalid.pdf'), 'This is intentionally not a PDF.');

const output = path.join(target, 'training.mp4');
const result = spawnSync('ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
    '-t', '12', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', output
], { encoding: 'utf8' });
if (result.status !== 0) {
    throw new Error(`ffmpeg failed: ${result.stderr || result.error}`);
}
console.log(`Created local test fixtures: ${target}`);
