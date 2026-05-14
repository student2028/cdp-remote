/**
 * uitty —「新建会话」向导（选目录 → 选 CLI → createTab）
 *
 * 由 cdp-relay 提供静态地址（CORS 已开）：
 *   http://<relay-host>:<port>/uitty_compat/open_new_session_wizard.js
 *
 * 在 uitty 页面中，在 window.uittyAPI 且 createTab 就绪之后引入并安装：
 *
 *   <script src="http://127.0.0.1:19336/uitty_compat/open_new_session_wizard.js"></script>
 *   <script>
 *     UittyWizardCompat.install({
 *       // 可选：默认会试 UITTY_RELAY_HTTP、localStorage uitty.relayHttp、location.origin
 *       getRelayBase: function () { return 'http://192.168.x.x:19336'; }
 *     });
 *   </script>
 *
 * 安装后，远程 App 会通过 CDP 调用 uittyAPI.openNewSessionWizard()，与本向导对接。
 */
(function (global) {
  'use strict';

  function shSingleQuote(s) {
    return "'" + String(s).replace(/'/g, "'\\''") + "'";
  }

  function basename(p) {
    var t = String(p).replace(/\\/g, '/').replace(/\/+$/, '');
    var i = t.lastIndexOf('/');
    return i >= 0 ? t.slice(i + 1) || '/' : t || '/';
  }

  function clearEl(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
  }

  function openWizard(relayBase, api, onFullyClosed) {
    var overlay = document.createElement('div');
    overlay.setAttribute('data-uitty-wizard-overlay', '1');
    overlay.style.cssText =
      'position:fixed;inset:0;background:rgba(0,0,0,.48);z-index:2147483000;' +
      'display:flex;align-items:center;justify-content:center;padding:12px;box-sizing:border-box;';

    var card = document.createElement('div');
    card.style.cssText =
      'background:#1a1b1e;color:#eceff4;max-width:min(540px,100%);width:100%;max-height:88vh;' +
      'overflow:hidden;display:flex;flex-direction:column;border-radius:14px;' +
      'font:15px/1.45 system-ui,-apple-system,sans-serif;box-shadow:0 12px 48px rgba(0,0,0,.55);';

    var head = document.createElement('div');
    head.style.cssText =
      'padding:14px 16px;border-bottom:1px solid #2e3440;font-weight:600;font-size:16px;';
    head.textContent = '新建会话 — 目录 + CLI';

    var body = document.createElement('div');
    body.style.cssText = 'padding:12px 16px;overflow:auto;flex:1;';

    var foot = document.createElement('div');
    foot.style.cssText =
      'padding:12px 16px;border-top:1px solid #2e3440;display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end;';

    function btn(label, primary, fn) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.style.cssText =
        'padding:9px 16px;border-radius:10px;border:0;cursor:pointer;font-weight:600;' +
        (primary ? 'background:#5e81ce;color:#fff;' : 'background:#3b4252;color:#eceff4;');
      b.onclick = fn;
      return b;
    }

    function destroy() {
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      if (typeof onFullyClosed === 'function') onFullyClosed();
    }

    var step = 1;
    var currentPath = '';
    var selectedDir = '';
    var dirsPayload = null;

    function esc(s) {
      var d = document.createElement('div');
      d.textContent = s;
      return d.innerHTML;
    }

    function fetchDirs(absPath, cb) {
      var qs = '';
      if (absPath !== undefined && absPath !== null && absPath !== '')
        qs = '?path=' + encodeURIComponent(absPath);
      fetch(relayBase.replace(/\/$/, '') + '/dirs' + qs, { method: 'GET' })
        .then(function (r) {
          return r.json();
        })
        .then(function (j) {
          dirsPayload = j;
          cb(null, j);
        })
        .catch(function (e) {
          cb(e || new Error('network'));
        });
    }

    function renderDirsLoading() {
      clearEl(body);
      var p = document.createElement('p');
      p.style.color = '#b8bcc8';
      p.textContent = '正在加载目录…';
      body.appendChild(p);
    }

    function renderDirsError(err) {
      clearEl(body);
      var p = document.createElement('p');
      p.style.color = '#bf616a';
      p.textContent = '读取目录失败: ' + (err && err.message ? err.message : String(err));
      body.appendChild(p);
    }

    function renderStep1() {
      step = 1;
      clearEl(foot);
      foot.append(btn('取消', false, destroy));

      renderDirsLoading();
      fetchDirs(currentPath || '', function (err, json) {
        if (err) {
          renderDirsError(err);
          return;
        }
        clearEl(body);
        var cur = json.current || '';
        currentPath = cur;

        var sub = document.createElement('div');
        sub.style.cssText = 'color:#aeb3c2;font-size:12px;margin-bottom:10px;word-break:break-all;';
        sub.innerHTML =
          '<div style="margin-bottom:4px;font-weight:600;color:#eceff4;">选择工作目录</div>' +
          '当前：<code style="background:#252933;padding:2px 6px;border-radius:6px">' +
          esc(cur || '/') +
          '</code>';
        body.appendChild(sub);

        var ul = document.createElement('div');
        ul.style.cssText = 'border:1px solid #2e3440;border-radius:10px;overflow:hidden;';

        function row(label, click) {
          var r = document.createElement('button');
          r.type = 'button';
          r.textContent = label;
          r.style.cssText =
            'display:block;width:100%;text-align:left;padding:12px 14px;border:0;' +
            'border-bottom:1px solid #2e3440;background:#1f2229;color:#eceff4;cursor:pointer;' +
            'font:inherit;';
          r.onmouseover = function () {
            r.style.background = '#252933';
          };
          r.onmouseout = function () {
            r.style.background = '#1f2229';
          };
          r.onclick = click;
          return r;
        }

        var parentVal = json.parent;
        var curRawStr = json.current || '';
        var showParent =
          parentVal != null &&
          String(parentVal).length > 0 &&
          String(parentVal) !== String(curRawStr);
        if (showParent) {
          ul.appendChild(
            row('⬆ 上一级', function () {
              currentPath = parentVal;
              renderStep1();
            })
          );
        }

        var dirs = json.dirs || [];
        if (dirs.length === 0) {
          var emptyHint = document.createElement('div');
          emptyHint.style.cssText = 'padding:12px 14px;color:#b8bcc8;font-size:13px;';
          emptyHint.textContent = '当前目录下没有子文件夹，可直接点下方「在此目录启动」。';
          ul.appendChild(emptyHint);
        } else {
          dirs.forEach(function (d) {
            ul.appendChild(
              row('📁 ' + d.name, function () {
                currentPath = d.path;
                renderStep1();
              })
            );
          });
        }

        body.appendChild(ul);
        clearEl(foot);
        foot.appendChild(btn('取消', false, destroy));
        foot.appendChild(
          btn('在此目录启动', true, function () {
            selectedDir = cur;
            if (!selectedDir) {
              alert('无法确定路径');
              return;
            }
            renderStep2();
          })
        );
      });
    }

    function renderStep2() {
      step = 2;
      clearEl(body);

      var info = document.createElement('div');
      info.style.cssText = 'color:#aeb3c2;font-size:12px;margin-bottom:12px;word-break:break-all;';
      info.innerHTML =
        '<div style="font-weight:600;color:#eceff4;margin-bottom:6px;">启动 CLI</div>' +
        '目录：<code style="background:#252933;padding:2px 6px;border-radius:6px">' +
        esc(selectedDir) +
        '</code>';
      body.appendChild(info);

      var presetsWrap = document.createElement('div');
      presetsWrap.style.cssText =
        'display:flex;gap:10px;flex-wrap:wrap;margin-bottom:14px;align-items:center;';
      body.appendChild(presetsWrap);

      function mkPreset(label, cmd, emoji, color) {
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = label;
        b.style.cssText =
          'padding:10px 14px;border-radius:10px;border:0;cursor:pointer;font-weight:700;' +
          'background:' +
          color +
          ';color:#fff;';
        b.onclick = function () {
          launch(cmd, emoji, label.slice(0, 16));
        };
        presetsWrap.appendChild(b);
      }

      mkPreset('Claude Code', 'claude --permission-mode bypassPermissions', '🧠', '#a855bc');
      mkPreset('OpenCode', 'opencode', '◆', '#0891b2');
      mkPreset('Aider', 'aider', '🤝', '#16a34a');

      var customLab = document.createElement('label');
      customLab.style.cssText = 'display:block;color:#cfd1db;font-size:13px;margin-bottom:6px;';
      customLab.textContent = '其它命令（不包含 cd）';
      body.appendChild(customLab);

      var input = document.createElement('input');
      input.type = 'text';
      input.placeholder = '例如: codex 、 gemini';
      input.style.cssText =
        'width:100%;padding:11px 12px;border-radius:10px;border:1px solid #3b4252;' +
        'background:#252933;color:#eceff4;font:inherit;box-sizing:border-box;';
      body.appendChild(input);

      clearEl(foot);
      foot.appendChild(
        btn('返回', false, function () {
          renderStep1();
        })
      );
      foot.appendChild(btn('取消', false, destroy));
      foot.appendChild(
        btn('启动自定义', true, function () {
          var t = input.value.trim();
          if (!t) {
            alert('请输入命令');
            return;
          }
          launch(t, '⚙️', basename(selectedDir));
        })
      );

      input.onkeydown = function (ev) {
        if (ev.key === 'Enter') {
          var t = input.value.trim();
          if (t) launch(t, '⚙️', basename(selectedDir));
        }
      };

      input.focus();

      function launch(cliLine, emoji, tabHint) {
        var shellCmd = 'cd ' + shSingleQuote(selectedDir) + ' && ' + cliLine;
        var tabName = tabHint + ' · ' + basename(selectedDir);
        try {
          api.createTab(tabName, shellCmd, emoji);
          destroy();
        } catch (e) {
          alert('createTab 失败: ' + (e && e.message ? e.message : e));
        }
      }
    }

    overlay.appendChild(card);
    card.appendChild(head);
    card.appendChild(body);
    card.appendChild(foot);
    document.body.appendChild(overlay);

    renderStep1();
  }

  function install(opts) {
    opts = opts || {};
    var w = global.window || global;
    var api = w.uittyAPI;
    if (!api || typeof api.createTab !== 'function') {
      console.warn('[UittyWizardCompat] 需要已有 window.uittyAPI.createTab，安装已跳过');
      return false;
    }
    if (typeof api.openNewSessionWizard === 'function') {
      console.info('[UittyWizardCompat] openNewSessionWizard 已由 uitty 提供，跳过覆盖');
      return true;
    }

    var getRelayBase =
      opts.getRelayBase ||
      function () {
        if (global.UITTY_RELAY_HTTP)
          return String(global.UITTY_RELAY_HTTP).replace(/\/$/, '');
        try {
          var h = global.localStorage && global.localStorage.getItem('uitty.relayHttp');
          if (h) return String(h).replace(/\/$/, '');
        } catch (e1) {}
        if (global.location && /^https?:/.test(global.location.protocol))
          return global.location.origin.replace(/\/$/, '');
        return 'http://127.0.0.1:19336';
      };

    var busy = false;
    api.openNewSessionWizard = function () {
      if (busy) return true;
      busy = true;
      try {
        openWizard(getRelayBase(), api, function () {
          busy = false;
        });
      } catch (e) {
        busy = false;
        console.error('[UittyWizardCompat]', e);
        return false;
      }
      return true;
    };
    console.info('[UittyWizardCompat] 已安装 openNewSessionWizard（Relay: ' + getRelayBase() + '）');
    return true;
  }

  var exp = { install: install, shSingleQuote: shSingleQuote };
  global.UittyWizardCompat = exp;
  if (typeof module !== 'undefined' && module.exports) module.exports = exp;
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this);
