(() => {
  'use strict';

  const $ = (sel) => document.querySelector(sel);
  const state = {
    name: '',
    clientId: '',
    self: null,
    token: null,
    room: null,
    users: [],
    messages: [],
    ids: new Set(),
    lastId: 0,
    epoch: null,
    connected: false,
    ws: null,
    reconnectDelay: 1000,
    reconnectTimer: null,
    pingTimer: null,
    lastFrame: 0,
    pending: new Map(),
    uploadChain: Promise.resolve(),
    embed: false,
    lastDate: '',
  };

  const els = {
    messages: $('#messages'),
    composer: $('#composer'),
    text: $('#text-input'),
    send: $('#send-btn'),
    attach: $('#attach-btn'),
    fileInput: $('#file-input'),
    joinPanel: $('#join-panel'),
    joinBtn: $('#join-btn'),
    nameInput: $('#name-input'),
    joinError: $('#join-error'),
    banner: $('#conn-banner'),
    userCount: $('#user-count'),
    usersBtn: $('#users-btn'),
    usersSheet: $('#users-sheet'),
    usersList: $('#users-list'),
    roomName: $('#room-name'),
    newMsg: $('#new-msg-btn'),
    lightbox: $('#lightbox'),
    lightboxImg: $('#lightbox-img'),
    lightboxDownload: $('#lightbox-download'),
    toast: $('#toast'),
    exitBtn: $('#exit-btn'),
  };

  function storageGet(store, key) {
    try { return store.getItem(key); } catch (_) { return null; }
  }
  function storageSet(store, key, value) {
    try { store.setItem(key, value); } catch (_) { /* private mode */ }
  }
  function randomId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      try { return window.crypto.randomUUID(); } catch (_) { /* fall through */ }
    }
    return 'c-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }
  function cleanName(raw) {
    return String(raw || '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 20);
  }

  function init() {
    const params = new URLSearchParams(location.search);
    const queryName = cleanName(params.get('name') || '');
    state.embed = params.get('embed') === '1';
    state.clientId = storageGet(sessionStorage, 'teatalk.clientId') || randomId();
    storageSet(sessionStorage, 'teatalk.clientId', state.clientId);
    state.name = queryName || cleanName(storageGet(localStorage, 'teatalk.name') || '');
    if (state.embed) els.exitBtn.classList.remove('hidden');
    els.exitBtn.addEventListener('click', () => { location.href = 'teatalk://exit'; });

    if (state.name) {
      els.nameInput.value = state.name;
      hideJoin();
      connect();
    } else {
      showJoin();
    }

    bindEvents();
    setBanner('正在连接…');
  }

  function bindEvents() {
    els.joinBtn.addEventListener('click', join);
    els.nameInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') { event.preventDefault(); join(); }
    });
    els.composer.addEventListener('submit', (event) => { event.preventDefault(); sendText(); });
    els.text.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendText(); }
    });
    els.text.addEventListener('input', autoGrow);
    els.attach.addEventListener('click', () => els.fileInput.click());
    els.fileInput.addEventListener('change', () => {
      const files = Array.from(els.fileInput.files || []);
      els.fileInput.value = '';
      queueUploads(files);
    });
    els.messages.addEventListener('dragover', (event) => {
      if (event.dataTransfer && Array.from(event.dataTransfer.types).includes('Files')) {
        event.preventDefault();
        els.messages.classList.add('drag-over');
      }
    });
    els.messages.addEventListener('dragleave', () => els.messages.classList.remove('drag-over'));
    els.messages.addEventListener('drop', (event) => {
      if (!event.dataTransfer) return;
      event.preventDefault();
      els.messages.classList.remove('drag-over');
      queueUploads(Array.from(event.dataTransfer.files || []));
    });
    els.text.addEventListener('paste', (event) => {
      const items = event.clipboardData ? Array.from(event.clipboardData.items || []) : [];
      const files = items.filter((item) => item.kind === 'file').map((item) => item.getAsFile()).filter(Boolean);
      if (files.length > 0) { event.preventDefault(); queueUploads(files); }
    });
    els.usersBtn.addEventListener('click', openUsers);
    document.querySelectorAll('[data-close="users"]').forEach((el) => el.addEventListener('click', closeUsers));
    document.querySelectorAll('[data-close="lightbox"]').forEach((el) => el.addEventListener('click', closeLightbox));
    els.newMsg.addEventListener('click', () => scrollToBottom(true));
    els.messages.addEventListener('scroll', () => {
      if (isNearBottom()) els.newMsg.classList.add('hidden');
    });
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && !state.connected) connect();
    });
    window.addEventListener('online', () => { if (!state.connected) connect(); });
    window.setInterval(watchdog, 10000);
  }

  function autoGrow() {
    els.text.style.height = 'auto';
    els.text.style.height = Math.min(els.text.scrollHeight, 120) + 'px';
  }

  function showJoin() {
    els.joinPanel.classList.remove('hidden');
    els.nameInput.focus();
  }
  function hideJoin() { els.joinPanel.classList.add('hidden'); }

  function join() {
    const name = cleanName(els.nameInput.value);
    if (!name) {
      els.joinError.textContent = '先给自己起个名字';
      return;
    }
    state.name = name;
    storageSet(localStorage, 'teatalk.name', name);
    els.joinError.textContent = '';
    hideJoin();
    connect();
  }

  function connect() {
    if (!state.name) { showJoin(); return; }
    if (state.ws && (state.ws.readyState === WebSocket.OPEN || state.ws.readyState === WebSocket.CONNECTING)) return;
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    setBanner('正在连接…');
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    let ws;
    try {
      ws = new WebSocket(`${scheme}://${location.host}/ws`);
    } catch (_) {
      scheduleReconnect();
      return;
    }
    state.ws = ws;
    ws.addEventListener('open', () => {
      state.reconnectDelay = 1000;
      state.lastFrame = Date.now();
      ws.send(JSON.stringify({
        v: 1,
        t: 'hello',
        name: state.name,
        clientId: state.clientId,
        after: state.lastId || 0,
      }));
      if (state.pingTimer) clearInterval(state.pingTimer);
      state.pingTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ t: 'ping' }));
      }, 25000);
    });
    ws.addEventListener('message', (event) => {
      state.lastFrame = Date.now();
      let data;
      try { data = JSON.parse(event.data); } catch (_) { return; }
      handle(data);
    });
    ws.addEventListener('close', () => {
      if (state.ws === ws) state.ws = null;
      state.connected = false;
      setConnectedUi(false);
      if (state.pingTimer) { clearInterval(state.pingTimer); state.pingTimer = null; }
      setBanner('连接断开，正在重连…');
      scheduleReconnect();
    });
    ws.addEventListener('error', () => { try { ws.close(); } catch (_) {} });
  }

  function scheduleReconnect() {
    if (state.reconnectTimer) return;
    const delay = Math.min(state.reconnectDelay, 10000);
    state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connect();
    }, delay);
  }

  function watchdog() {
    if (!state.connected) return;
    if (Date.now() - state.lastFrame > 60000) {
      try { state.ws.close(); } catch (_) {}
    }
  }

  function handle(data) {
    switch (data.t) {
      case 'snapshot': {
        state.self = data.self || null;
        state.token = data.self ? data.self.token : null;
        state.room = data.room || null;
        state.users = data.users || [];
        if (state.room) {
          if (state.epoch && state.room.epoch !== state.epoch) resetMessages();
          state.epoch = state.room.epoch;
          els.roomName.textContent = state.room.name || '';
        }
        const incoming = data.messages || [];
        if (state.messages.length === 0 || incoming.length === 0) {
          // First connect or empty room: rebuild from snapshot.
          resetMessages();
          for (const msg of incoming) mergeMessage(msg, false);
          renderAll();
        } else {
          for (const msg of incoming) mergeMessage(msg, true);
        }
        state.connected = true;
        setConnectedUi(true);
        setBanner('');
        renderUsers();
        break;
      }
      case 'message':
        state.connected = true;
        setConnectedUi(true);
        setBanner('');
        if (data.message) mergeMessage(data.message, true);
        break;
      case 'users':
        state.users = data.users || [];
        renderUsers();
        break;
      case 'room':
        if (data.room) {
          state.room = data.room;
          els.roomName.textContent = data.room.name || '';
        }
        break;
      case 'cleared':
        if (data.room) {
          state.room = data.room;
          state.epoch = data.room.epoch;
          els.roomName.textContent = data.room.name || '';
        }
        resetMessages();
        renderAll();
        break;
      case 'pong':
        break;
      case 'error':
        toast(data.message || '操作失败');
        break;
    }
  }

  function resetMessages() {
    state.messages = [];
    state.ids.clear();
    state.lastId = 0;
    state.lastDate = '';
    els.messages.innerHTML = '';
  }

  function mergeMessage(msg, animate) {
    if (!msg || typeof msg.id !== 'number') return false;
    if (state.ids.has(msg.id)) {
      removePending(msg.clientMsgId);
      return false;
    }
    state.ids.add(msg.id);
    state.messages.push(msg);
    state.lastId = Math.max(state.lastId, msg.id);
    removePending(msg.clientMsgId);
    if (!animate) return true;
    appendMessage(msg, true);
    return true;
  }

  function appendMessage(msg, scroll) {
    removeEmptyState();
    const dateLabel = formatDate(msg.ts);
    if (dateLabel !== state.lastDate) {
      state.lastDate = dateLabel;
      const sep = document.createElement('div');
      sep.className = 'date-sep';
      sep.textContent = dateLabel;
      els.messages.appendChild(sep);
    }
    const wasNearBottom = isNearBottom();
    els.messages.appendChild(messageElement(msg));
    if (scroll && (wasNearBottom || isOwn(msg))) scrollToBottom(false);
    else if (!wasNearBottom) els.newMsg.classList.remove('hidden');
  }

  function renderAll() {
    const oldScroll = els.messages.scrollTop;
    els.messages.innerHTML = '';
    state.lastDate = '';
    if (state.messages.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.innerHTML = '<strong>还没有人说话</strong>打个招呼，或者拖个文件进来。';
      els.messages.appendChild(empty);
      return;
    }
    for (const msg of state.messages) appendMessage(msg, false);
    els.messages.scrollTop = oldScroll;
    scrollToBottom(false);
  }

  function removeEmptyState() {
    const empty = els.messages.querySelector('.empty-state');
    if (empty) empty.remove();
  }

  function messageElement(msg) {
    const own = isOwn(msg);
    const row = document.createElement('div');
    row.className = 'msg ' + (own ? 'own' : 'other');

    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    if (!own) {
      const sender = document.createElement('span');
      sender.className = 'msg-sender';
      sender.textContent = msg.senderName || '访客';
      meta.appendChild(sender);
    }
    const time = document.createElement('span');
    time.className = 'msg-time';
    time.textContent = formatTime(msg.ts);
    meta.appendChild(time);
    row.appendChild(meta);

    if (msg.kind === 'file' && msg.file) {
      row.appendChild(fileElement(msg));
    } else {
      const bubble = document.createElement('div');
      bubble.className = 'bubble';
      renderText(bubble, msg.text || '');
      row.appendChild(bubble);
    }
    return row;
  }

  function renderText(container, text) {
    const parts = String(text).split(/(https?:\/\/[^\s<]+)/g);
    for (const part of parts) {
      if (!part) continue;
      if (/^https?:\/\//.test(part)) {
        const link = document.createElement('a');
        link.href = part;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = part;
        container.appendChild(link);
      } else {
        container.appendChild(document.createTextNode(part));
      }
    }
  }

  function fileElement(msg) {
    const file = msg.file;
    const image = isImage(file);
    if (image) {
      const wrap = document.createElement('div');
      wrap.className = 'image-bubble';
      const img = document.createElement('img');
      img.src = fileUrl(file);
      img.alt = file.name;
      img.loading = 'lazy';
      img.addEventListener('click', () => openLightbox(file));
      img.addEventListener('error', () => {
        wrap.replaceWith(fileCard(file));
      });
      wrap.appendChild(img);
      return wrap;
    }
    return fileCard(file);
  }

  function fileCard(file) {
    const card = document.createElement('a');
    card.className = 'file-card';
    card.href = fileUrl(file);
    card.download = file.name;
    const icon = document.createElement('div');
    icon.className = 'file-icon';
    icon.textContent = fileIcon(file.name);
    const info = document.createElement('div');
    info.className = 'file-info';
    const name = document.createElement('div');
    name.className = 'file-name';
    name.textContent = file.name;
    const size = document.createElement('div');
    size.className = 'file-size';
    size.textContent = formatSize(file.size);
    info.append(name, size);
    const download = document.createElement('span');
    download.className = 'file-download';
    download.textContent = '下载';
    card.append(icon, info, download);
    card.addEventListener('click', (event) => {
      // Let the browser/WebView handle the download navigation.
      if (!state.connected) event.preventDefault();
    });
    return card;
  }

  function queueUploads(files) {
    if (!files || files.length === 0) return;
    if (!state.connected || !state.token) { toast('还没有连上房间'); return; }
    for (const file of files) {
      state.uploadChain = state.uploadChain.then(() => uploadOne(file)).catch(() => {});
    }
  }

  function uploadOne(file) {
    return new Promise((resolve) => {
      const max = state.room ? state.room.maxFileBytes : 0;
      if (max && file.size > max) {
        toast(`文件太大，最大 ${formatSize(max)}`);
        resolve();
        return;
      }
      const clientMsgId = randomId();
      const pending = addPending(file);
      state.pending.set(clientMsgId, pending);
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', '/api/upload?name=' + encodeURIComponent(file.name));
      xhr.setRequestHeader('X-TeaTalk-Session', state.token || '');
      xhr.setRequestHeader('X-Client-Msg-Id', clientMsgId);
      xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) setPendingProgress(pending, event.loaded, event.total);
      };
      xhr.onload = () => {
        if (xhr.status === 201) {
          let message = null;
          try { message = JSON.parse(xhr.responseText).message; } catch (_) {}
          removePending(clientMsgId);
          if (message) {
            if (!state.ids.has(message.id)) {
              mergeMessage(message, true);
            }
          }
        } else {
          removePending(clientMsgId);
          toast(xhr.status === 401 ? '连接过期，正在重连' : '上传失败');
          if (xhr.status === 401) forceReconnect();
        }
        resolve();
      };
      xhr.onerror = () => { removePending(clientMsgId); toast('上传失败'); resolve(); };
      xhr.onabort = () => { removePending(clientMsgId); resolve(); };
      xhr.send(file);
    });
  }

  function addPending(file) {
    removeEmptyState();
    const row = document.createElement('div');
    row.className = 'msg own';
    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    const time = document.createElement('span');
    time.className = 'msg-time';
    time.textContent = '发送中';
    meta.appendChild(time);
    row.appendChild(meta);
    const card = document.createElement('div');
    card.className = 'pending-card';
    const top = document.createElement('div');
    top.className = 'pending-top';
    const icon = document.createElement('div');
    icon.className = 'file-icon';
    icon.textContent = fileIcon(file.name);
    const name = document.createElement('div');
    name.className = 'pending-name';
    name.textContent = file.name;
    top.append(icon, name);
    const track = document.createElement('div');
    track.className = 'progress-track';
    const bar = document.createElement('div');
    bar.className = 'progress-bar';
    track.appendChild(bar);
    const status = document.createElement('div');
    status.className = 'pending-status';
    status.textContent = '等待上传';
    card.append(top, track, status);
    row.appendChild(card);
    els.messages.appendChild(row);
    if (isNearBottom()) scrollToBottom(false);
    return { row, bar, status };
  }

  function setPendingProgress(pending, loaded, total) {
    const percent = total > 0 ? Math.round((loaded / total) * 100) : 0;
    pending.bar.style.width = percent + '%';
    pending.status.textContent = percent + '% · ' + formatSize(loaded) + ' / ' + formatSize(total);
  }

  function removePending(clientMsgId) {
    if (!clientMsgId) return;
    const pending = state.pending.get(clientMsgId);
    if (!pending) return;
    pending.row.remove();
    state.pending.delete(clientMsgId);
  }

  function forceReconnect() {
    if (state.ws) { try { state.ws.close(); } catch (_) {} }
  }

  function sendText() {
    const text = els.text.value.trim();
    if (!text) return;
    if (!state.connected || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      toast('还没有连上房间');
      return;
    }
    state.ws.send(JSON.stringify({ t: 'text', text, clientMsgId: randomId() }));
    els.text.value = '';
    autoGrow();
  }

  function renderUsers() {
    els.userCount.textContent = String(state.users.length);
    els.usersList.innerHTML = '';
    for (const user of state.users) {
      const li = document.createElement('li');
      const avatar = document.createElement('div');
      avatar.className = 'avatar';
      avatar.textContent = initials(user.name);
      const name = document.createElement('span');
      name.className = 'user-name';
      name.textContent = user.name;
      li.append(avatar, name);
      if (state.self && user.id === state.self.id) {
        const you = document.createElement('span');
        you.className = 'user-you';
        you.textContent = '（你）';
        li.appendChild(you);
      }
      els.usersList.appendChild(li);
    }
  }

  function openUsers() {
    renderUsers();
    els.usersSheet.classList.remove('hidden');
  }
  function closeUsers() { els.usersSheet.classList.add('hidden'); }

  function openLightbox(file) {
    els.lightboxImg.src = fileUrl(file);
    els.lightboxImg.alt = file.name;
    els.lightboxDownload.href = fileUrl(file);
    els.lightboxDownload.download = file.name;
    els.lightbox.classList.remove('hidden');
  }
  function closeLightbox() {
    els.lightbox.classList.add('hidden');
    els.lightboxImg.src = '';
  }

  function isOwn(msg) {
    return Boolean(state.self && msg.senderId === state.self.id);
  }

  function isImage(file) {
    if (!file) return false;
    if (file.mime && /^image\/(jpeg|png|gif|webp|avif|heic|heif)$/.test(file.mime)) return true;
    return /\.(jpe?g|png|gif|webp|avif|heic|heif)$/i.test(file.name || '');
  }

  function fileIcon(name) {
    const ext = String(name || '').split('.').pop().toLowerCase();
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'heic'].includes(ext)) return '🖼';
    if (['mp4', 'mov', 'mkv', 'webm'].includes(ext)) return '🎬';
    if (['mp3', 'wav', 'm4a', 'flac'].includes(ext)) return '🎵';
    if (['pdf'].includes(ext)) return '📕';
    if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return '🗜';
    if (['doc', 'docx', 'txt', 'md'].includes(ext)) return '📄';
    return '📎';
  }

  function fileUrl(file) {
    return '/api/files/' + encodeURIComponent(file.id) + '/' + encodeURIComponent(file.name || 'file');
  }

  function initials(name) {
    const clean = String(name || '?').trim();
    return clean ? Array.from(clean).slice(0, 2).join('') : '?';
  }

  function formatSize(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function formatTime(ts) {
    if (!ts) return '';
    try {
      return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    } catch (_) { return ''; }
  }

  function formatDate(ts) {
    const date = new Date(ts || Date.now());
    const today = new Date();
    const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
    const startOfDate = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
    const diff = Math.round((startOfToday - startOfDate) / 86400000);
    if (diff === 0) return '今天';
    if (diff === 1) return '昨天';
    return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
  }

  function isNearBottom() {
    return els.messages.scrollHeight - els.messages.scrollTop - els.messages.clientHeight < 120;
  }
  function scrollToBottom(smooth) {
    els.messages.scrollTo({ top: els.messages.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    els.newMsg.classList.add('hidden');
  }

  function setBanner(text) {
    if (!text) { els.banner.classList.add('hidden'); els.banner.textContent = ''; return; }
    els.banner.textContent = text;
    els.banner.classList.remove('hidden');
  }

  function setConnectedUi(connected) {
    els.usersBtn.classList.toggle('offline', !connected);
  }

  let toastTimer = null;
  function toast(message) {
    els.toast.textContent = message;
    els.toast.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => els.toast.classList.add('hidden'), 2600);
  }

  function syncViewportHeight() {
    const viewport = window.visualViewport;
    const height = (viewport && viewport.height) || window.innerHeight || document.documentElement.clientHeight;
    const top = (viewport && viewport.offsetTop) || 0;
    if (height > 0) {
      document.documentElement.style.setProperty('--app-height', Math.round(height) + 'px');
      document.documentElement.style.setProperty('--app-top', Math.round(top) + 'px');
    }
    // iOS Safari scrolls the whole page when an input near the bottom focuses.
    // The app is fixed, so undo that scroll and keep the composer above the IME.
    if (window.scrollY !== 0 || window.scrollX !== 0) {
      window.scrollTo(0, 0);
    }
  }

  function scheduleViewportSync() {
    requestAnimationFrame(syncViewportHeight);
    setTimeout(syncViewportHeight, 80);
    setTimeout(syncViewportHeight, 250);
  }

  init();
  syncViewportHeight();
  window.addEventListener('resize', syncViewportHeight, { passive: true });
  window.addEventListener('scroll', syncViewportHeight, { passive: true });
  window.addEventListener('orientationchange', () => scheduleViewportSync(), { passive: true });
  document.addEventListener('focusin', scheduleViewportSync, true);
  document.addEventListener('focusout', scheduleViewportSync, true);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncViewportHeight, { passive: true });
    // On iOS the visual viewport can scroll independently; reposition #app.
    window.visualViewport.addEventListener('scroll', syncViewportHeight, { passive: true });
  }
  setTimeout(syncViewportHeight, 80);
  setTimeout(syncViewportHeight, 400);
})();
