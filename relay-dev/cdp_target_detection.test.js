const assert = require('assert');
const { detectAppType } = require('./cdp_target_detection');

const cursorWithDsmeWebview = detectAppType([
    { type: 'webview', title: 'DSME — DeepSeek Matrix Engine', url: 'http://localhost:5173/' },
    {
        type: 'page',
        title: 'DSME — DeepSeek Matrix Engine — dsme',
        url: 'vscode-file://vscode-app/Applications/Cursor.app/Contents/Resources/app/out/vs/code/electron-sandbox/workbench/workbench.html'
    }
]);
assert.deepStrictEqual(cursorWithDsmeWebview, { name: 'Cursor', emoji: '🖱️' });

const antigravityProjectNamedDsme = detectAppType([
    {
        type: 'page',
        title: 'dsme — index.css',
        url: 'vscode-file://vscode-app/Applications/Antigravity.app/Contents/Resources/app/out/vs/code/electron-browser/workbench/workbench.html'
    }
]);
assert.deepStrictEqual(antigravityProjectNamedDsme, { name: 'Antigravity', emoji: '🚀' });

const standaloneDsme = detectAppType([
    { type: 'page', title: 'DSME — DeepSeek Matrix Engine', url: 'http://localhost:5173/' }
]);
assert.deepStrictEqual(standaloneDsme, { name: 'DSME', emoji: '🐋' });

const newAntigravityConversationPage = [
    {
        type: 'page',
        title: 'Initiating Technical Assistance',
        url: 'https://127.0.0.1:49690/c/b92dacbe-77bf-4e82-a86e-4a94af55b6b5?section=9c7d4bad'
    }
];
assert.deepStrictEqual(detectAppType(newAntigravityConversationPage), { name: 'Unknown', emoji: '❓' });
assert.deepStrictEqual(detectAppType(newAntigravityConversationPage, { port: 9333 }), { name: 'Antigravity', emoji: '🚀' });
assert.deepStrictEqual(detectAppType(newAntigravityConversationPage, { port: 9334 }), { name: 'Antigravity', emoji: '🚀' });

console.log('cdp_target_detection tests passed');
