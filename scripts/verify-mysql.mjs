import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { root, java, runtimeEnv, backendJar, run, stop, waitFor } from './process.mjs';

// Uses a newly initialized datadir. No connection to the user's existing MySQL instance.
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const directory = path.join(root, '.local', 'mysql-check', stamp);
const data = path.join(directory, 'mysql-data');
const port = process.env.MYSQL_CHECK_PORT || '13368';
const apiPort = process.env.MYSQL_CHECK_API_PORT || '18082';
const socket = `/tmp/yongsen-mysql-${randomUUID()}.sock`;
if (!/^\d{4,5}$/.test(port) || !/^\d{4,5}$/.test(apiPort)) throw new Error('Invalid local ports');
await mkdir(data, { recursive: true });
const mysqlArgs = ['--no-defaults', '--protocol=TCP', '-h127.0.0.1', `-P${port}`, '-uroot'];
let database;
let backend;
let passed = false;
let errorMessage;
let backendSha256;
const streams = [];

function service(command, args, options, name) {
    const log = createWriteStream(path.join(directory, `${name}.log`));
    streams.push(log);
    const child = spawn(command, args, { cwd: root, env: runtimeEnv, ...options, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
    return child;
}

function executeSql(args, file) {
    return new Promise((resolve, reject) => {
        const child = spawn('mysql', [...mysqlArgs, ...args], { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
        let error = '';
        child.stderr.on('data', chunk => { error += chunk; });
        child.stdout.resume();
        child.on('error', reject);
        child.on('exit', code => code === 0 ? resolve() : reject(new Error(`mysql: ${error.trim()}`)));
        if (file) createReadStream(file).pipe(child.stdin);
        else child.stdin.end();
    });
}

try {
    await run('mysqld', ['--no-defaults', '--initialize-insecure', `--datadir=${data}`, `--log-error=${path.join(directory, 'initialize.log')}`]);
    database = service('mysqld', ['--no-defaults', `--datadir=${data}`, `--port=${port}`, '--bind-address=127.0.0.1',
        `--socket=${socket}`, `--pid-file=${path.join(directory, 'mysql.pid')}`, '--mysqlx=OFF'], {}, 'mysql');
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt++) {
        try { await executeSql(['-e', 'SELECT 1']); ready = true; break; } catch {}
        if (database.exitCode !== null) throw new Error('Isolated MySQL stopped before startup');
        await new Promise(resolve => setTimeout(resolve, 500));
    }
    if (!ready) throw new Error('Isolated MySQL did not start');
    await executeSql(['-e', 'CREATE DATABASE training_check CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci']);
    await executeSql(['training_check'], path.join(root, 'backend', 'schema-mysql.sql'));
    await run('mvn', ['-B', '-Dtest=BackgroundWorkMysqlTest',
        `-Dtraining.mysql.test-url=jdbc:mysql://127.0.0.1:${port}/training_check?serverTimezone=UTC`, 'test'],
        { cwd: path.join(root, 'backend') });
    const jar = path.join(directory, 'training-backend.jar');
    await copyFile(backendJar(), jar);
    backendSha256 = createHash('sha256').update(await readFile(jar)).digest('hex');
    backend = service(java, ['-jar', jar], { env: { ...runtimeEnv, SPRING_PROFILES_ACTIVE: 'test', WECHAT_MODE: 'mock',
        SERVER_PORT: apiPort, TRAINING_PUBLIC_BASE: `http://127.0.0.1:${apiPort}`,
        TRAINING_DATA_DIR: path.join(directory, 'files'), SPRING_SQL_INIT_MODE: 'never',
        SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${port}/training_check?serverTimezone=UTC`,
        SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: '' } }, 'backend');
    await waitFor(`http://127.0.0.1:${apiPort}/health`, [backend, database]);
    await run('npx', ['playwright', 'test', 'api.spec.ts', 'guards.spec.ts'], { env: { ...runtimeEnv,
        E2E_API_URL: `http://127.0.0.1:${apiPort}`, TRAINING_EVIDENCE_DIR: path.join(directory, 'evidence'),
        E2E_RESULTS_DIR: path.join(directory, 'test-results'), E2E_HTML_REPORT: path.join(directory, 'report') } });
    passed = true;
    console.log(`MySQL schema and API end-to-end passed: ${directory}`);
} catch (error) {
    errorMessage = error.message;
    console.error(`${errorMessage}\nEvidence: ${directory}`);
    process.exitCode = 1;
} finally {
    await stop(backend);
    await stop(database);
    streams.forEach(stream => stream.end());
    await writeFile(path.join(directory, 'verification.json'), JSON.stringify({ passed, errorMessage, backendSha256,
        finishedAt: new Date().toISOString(), scope: 'Isolated local MySQL schema, durable background work SQL, and authenticated API end-to-end. Simulated WeChat only.' }, null, 2));
}
