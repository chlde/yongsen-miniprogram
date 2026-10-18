import { spawn } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const homebrewJava = '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home';
export const javaHome = process.env.TRAINING_JAVA_HOME || (existsSync(homebrewJava) ? homebrewJava : process.env.JAVA_HOME);
export const runtimeEnv = { ...process.env, ...(javaHome ? { JAVA_HOME: javaHome } : {}) };
export const java = javaHome ? path.join(javaHome, 'bin', 'java') : 'java';

export function run(command, args, options = {}) {
    return new Promise((resolve, reject) => {
        const settings = options.env?.TRAINING_MAVEN_SETTINGS || runtimeEnv.TRAINING_MAVEN_SETTINGS;
        const commandArgs = command === 'mvn' && settings ? ['-s', settings, ...args] : args;
        const child = spawn(command, commandArgs, { cwd: root, env: runtimeEnv, stdio: 'inherit', ...options });
        child.on('error', reject);
        child.on('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code ?? signal}`)));
    });
}

export function backendJar() {
    const directory = path.join(root, 'backend', 'target');
    const candidates = existsSync(directory) ? readdirSync(directory).filter(name => name.endsWith('.jar') && !name.endsWith('-sources.jar')) : [];
    if (candidates.length !== 1) {
        throw new Error('Expected one backend executable jar. Run npm run setup first.');
    }
    return path.join(directory, candidates[0]);
}

export async function waitFor(url, children = [], timeout = 90_000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        if (children.some(child => child.exitCode !== null)) {
            throw new Error(`A service exited before ${url} became ready`);
        }
        try {
            const response = await fetch(url, { signal: AbortSignal.timeout(1500) });
            if (response.ok) {
                return;
            }
        } catch {}
        await new Promise(resolve => setTimeout(resolve, 500));
    }
    throw new Error(`Timed out waiting for ${url}`);
}

export async function stop(child) {
    if (!child || child.exitCode !== null) {
        return;
    }
    child.kill('SIGTERM');
    await Promise.race([
        new Promise(resolve => child.once('exit', resolve)),
        new Promise(resolve => setTimeout(resolve, 8000))
    ]);
    if (child.exitCode === null) {
        child.kill('SIGKILL');
    }
}
