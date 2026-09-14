package com.denonmusic.app.lancontrol

/**
 * The browser GUI for [LanControlServer] - a single self-contained HTML document (inline CSS/JS, no
 * external requests) so the raw socket server doesn't need a second route just to serve a script file.
 * Talks to the same JSON API a script would, over `fetch`, using the token typed into the gate screen.
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
  h1 { font-size: 18px; margin: 0 0 16px; font-weight: 600; }
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 12px;
    max-width: 480px;
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
  .muted { color: var(--muted); font-size: 13px; }
  .err { color: var(--danger); font-size: 13px; min-height: 16px; }
  .song { font-size: 16px; font-weight: 600; }
  .np { display: flex; gap: 12px; align-items: center; }
  .np img { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; background: #000; flex-shrink: 0; }
  .queue-item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--border); }
  .queue-item:last-child { border-bottom: none; }
  .queue-item .meta { min-width: 0; }
  .queue-item .meta .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
    <div class="row spread">
      <span>AVR power: <b id="powerLabel">-</b></span>
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
  </div>

  <div class="card">
    <div class="row spread">
      <b>Queue</b>
      <button id="forgetBtn" class="danger">Forget password</button>
    </div>
    <div id="queueList" class="muted">Loading...</div>
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

  function postQuery(path, params) {
    var qs = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]);
    }).join("&");
    return post(path + "?" + qs);
  }

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
  }

  function renderQueue(items) {
    var el = document.getElementById("queueList");
    if (!items.length) { el.textContent = "Queue is empty."; return; }
    el.innerHTML = "";
    items.forEach(function (item) {
      var row = document.createElement("div");
      row.className = "queue-item";
      var meta = document.createElement("div");
      meta.className = "meta";
      var t = document.createElement("div");
      t.className = "t";
      t.textContent = item.song || "-";
      var sub = document.createElement("div");
      sub.className = "t muted";
      sub.textContent = [item.artist, item.album].filter(Boolean).join(" — ");
      meta.appendChild(t);
      meta.appendChild(sub);
      var playBtn = document.createElement("button");
      playBtn.textContent = "Play";
      playBtn.onclick = function () { postQuery("/queue/play", { qid: item.qid }).then(refreshStatus); };
      row.appendChild(meta);
      row.appendChild(playBtn);
      el.appendChild(row);
    });
  }

  function refreshStatus() { api("/status").then(renderStatus).catch(function () {}); }
  function refreshQueue() { api("/queue").then(renderQueue).catch(function () {}); }

  function startPolling() {
    refreshStatus();
    refreshQueue();
    statusTimer = setInterval(refreshStatus, 2000);
    queueTimer = setInterval(refreshQueue, 8000);
  }

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
