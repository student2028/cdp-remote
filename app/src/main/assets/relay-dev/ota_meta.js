/**
 * OTA：中继从本仓库自动得到版本信息，无需手改任何文件。
 * 优先读 Gradle 构建生成的 build/ota/version.json；
 * 若无则解析 app/build/.../*.apk（需 ANDROID_HOME + aapt）。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

function findLatestApk(repoRoot) {
    const candidates = [
        ['release', 'CdpRemote-release.apk'],
        ['debug', 'CdpRemote-debug.apk']
    ];
    let best = null;
    for (const [type, name] of candidates) {
        const p = path.join(repoRoot, 'app/build/outputs/apk', type, name);
        if (fs.existsSync(p)) {
            const st = fs.statSync(p);
            if (!best || st.mtimeMs > best.mtimeMs) {
                best = { path: p, fileName: name, mtimeMs: st.mtimeMs };
            }
        }
    }
    return best;
}

function parseApkBadging(apkPath) {
    const home = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    if (!home) return null;
    const buildToolsDir = path.join(home, 'build-tools');
    if (!fs.existsSync(buildToolsDir)) return null;
    const vers = fs.readdirSync(buildToolsDir).filter((x) => /^\d/.test(x)).sort();
    if (!vers.length) return null;
    const aapt = path.join(buildToolsDir, vers[vers.length - 1], 'aapt');
    if (!fs.existsSync(aapt)) return null;
    try {
        const out = execSync(`"${aapt}" dump badging "${apkPath}"`, {
            encoding: 'utf8',
            maxBuffer: 4 * 1024 * 1024
        });
        const vc = out.match(/versionCode='(\d+)'/);
        const vn = out.match(/versionName='([^']*)'/);
        if (vc && vn) {
            return {
                versionCode: parseInt(vc[1], 10),
                versionName: vn[1],
                updateMessage: '自本机已构建的 APK 解析'
            };
        }
    } catch (e) {}
    return null;
}

/**
 * @param {string} repoRoot 仓库根目录（含 app/、build/ 的 voice7 根）
 */
function loadOtaMeta(repoRoot) {
    const root = path.resolve(repoRoot);
    const jsonPath = path.join(root, 'build/ota/version.json');
    if (fs.existsSync(jsonPath)) {
        try {
            const j = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
            return {
                versionCode: Number(j.versionCode) || 1,
                versionName: String(j.versionName || '1.0'),
                updateMessage: String(j.updateMessage || '')
            };
        } catch (e) {}
    }
    const apk = findLatestApk(root);
    if (apk) {
        const meta = parseApkBadging(apk.path);
        if (meta) return meta;
    }
    return {
        versionCode: 1,
        versionName: '0',
        updateMessage:
            '未找到 build/ota/version.json 且无法解析 APK。请在仓库根执行 ./gradlew assembleDebug，并保证已构建 APK。'
    };
}

module.exports = { loadOtaMeta, findLatestApk };
