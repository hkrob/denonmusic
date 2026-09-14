package com.denonmusic.app.lancontrol

/**
 * The browser GUI for [LanControlServer] - a single self-contained HTML document (inline CSS/JS, no
 * external requests) so the raw socket server doesn't need a second route just to serve a script file.
 * Talks to the same JSON API a script would, over `fetch`, using the token typed into the gate screen.
 *
 * Mirrors the app's own screens as tabs: Now Playing, Queue, AVR, Browse (the HEOS-indexed library)
 * and Files (the SMB bridge fallback browser) - see LanControlManager for the endpoints behind each.
 */
internal const val WEB_UI_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Denon Music Control</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #14161a;
    --card: #1e2126;
    --border: #2c3037;
    --text: #e8eaed;
    --muted: #9aa0a8;
    --accent: #5b8cff;
    --danger: #ff5b6a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    padding: 16px;
    padding-bottom: 48px;
  }
  h1 { font-size: 18px; margin: 0 0 12px; font-weight: 600; }
  h2 { font-size: 14px; margin: 0 0 8px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em; }
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 12px;
    max-width: 560px;
  }
  .row { display: flex; align-items: center; gap: 10px; }
  .row + .row { margin-top: 12px; }
  .spread { justify-content: space-between; }
  .wrap { flex-wrap: wrap; }
  label { font-size: 13px; color: var(--muted); }
  input[type=password], input[type=text] {
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: 8px;
    padding: 10px;
    font-size: 15px;
    flex: 1;
    min-width: 0;
  }
  input[type=range] { flex: 1; }
  select {
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: 8px;
    padding: 8px;
    font-size: 14px;
  }
  button {
    background: #2a2e35;
    border: 1px solid var(--border);
    color: var(--text);
    border-radius: 8px;
    padding: 10px 14px;
    font-size: 14px;
    cursor: pointer;
  }
  button:active { background: #33383f; }
  button.primary { background: var(--accent); border-color: var(--accent); color: #0c1220; font-weight: 600; }
  button.big { font-size: 20px; padding: 12px 18px; min-width: 52px; }
  button.danger { color: var(--danger); }
  button.small { padding: 6px 10px; font-size: 13px; }
  .muted { color: var(--muted); font-size: 13px; }
  .err { color: var(--danger); font-size: 13px; min-height: 16px; }
  .song { font-size: 16px; font-weight: 600; }
  .np { display: flex; gap: 12px; align-items: center; }
  .np img { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; background: #000; flex-shrink: 0; }
  .tabs { display: flex; gap: 6px; margin-bottom: 14px; flex-wrap: wrap; max-width: 560px; }
  .tabs button { flex: 1; min-width: 72px; background: var(--card); }
  .tabs button.active { background: var(--accent); border-color: var(--accent); color: #0c1220; font-weight: 600; }
  .page { max-width: 560px; }
  .breadcrumb { display: flex; flex-wrap: wrap; gap: 4px; font-size: 13px; margin-bottom: 10px; }
  .breadcrumb span { color: var(--accent); cursor: pointer; }
  .breadcrumb .sep { color: var(--muted); cursor: default; }
  .list-item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid var(--border); }
  .list-item:last-child { border-bottom: none; }
  .list-item .meta { min-width: 0; flex: 1; cursor: pointer; }
  .list-item .meta .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .list-item .actions { display: flex; gap: 6px; flex-shrink: 0; }
  .kv { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; font-size: 13px; }
  .kv .k { color: var(--muted); }
  [hidden] { display: none !important; }
</style>
</head>
<body>

<div id="gate" class="card">
  <h1>Denon Music Control</h1>
  <div class="row">
    <input id="tokenInput" type="password" placeholder="LAN control password" autocomplete="off">
    <button id="connectBtn" class="primary">Connect</button>
  </div>
  <div id="gateErr" class="err"></div>
</div>

<div id="app" hidden>
  <h1>Denon Music Control</h1>

  <div class="tabs">
    <button data-tab="now" class="active">Now Playing</button>
    <button data-tab="queue">Queue</button>
    <button data-tab="avr">AVR</button>
    <button data-tab="browse">Browse</button>
    <button data-tab="files">Files</button>
  </div>

  <div id="page-now" class="page">
    <div class="card">
      <div class="np">
        <img id="art" hidden>
        <div style="min-width:0">
          <div id="song" class="song">-</div>
          <div id="artistAlbum" class="muted">-</div>
        </div>
      </div>
      <div class="row spread" style="margin-top:14px">
        <div class="row">
          <button id="prevBtn">&#9664;&#9664;</button>
          <button id="playPauseBtn" class="big primary">&#9654;</button>
          <button id="stopBtn">&#9632;</button>
          <button id="nextBtn">&#9654;&#9654;</button>
        </div>
        <div id="repeatShuffle" class="muted"></div>
      </div>
    </div>
    <div class="card">
      <div class="row">
        <label style="width:56px">Volume</label>
        <input id="volumeSlider" type="range" min="0" max="100" value="0">
        <span id="volumeLabel" class="muted" style="width:32px;text-align:right">0</span>
      </div>
      <div class="row">
        <button id="muteBtn">Mute</button>
        <button id="unmuteBtn">Unmute</button>
      </div>
    </div>
    <div class="card">
      <button id="forgetBtn" class="danger">Forget password</button>
    </div>
  </div>

  <div id="page-queue" class="page" hidden>
    <div class="card">
      <h2>Queue</h2>
      <div id="queueList" class="muted">Loading...</div>
    </div>
  </div>

  <div id="page-avr" class="page" hidden>
    <div class="card">
      <div class="row spread">
        <span>Power: <b id="powerLabel">-</b></span>
        <button id="powerBtn">Toggle</button>
      </div>
      <div class="row">
        <label style="width:56px">Mode</label>
        <select id="soundMode">
          <option value="Movie">Movie</option>
          <option value="Music">Music</option>
          <option value="Game">Game</option>
          <option value="Direct">Direct</option>
          <option value="PureDirect">Pure Direct</option>
          <option value="Stereo">Stereo</option>
          <option value="Auto">Auto</option>
          <option value="MultiChStereo">Multi-Ch Stereo</option>
          <option value="DolbyDigital">Dolby Digital</option>
          <option value="DtsSurround">DTS Surround</option>
          <option value="Virtual">Virtual</option>
          <option value="Auro3d">Auro-3D</option>
          <option value="Auro2dSurround">Auro-2D Surround</option>
        </select>
      </div>
      <div class="row">
        <label style="width:56px">Input</label>
        <input id="inputMnemonic" type="text" placeholder="e.g. NET">
        <button id="inputBtn" class="small">Set</button>
      </div>
      <div class="row">
        <label style="width:56px">Bit-perfect</label>
        <select id="bitPerfect">
          <option value="Off">Off</option>
          <option value="AutoDirect">Auto Direct</option>
          <option value="AutoPureDirect">Auto Pure Direct</option>
        </select>
      </div>
    </div>
    <div class="card">
      <h2>Signal</h2>
      <div class="kv">
        <div class="k">Input</div><div id="avrInput">-</div>
        <div class="k">Signal</div><div id="avrSignal">-</div>
        <div class="k">Sample rate</div><div id="avrSampleRate">-</div>
        <div class="k">Output channels</div><div id="avrChannels">-</div>
      </div>
    </div>
  </div>

  <div id="page-browse" class="page" hidden>
    <div class="card">
      <h2>Library</h2>
      <div id="browseCrumb" class="breadcrumb"></div>
      <div class="row" style="margin-bottom:10px">
        <button id="browsePlayAllBtn" class="small">Play all here</button>
        <button id="browseQueueAllBtn" class="small">Add all to queue</button>
      </div>
      <div id="browseList" class="muted">Loading...</div>
    </div>
  </div>

  <div id="page-files" class="page" hidden>
    <div class="card">
      <h2>SMB share</h2>
      <div id="filesCrumb" class="breadcrumb"></div>
      <div class="row" style="margin-bottom:10px">
        <button id="filesPlayAllBtn" class="small">Play all here</button>
        <button id="filesQueueAllBtn" class="small">Add all to queue</button>
      </div>
      <div id="filesList" class="muted">Loading...</div>
    </div>
  </div>
</div>

<script>
(function () {
  var TOKEN_KEY = "lanControlToken";
  var gate = document.getElementById("gate");
  var app = document.getElementById("app");
  var gateErr = document.getElementById("gateErr");
  var statusTimer = null;
  var queueTimer = null;
  var lastStatus = null;
  var activeTab = "now";

  var browseStack = [];
  var filesPath = "";

  function getToken() { return localStorage.getItem(TOKEN_KEY); }
  function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }
  function clearToken() { localStorage.removeItem(TOKEN_KEY); }

  function api(path, opts) {
    opts = opts || {};
    var headers = opts.headers || {};
    headers["X-Lan-Control-Token"] = getToken() || "";
    return fetch(path, Object.assign({}, opts, { headers: headers })).then(function (res) {
      if (res.status === 401) {
        stopPolling();
        clearToken();
        showGate("Wrong password.");
        throw new Error("unauthorized");
      }
      return res.json();
    });
  }

  function post(path) { return api(path, { method: "POST" }); }

  function withQuery(path, params) {
    var qs = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]);
    }).join("&");
    return path + (qs ? "?" + qs : "");
  }

  function postQuery(path, params) { return post(withQuery(path, params)); }
  function getQuery(path, params) { return api(withQuery(path, params)); }

  function showGate(err) {
    gateErr.textContent = err || "";
    gate.hidden = false;
    app.hidden = true;
  }

  function showApp() {
    gate.hidden = true;
    app.hidden = false;
  }

  function stopPolling() {
    if (statusTimer) clearInterval(statusTimer);
    if (queueTimer) clearInterval(queueTimer);
    statusTimer = null;
    queueTimer = null;
  }

  // ---- tabs ----
  document.querySelectorAll(".tabs button").forEach(function (btn) {
    btn.onclick = function () { selectTab(btn.getAttribute("data-tab")); };
  });

  function selectTab(tab) {
    activeTab = tab;
    document.querySelectorAll(".tabs button").forEach(function (btn) {
      btn.classList.toggle("active", btn.getAttribute("data-tab") === tab);
    });
    ["now", "queue", "avr", "browse", "files"].forEach(function (t) {
      document.getElementById("page-" + t).hidden = t !== tab;
    });
    if (tab === "queue") refreshQueue();
    if (tab === "browse" && browseStack.length === 0) enterBrowse(null, null, "Library");
    if (tab === "files" && document.getElementById("filesList").textContent === "Loading...") loadFiles(filesPath);
  }

  // ---- now playing ----
  function renderStatus(s) {
    lastStatus = s;
    document.getElementById("song").textContent = s.song || "(nothing playing)";
    document.getElementById("artistAlbum").textContent = [s.artist, s.album].filter(Boolean).join(" — ") || "-";
    var art = document.getElementById("art");
    if (s.imageUrl) { art.src = s.imageUrl; art.hidden = false; } else { art.hidden = true; }
    document.getElementById("playPauseBtn").innerHTML = s.playState === "play" ? "&#10074;&#10074;" : "&#9654;";
    var volume = typeof s.volume === "number" ? s.volume : 0;
    document.getElementById("volumeSlider").value = volume;
    document.getElementById("volumeLabel").textContent = volume;
    var rs = [];
    if (s.repeat && s.repeat !== "off") rs.push("repeat: " + s.repeat);
    if (s.shuffle) rs.push("shuffle on");
    document.getElementById("repeatShuffle").textContent = rs.join(", ");

    document.getElementById("powerLabel").textContent = s.avrPower || "unknown";
    if (s.soundMode) document.getElementById("soundMode").value = s.soundMode;
    if (s.bitPerfectPolicy) document.getElementById("bitPerfect").value = s.bitPerfectPolicy;
    document.getElementById("avrInput").textContent = s.input || "-";
    var sig = s.signalType || "-";
    if (s.sampleRateKhz) sig += " " + s.sampleRateKhz + " kHz";
    document.getElementById("avrSignal").textContent = sig;
    document.getElementById("avrSampleRate").textContent = s.sampleRateKhz ? (s.sampleRateKhz + " kHz") : "-";
    document.getElementById("avrChannels").textContent = s.outputChannels ? s.outputChannels.length : "-";
  }

  function refreshStatus() { api("/status").then(renderStatus).catch(function () {}); }

  function startPolling() {
    refreshStatus();
    statusTimer = setInterval(refreshStatus, 2000);
  }

  // ---- queue ----
  function renderQueue(items) {
    var el = document.getElementById("queueList");
    if (!items.length) { el.textContent = "Queue is empty."; return; }
    el.innerHTML = "";
    items.forEach(function (item) {
      var row = document.createElement("div");
      row.className = "list-item";
      var meta = document.createElement("div");
      meta.className = "meta";
      meta.onclick = function () { postQuery("/queue/play", { qid: item.qid }).then(refreshStatus); };
      var t = document.createElement("div");
      t.className = "t";
      t.textContent = item.song || "-";
      var sub = document.createElement("div");
      sub.className = "t muted";
      sub.textContent = [item.artist, item.album].filter(Boolean).join(" — ");
      meta.appendChild(t);
      meta.appendChild(sub);
      row.appendChild(meta);
      el.appendChild(row);
    });
  }

  function refreshQueue() { api("/queue").then(renderQueue).catch(function () {}); }

  // ---- transport / avr controls ----
  document.getElementById("connectBtn").onclick = function () {
    var v = document.getElementById("tokenInput").value;
    if (!v) return;
    setToken(v);
    init();
  };
  document.getElementById("tokenInput").addEventListener("keydown", function (e) {
    if (e.key === "Enter") document.getElementById("connectBtn").click();
  });

  document.getElementById("prevBtn").onclick = function () { post("/previous").then(refreshStatus); };
  document.getElementById("nextBtn").onclick = function () { post("/next").then(refreshStatus); };
  document.getElementById("stopBtn").onclick = function () { post("/stop").then(refreshStatus); };
  document.getElementById("playPauseBtn").onclick = function () {
    var playing = lastStatus && lastStatus.playState === "play";
    post(playing ? "/pause" : "/play").then(refreshStatus);
  };
  document.getElementById("muteBtn").onclick = function () { postQuery("/mute", { on: true }); };
  document.getElementById("unmuteBtn").onclick = function () { postQuery("/mute", { on: false }); };
  document.getElementById("powerBtn").onclick = function () {
    var on = !(lastStatus && lastStatus.avrPower === "On");
    postQuery("/power", { on: on }).then(refreshStatus);
  };
  document.getElementById("soundMode").onchange = function (e) {
    postQuery("/soundmode", { mode: e.target.value }).then(refreshStatus);
  };
  document.getElementById("inputBtn").onclick = function () {
    var v = document.getElementById("inputMnemonic").value.trim();
    if (!v) return;
    postQuery("/input", { mnemonic: v }).then(refreshStatus);
  };
  document.getElementById("bitPerfect").onchange = function (e) {
    postQuery("/bitperfect", { policy: e.target.value }).then(refreshStatus);
  };
  document.getElementById("volumeSlider").addEventListener("change", function (e) {
    postQuery("/volume", { level: e.target.value });
  });
  document.getElementById("volumeLabel").textContent = document.getElementById("volumeSlider").value;
  document.getElementById("volumeSlider").addEventListener("input", function (e) {
    document.getElementById("volumeLabel").textContent = e.target.value;
  });
  document.getElementById("forgetBtn").onclick = function () {
    stopPolling();
    clearToken();
    showGate("");
  };

  // ---- browse (HEOS library) ----
  function renderCrumb(elId, stack, onJump) {
    var el = document.getElementById(elId);
    el.innerHTML = "";
    stack.forEach(function (level, i) {
      var span = document.createElement("span");
      span.textContent = level.name;
      span.onclick = function () { onJump(i); };
      el.appendChild(span);
      if (i < stack.length - 1) {
        var sep = document.createElement("span");
        sep.className = "sep";
        sep.textContent = " / ";
        el.appendChild(sep);
      }
    });
  }

  /**
   * Enters a new level and pushes it onto the breadcrumb (name/sid/cid all describe the level being
   * entered - sid/cid may be null/undefined for "the library's own root", resolved server-side).
   */
  function enterBrowse(sid, cid, name) {
    browseStack.push({ sid: sid, cid: cid, name: name });
    fetchBrowseLevel();
  }

  /** Re-fetches and renders whatever level is currently last on the breadcrumb, without pushing. */
  function fetchBrowseLevel() {
    var level = browseStack[browseStack.length - 1];
    var params = {};
    if (level.sid) params.sid = level.sid;
    if (level.cid) params.cid = level.cid;
    document.getElementById("browseList").textContent = "Loading...";
    renderCrumb("browseCrumb", browseStack, function (i) {
      browseStack = browseStack.slice(0, i + 1);
      fetchBrowseLevel();
    });
    getQuery("/browse", params).then(function (res) {
      // The server resolves an omitted sid to the auto-detected library root - remember it on the
      // breadcrumb entry so navigating back here (or "Play all") doesn't have to re-resolve it.
      level.sid = res.sid;
      level.cid = res.cid;
      renderBrowseList(res.items, res.sid, res.cid);
    }).catch(function () {
      document.getElementById("browseList").textContent = "Couldn't load this folder.";
    });
  }

  function browseLevelParams(sid, cid, extra) {
    var params = Object.assign({ sid: sid }, extra);
    if (cid) params.cid = cid;
    return params;
  }

  function renderBrowseList(items, sid, cid) {
    document.getElementById("browsePlayAllBtn").onclick = function () {
      postQuery("/browse/playall", browseLevelParams(sid, cid, { criteria: "ReplaceAndPlay" })).then(function () { selectTab("now"); refreshStatus(); });
    };
    document.getElementById("browseQueueAllBtn").onclick = function () {
      postQuery("/browse/playall", browseLevelParams(sid, cid, { criteria: "AddToEnd" }));
    };
    var el = document.getElementById("browseList");
    if (!items.length) { el.textContent = "Nothing here."; return; }
    el.innerHTML = "";
    items.forEach(function (item) {
      var row = document.createElement("div");
      row.className = "list-item";
      var meta = document.createElement("div");
      meta.className = "meta";
      var t = document.createElement("div");
      t.className = "t";
      t.textContent = (item.isContainer ? "📁 " : "") + item.name;
      var sub = document.createElement("div");
      sub.className = "t muted";
      sub.textContent = [item.artist, item.album].filter(Boolean).join(" — ");
      meta.appendChild(t);
      meta.appendChild(sub);
      row.appendChild(meta);
      if (item.isContainer) {
        meta.onclick = function () { enterBrowse(item.sid || sid, item.sid ? null : item.cid, item.name); };
      } else if (item.isTrack) {
        var actions = document.createElement("div");
        actions.className = "actions";
        var playBtn = document.createElement("button");
        playBtn.className = "small";
        playBtn.textContent = "Play";
        playBtn.onclick = function () {
          postQuery("/browse/queue", browseLevelParams(sid, item.cid || cid, { mid: item.mid, criteria: "PlayNow" })).then(function () { selectTab("now"); refreshStatus(); });
        };
        var addBtn = document.createElement("button");
        addBtn.className = "small";
        addBtn.textContent = "+";
        addBtn.onclick = function () {
          postQuery("/browse/queue", browseLevelParams(sid, item.cid || cid, { mid: item.mid, criteria: "AddToEnd" }));
        };
        actions.appendChild(playBtn);
        actions.appendChild(addBtn);
        row.appendChild(actions);
      }
      el.appendChild(row);
    });
  }

  // ---- files (SMB fallback) ----
  function loadFiles(path) {
    filesPath = path;
    var segments = path ? path.split("/") : [];
    var crumbStack = [{ name: "root", path: "" }];
    var built = "";
    segments.forEach(function (seg) {
      built = built ? built + "/" + seg : seg;
      crumbStack.push({ name: seg, path: built });
    });
    renderCrumb("filesCrumb", crumbStack, function (i) { loadFiles(crumbStack[i].path); });
    document.getElementById("filesList").textContent = "Loading...";
    getQuery("/smb", { path: path }).then(function (entries) {
      renderFilesList(entries, path);
    }).catch(function () {
      document.getElementById("filesList").textContent = "Couldn't list this folder. Check the SMB share is configured.";
    });
  }

  function renderFilesList(entries, path) {
    document.getElementById("filesPlayAllBtn").onclick = function () {
      postQuery("/smb/play", { path: path }).then(function () { selectTab("now"); refreshStatus(); });
    };
    document.getElementById("filesQueueAllBtn").onclick = function () {
      postQuery("/smb/queue", { path: path });
    };
    var el = document.getElementById("filesList");
    if (!entries.length) { el.textContent = "Empty folder."; return; }
    el.innerHTML = "";
    entries.forEach(function (entry) {
      var row = document.createElement("div");
      row.className = "list-item";
      var meta = document.createElement("div");
      meta.className = "meta";
      var t = document.createElement("div");
      t.className = "t";
      t.textContent = (entry.isDirectory ? "📁 " : "🎵 ") + entry.name;
      meta.appendChild(t);
      row.appendChild(meta);
      if (entry.isDirectory) {
        meta.onclick = function () { loadFiles(entry.path); };
      } else {
        meta.onclick = function () {
          postQuery("/smb/play", { path: path, file: entry.path }).then(function () { selectTab("now"); refreshStatus(); });
        };
      }
      el.appendChild(row);
    });
  }

  function init() {
    var t = getToken();
    if (!t) { showGate(""); return; }
    api("/status").then(function (s) {
      showApp();
      renderStatus(s);
      startPolling();
    }).catch(function () {});
  }

  init();
})();
</script>
</body>
</html>
"""
