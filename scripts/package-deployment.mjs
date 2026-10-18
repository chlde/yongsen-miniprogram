import fs from 'node:fs';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requiredFiles = [
    '.dockerignore', 'deployment/Dockerfile', 'deployment/entrypoint.sh',
    'backend/pom.xml', 'backend/schema-mysql.sql',
    'web/package.json', 'web/package-lock.json', 'web/index.html',
    'web/tsconfig.json', 'web/vite.config.ts'
];
const requiredTrees = ['backend/src/main', 'web/src'];
const optionalTrees = ['web/public'];
const credentialExtensions = /\.(?:pem|key|p12|pfx|jks|keystore)$/i;
const excludedDirectories = new Set([
    '.git', '.local', 'node_modules', 'target', 'dist', 'build',
    'test', 'tests', '__tests__', '__mocks__', 'fixtures', 'coverage', 'test-results', 'playwright-report'
]);
const sensitiveKey = '(?:app[-_]?secret|client[-_]?secret|secret[-_]?key|api[-_]?key|access[-_]?token|refresh[-_]?token|private[-_]?key|password|passwd|admin[-_]?password|callback[-_]?token|callback[-_]?aes[-_]?key|WECHAT_APP_SECRET|WECHAT_CALLBACK_TOKEN|WECHAT_CALLBACK_AES_KEY|TRAINING_MYSQL_PASSWORD|TRAINING_ADMIN_PASSWORD|TRAINING_DATA_KEY)';
const sensitiveAssignment = new RegExp(`(?:^|[\\s{,])['"]?${sensitiveKey}['"]?\\s*[:=]\\s*(['"])([^\\r\\n]*?)\\1`, 'gim');
const sensitiveConfigKey = new RegExp(`^(?:${sensitiveKey})$`, 'i');
const textExtensions = new Set(['.java', '.ts', '.js', '.mjs', '.cjs', '.vue', '.json', '.yml', '.yaml', '.properties', '.xml', '.sql', '.html', '.css', '.svg', '.sh', '.txt']);
let artifactDirectory;

function failure(message) { throw new Error(message); }
function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }

function safeRelative(relative) {
    if (!relative || relative.includes('\\') || /[\x00-\x1f\x7f]/.test(relative)
        || path.posix.isAbsolute(relative) || relative.split('/').some(part => ['', '.', '..'].includes(part))) {
        failure('Unsafe source path; package creation stopped.');
    }
    return relative;
}

function checkedSource(relative, optional = false) {
    safeRelative(relative);
    let current = root;
    const parts = relative.split('/');
    for (let index = 0; index < parts.length; index++) {
        current = path.join(current, parts[index]);
        let stat;
        try { stat = fs.lstatSync(current); }
        catch (error) {
            if (optional && error.code === 'ENOENT') return null;
            failure(`Required deployment source is unavailable: ${relative}`);
        }
        if (stat.isSymbolicLink()) failure(`Symbolic links are not permitted in deployment sources: ${relative}`);
        if (index < parts.length - 1 && !stat.isDirectory()) failure(`Invalid source directory: ${relative}`);
        if (index === parts.length - 1) return { filename: current, stat };
    }
}

function excludedName(name, directory = false) {
    if (directory && excludedDirectories.has(name)) return true;
    return /^\.env(?:\.|$)/i.test(name)
        || /^(?:credentials|service[-_]?account)(?:[._-].*)?\.json$/i.test(name)
        || /^project\.private\.config\.json$/i.test(name)
        || credentialExtensions.test(name)
        || /\.(?:test|spec)\.[^.]+$/i.test(name)
        || /\.(?:log|tsbuildinfo|db|sqlite|sqlite3|mv\.db)$/i.test(name)
        || ['.DS_Store', '.npmrc', '.netrc', '.git-credentials', 'id_rsa', 'id_ed25519'].includes(name);
}

function collectTree(relative, files, optional = false) {
    const source = checkedSource(relative, optional);
    if (!source) return;
    if (!source.stat.isDirectory()) failure(`Expected a source directory: ${relative}`);
    for (const name of fs.readdirSync(source.filename).sort()) {
        const child = `${relative}/${name}`;
        const entry = checkedSource(child);
        // Reject links before applying exclusion rules, without following or reading them.
        if (excludedName(name, entry.stat.isDirectory())) continue;
        if (entry.stat.isDirectory()) collectTree(child, files);
        else if (entry.stat.isFile()) files.add(child);
        else failure(`Only regular source files are permitted: ${child}`);
    }
}

function placeholder(value) {
    const normalized = value.trim();
    return !normalized || /^\$\{[A-Z][A-Z0-9_]*(?::(?:LocalTrain123!|local-only-data-key-change-before-production)?)?\}$/.test(normalized);
}

function scanCredentials(relative, bytes) {
    if (!textExtensions.has(path.extname(relative).toLowerCase())
        && !['Dockerfile', '.dockerignore'].includes(path.basename(relative))) return;
    const text = bytes.toString('utf8');
    const finding = () => failure(`Possible literal credential in whitelisted source: ${relative}. No value has been printed.`);
    if (/-----BEGIN (?:[A-Z ]*PRIVATE KEY|OPENSSH PRIVATE KEY)-----/.test(text)
        || /\b(?:AKID[A-Za-z0-9]{28,}|AKIA[A-Z0-9]{16})\b/.test(text)
        || /\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}\b/.test(text)
        || /\bBearer\s+[A-Za-z0-9._~+/=-]{20,}/.test(text)
        || /https?:\/\/[^\s/@:]+:[^\s/@]+@/.test(text)) finding();
    sensitiveAssignment.lastIndex = 0;
    for (const match of text.matchAll(sensitiveAssignment)) {
        if (!placeholder(match[2])) finding();
    }
    if (['.yml', '.yaml', '.properties'].includes(path.extname(relative))) {
        for (const line of text.split(/\r?\n/)) {
            const match = /^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.*?)\s*$/.exec(line);
            if (!match || !sensitiveConfigKey.test(match[1])) continue;
            const value = match[2].replace(/\s+#.*$/, '').replace(/^(['"])(.*)\1$/, '$2');
            if (!placeholder(value)) finding();
        }
    }
}

function readSource(relative) {
    const source = checkedSource(relative);
    if (!source.stat.isFile()) failure(`Expected a regular file: ${relative}`);
    const descriptor = fs.openSync(source.filename, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try {
        if (!fs.fstatSync(descriptor).isFile()) failure(`Source changed during packaging: ${relative}`);
        const bytes = fs.readFileSync(descriptor);
        scanCredentials(relative, bytes);
        return bytes;
    } finally { fs.closeSync(descriptor); }
}

function privateDirectory(relative) {
    let current = root;
    for (const part of safeRelative(relative).split('/')) {
        current = path.join(current, part);
        try { fs.mkdirSync(current, { mode: 0o700 }); }
        catch (error) { if (error.code !== 'EEXIST') throw error; }
        const stat = fs.lstatSync(current);
        if (stat.isSymbolicLink() || !stat.isDirectory()) failure('Artifact output must be a real directory, not a symbolic link.');
    }
    return current;
}

function tar(args) {
    const result = spawnSync('tar', args, {
        encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, shell: false,
        env: { PATH: process.env.PATH || '/usr/bin:/bin', COPYFILE_DISABLE: '1', LC_ALL: 'C' }
    });
    if (result.error || result.status !== 0) failure('System tar failed. Package creation stopped without printing source contents.');
    return result.stdout;
}

function verifyArchive(archive, stage, records) {
    const names = tar(['-tzf', archive]).trim().split('\n').filter(Boolean);
    const expected = records.map(record => record.path).sort();
    if (names.length !== expected.length || JSON.stringify([...names].sort()) !== JSON.stringify(expected)) {
        failure('Archive entries do not exactly match the whitelist manifest.');
    }
    if (names.some(name => safeRelative(name) !== name)) failure('Archive contains an unsafe path.');
    const listing = tar(['-tvzf', archive]).trim().split('\n');
    if (listing.some(line => !line.startsWith('-'))) failure('Archive contains a link or a non-regular entry.');
    const verification = path.join(artifactDirectory, 'verification');
    fs.mkdirSync(verification, { mode: 0o700 });
    tar(['-xzf', archive, '--no-same-owner', '-C', verification]);
    for (const record of records) {
        const filename = path.join(verification, record.path);
        const stat = fs.lstatSync(filename);
        if (!stat.isFile() || stat.isSymbolicLink()) failure('Extracted archive contains a non-regular file.');
        const bytes = fs.readFileSync(filename);
        if (bytes.length !== record.bytes || sha256(bytes) !== record.sha256) failure('Extracted archive hash mismatch.');
        scanCredentials(record.path, bytes);
        if (!fs.readFileSync(path.join(stage, record.path)).equals(bytes)) failure('Archive differs from the staged source.');
    }
    fs.rmSync(verification, { recursive: true });
}

try {
    if (process.argv.length !== 2) failure('Usage: node scripts/package-deployment.mjs');
    process.umask(0o077);
    const files = new Set(requiredFiles);
    for (const relative of requiredTrees) collectTree(relative, files);
    for (const relative of optionalTrees) collectTree(relative, files, true);
    // Include additional real TS project configs if the build starts referencing them later.
    for (const name of fs.readdirSync(checkedSource('web').filename)) {
        if (/^tsconfig(?:\.[A-Za-z0-9_-]+)?\.json$/.test(name)) files.add(`web/${name}`);
    }
    const createdAt = new Date().toISOString();
    const stamp = createdAt.replace(/[:.]/g, '-') + '-' + randomUUID().slice(0, 8);
    artifactDirectory = privateDirectory(`.local/deployment-artifacts/${stamp}`);
    const stage = path.join(artifactDirectory, 'source');
    fs.mkdirSync(stage, { mode: 0o700 });
    const records = [];
    for (const relative of [...files].sort()) {
        const bytes = readSource(relative);
        const target = path.join(stage, relative);
        fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o755 });
        fs.writeFileSync(target, bytes, { flag: 'wx', mode: 0o600 });
        fs.chmodSync(target, relative === 'deployment/entrypoint.sh' ? 0o755 : 0o644);
        records.push({ path: relative, bytes: bytes.length, sha256: sha256(bytes) });
    }
    const fileList = path.join(artifactDirectory, 'archive-files.txt');
    fs.writeFileSync(fileList, records.map(record => record.path).join('\n') + '\n');
    const archiveName = 'yongsen-training-source.tar.gz';
    const archive = path.join(artifactDirectory, archiveName);
    tar(['-czf', archive, '--format=ustar', '--no-recursion', '-C', stage, '-T', fileList]);
    verifyArchive(archive, stage, records);
    fs.unlinkSync(fileList);
    const archiveHash = sha256(fs.readFileSync(archive));
    const manifest = {
        schemaVersion: 1, createdAt, archive: archiveName, archiveSha256: archiveHash,
        dockerfile: 'deployment/Dockerfile', buildContext: '.', platform: 'linux/amd64',
        fileCount: records.length,
        verification: {
            archiveEntriesMatch: true, extractedHashesMatch: true, regularFilesOnly: true,
            credentialScan: 'No suspected literal credentials in packaged source; only whitelisted files were read. Runtime secrets were not read or substituted.',
            deploymentStatus: 'Source package only; no image build, cloud deployment, or paid resource creation.'
        },
        files: records
    };
    const manifestName = 'manifest.json';
    const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2) + '\n');
    fs.writeFileSync(path.join(artifactDirectory, manifestName), manifestBytes);
    fs.writeFileSync(path.join(artifactDirectory, 'SHA256SUMS'), `${archiveHash}  ${archiveName}\n${sha256(manifestBytes)}  ${manifestName}\n`);
    console.log(JSON.stringify({
        status: 'verified-source-package', directory: artifactDirectory, archive,
        sha256: archiveHash, fileCount: records.length, dockerfile: 'deployment/Dockerfile', buildContext: '.'
    }, null, 2));
} catch (error) {
    if (artifactDirectory) fs.rmSync(artifactDirectory, { recursive: true, force: true });
    console.error(error instanceof Error ? error.message : 'Source packaging failed.');
    process.exitCode = 1;
}
