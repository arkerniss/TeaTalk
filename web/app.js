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
    groupSender: null,
    groupTs: 0,
    files: [],
    filesOpen: false,
    clearBusy: false,
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
    menuBtn: $('#menu-btn'),
    menuSheet: $('#menu-sheet'),
    menuFiles: $('#menu-files'),
    menuClear: $('#menu-clear'),
    filesView: $('#files-view'),
    filesList: $('#files-list'),
    filesBack: $('#files-back'),
    filesRefresh: $('#files-refresh'),
    clearDialog: $('#clear-dialog'),
    clearConfirm: $('#clear-confirm'),
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
    els.menuBtn.addEventListener('click', openMenu);
    document.querySelectorAll('[data-close="menu"]').forEach((el) => el.addEventListener('click', closeMenu));
    els.menuFiles.addEventListener('click', openFiles);
    els.menuClear.addEventListener('click', openClearDialog);
    document.querySelectorAll('[data-close="clear"]').forEach((el) => el.addEventListener('click', closeClearDialog));
    els.clearConfirm.addEventListener('click', clearChatHistory);
    els.filesBack.addEventListener('click', closeFiles);
    els.filesRefresh.addEventListener('click', loadFiles);
    document.querySelectorAll('[data-close="lightbox"]').forEach((el) => el.addEventListener('click', closeLightbox));
    document.addEventListener('keydown', (event) => {
      if (event.key !== 'Escape') return;
      if (!els.clearDialog.classList.contains('hidden')) { closeClearDialog(); return; }
      if (state.filesOpen) { closeFiles(); return; }
      if (!els.menuSheet.classList.contains('hidden')) { closeMenu(); return; }
      if (!els.usersSheet.classList.contains('hidden')) closeUsers();
    });
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
    els.text.style.height = Math.min(els.text.scrollHeight, 128) + 'px';
    els.send.classList.toggle('is-idle', !els.text.value.trim());
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
      case 'files-changed':
        if (state.filesOpen) loadFiles();
        break;
      case 'pong':
        break;
      case 'error':
        toast(data.message || '操作失败');
        break;
    }
  }

  const GROUP_WINDOW_MS = 5 * 60 * 1000;

  function resetMessages() {
    state.messages = [];
    state.ids.clear();
    state.lastId = 0;
    state.lastDate = '';
    state.groupSender = null;
    state.groupTs = 0;
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
    const newDay = dateLabel !== state.lastDate;
    if (newDay) {
      state.lastDate = dateLabel;
      const sep = document.createElement('div');
      sep.className = 'date-sep';
      sep.textContent = dateLabel;
      els.messages.appendChild(sep);
      state.groupSender = null;
      state.groupTs = 0;
    }
    // Consecutive messages from the same person inside the window share one
    // sender/time header and tighten their vertical rhythm.
    const sender = msg.senderId || null;
    const sameGroup = !newDay && sender !== null && sender === state.groupSender &&
      msg.ts - state.groupTs < GROUP_WINDOW_MS;
    const wasNearBottom = isNearBottom();
    const row = messageElement(msg, !sameGroup);
    if (sameGroup) row.classList.add('group-has-prev');
    els.messages.appendChild(row);
    state.groupSender = sender;
    state.groupTs = msg.ts;
    if (scroll && (wasNearBottom || isOwn(msg))) scrollToBottom(false);
    else if (!wasNearBottom) els.newMsg.classList.remove('hidden');
  }

  function renderAll() {
    const oldScroll = els.messages.scrollTop;
    els.messages.innerHTML = '';
    state.lastDate = '';
    state.groupSender = null;
    state.groupTs = 0;
    if (state.messages.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.innerHTML = '<svg class="cup" aria-hidden="true" focusable="false">' +
        '<use href="#i-cup"/></svg><strong>还没有人说话</strong>' +
        '<span>打个招呼，或者拖个文件进来。</span>';
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

  function messageElement(msg, showMeta) {
    const own = isOwn(msg);
    const row = document.createElement('div');
    row.className = 'msg ' + (own ? 'own' : 'other');

    if (showMeta) {
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
    }

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
    icon.textContent = fileExt(file.name);
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
    icon.textContent = fileExt(file.name);
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
    closeMenu();
    renderUsers();
    els.usersSheet.classList.remove('hidden');
  }
  function closeUsers() { els.usersSheet.classList.add('hidden'); }

  function openMenu() {
    closeUsers();
    els.menuSheet.classList.remove('hidden');
  }
  function closeMenu() { els.menuSheet.classList.add('hidden'); }

  async function apiFetch(path, options) {
    const opts = options || {};
    const headers = Object.assign({}, opts.headers || {});
    if (state.token) headers['X-TeaTalk-Session'] = state.token;
    const response = await fetch(path, Object.assign({}, opts, { headers }));
    if (response.status === 401) {
      forceReconnect();
      throw new Error('session expired');
    }
    if (!response.ok) throw new Error('request failed');
    return response.json();
  }

  function openFiles() {
    closeMenu();
    if (!state.token) { toast('还没有连上房间'); return; }
    state.filesOpen = true;
    els.filesView.classList.remove('hidden');
    loadFiles();
  }

  function closeFiles() {
    state.filesOpen = false;
    els.filesView.classList.add('hidden');
    closeOpenSwipes(null);
  }

  async function loadFiles() {
    if (!state.filesOpen) return;
    if (!state.token) { toast('还没有连上房间'); return; }
    els.filesList.innerHTML = '<div class="files-loading">正在加载…</div>';
    try {
      const data = await apiFetch('/api/library');
      if (!state.filesOpen) return;
      state.files = Array.isArray(data.files) ? data.files : [];
      renderFiles();
    } catch (_) {
      if (!state.filesOpen) return;
      els.filesList.innerHTML = '<div class="files-error">文件列表加载失败，点右上角刷新重试。</div>';
    }
  }

  function renderFiles() {
    els.filesList.innerHTML = '';
    if (!state.files.length) {
      const empty = document.createElement('div');
      empty.className = 'files-empty';
      const strong = document.createElement('strong');
      strong.textContent = '还没有文件';
      const hint = document.createElement('span');
      hint.textContent = '在聊天里发送文件后，会在这里保留 7 天。';
      empty.append(strong, hint);
      els.filesList.appendChild(empty);
      return;
    }
    for (const record of state.files) els.filesList.appendChild(libraryFileRow(record));
  }

  function libraryFileRow(record) {
    const swipe = document.createElement('div');
    swipe.className = 'file-swipe';

    const actions = document.createElement('div');
    actions.className = 'file-actions';
    const extend = document.createElement('button');
    extend.type = 'button';
    extend.className = 'extend-btn';
    extend.textContent = '延期 7 天';
    extend.addEventListener('click', (event) => {
      event.stopPropagation();
      extendFile(record, extend);
    });
    actions.appendChild(extend);

    const main = document.createElement('div');
    main.className = 'file-main';
    const icon = document.createElement('div');
    icon.className = 'file-icon';
    icon.textContent = fileExt(record.name);

    const info = document.createElement('div');
    info.className = 'file-row-info';
    const name = document.createElement('div');
    name.className = 'file-row-name';
    name.textContent = record.name || '文件';
    const meta = document.createElement('div');
    meta.className = 'file-row-meta';
    meta.textContent = formatSize(record.size) + ' · ' + (record.uploaderName || '访客') + ' · ' + formatDate(record.uploadedAt);
    const expiry = document.createElement('div');
    expiry.className = 'file-row-expiry';
    expiry.textContent = expiryLabel(record.expiresAt);
    info.append(name, meta, expiry);

    const download = document.createElement('a');
    download.className = 'file-row-download';
    download.href = fileUrl(record);
    download.download = record.name || 'file';
    download.textContent = '下载';
    download.addEventListener('click', (event) => {
      if (swipe.dataset.swiped === '1') event.preventDefault();
    });

    const more = document.createElement('button');
    more.type = 'button';
    more.className = 'file-row-more';
    more.setAttribute('aria-label', '文件操作');
    more.textContent = '⋯';
    more.addEventListener('click', (event) => {
      event.stopPropagation();
      closeOpenSwipes(swipe);
      setSwipeOpen(swipe, !swipe.classList.contains('open'));
    });

    main.append(icon, info, download, more);
    swipe.append(actions, main);
    bindFileSwipe(swipe, main);
    return swipe;
  }

  const FILE_ACTION_WIDTH = 112;

  function closeOpenSwipes(except) {
    document.querySelectorAll('.file-swipe.open').forEach((swipe) => {
      if (swipe === except) return;
      setSwipeOpen(swipe, false);
    });
  }

  function setSwipeOpen(swipe, open) {
    swipe.classList.toggle('open', open);
    const main = swipe.querySelector('.file-main');
    if (main) {
      main.style.transition = '';
      main.style.transform = open ? 'translateX(' + FILE_ACTION_WIDTH + 'px)' : '';
      main.dataset.shift = open ? String(FILE_ACTION_WIDTH) : '0';
    }
  }

  function bindFileSwipe(swipe, main) {
    let startX = 0;
    let startY = 0;
    let base = 0;
    let tracking = false;
    let horizontal = false;

    main.addEventListener('touchstart', (event) => {
      if (event.touches.length !== 1) return;
      closeOpenSwipes(swipe);
      startX = event.touches[0].clientX;
      startY = event.touches[0].clientY;
      base = swipe.classList.contains('open') ? FILE_ACTION_WIDTH : 0;
      main.dataset.shift = String(base);
      tracking = true;
      horizontal = false;
      delete swipe.dataset.swiped;
    }, { passive: true });

    main.addEventListener('touchmove', (event) => {
      if (!tracking) return;
      const touch = event.touches[0];
      const dx = touch.clientX - startX;
      const dy = touch.clientY - startY;
      if (!horizontal) {
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
        if (Math.abs(dy) > Math.abs(dx)) {
          tracking = false;
          return;
        }
        horizontal = true;
        main.style.transition = 'none';
      }
      event.preventDefault();
      const shift = Math.max(0, Math.min(FILE_ACTION_WIDTH, base + dx));
      main.style.transform = 'translateX(' + shift + 'px)';
      main.dataset.shift = String(shift);
    }, { passive: false });

    const finish = () => {
      if (!tracking) return;
      const wasHorizontal = horizontal;
      tracking = false;
      horizontal = false;
      if (!wasHorizontal) return;
      main.style.transition = '';
      const shift = Number(main.dataset.shift || 0);
      const open = shift >= 48;
      setSwipeOpen(swipe, open);
      swipe.dataset.swiped = '1';
      window.setTimeout(() => { delete swipe.dataset.swiped; }, 350);
    };
    main.addEventListener('touchend', finish);
    main.addEventListener('touchcancel', finish);
    main.addEventListener('click', (event) => {
      if (swipe.dataset.swiped === '1') {
        event.preventDefault();
        event.stopPropagation();
      }
    });
  }

  async function extendFile(record, button) {
    button.disabled = true;
    button.textContent = '延期中…';
    try {
      await apiFetch('/api/library/' + encodeURIComponent(record.id) + '/extend', { method: 'POST' });
      toast('已延期 7 天');
      await loadFiles();
    } catch (_) {
      button.disabled = false;
      button.textContent = '延期 7 天';
      toast('延期失败');
    }
  }

  function openClearDialog() {
    closeMenu();
    if (!state.token) { toast('还没有连上房间'); return; }
    els.clearDialog.classList.remove('hidden');
  }

  function closeClearDialog() {
    if (state.clearBusy) return;
    els.clearDialog.classList.add('hidden');
  }

  async function clearChatHistory() {
    if (state.clearBusy) return;
    if (!state.token) { toast('还没有连上房间'); return; }
    state.clearBusy = true;
    els.clearConfirm.disabled = true;
    els.clearConfirm.textContent = '清除中…';
    try {
      await apiFetch('/api/room/clear', { method: 'POST' });
      closeClearDialog();
      closeMenu();
      resetMessages();
      renderAll();
      toast('聊天记录已清除，文件仍保留');
    } catch (_) {
      toast('清除失败');
    } finally {
      state.clearBusy = false;
      els.clearDialog.classList.add('hidden');
      els.clearConfirm.disabled = false;
      els.clearConfirm.textContent = '清除';
    }
  }

  function expiryLabel(expiresAt) {
    const remaining = Math.max(0, Number(expiresAt) - Date.now());
    const days = Math.ceil(remaining / 86400000);
    const date = new Date(Number(expiresAt)).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
    if (days <= 0) return '已到期';
    return '剩 ' + days + ' 天 · ' + date + ' 到期';
  }

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

  // File cards show the extension as a small monospace badge instead of an
  // emoji: it is language-neutral, aligns to the baseline grid and stays
  // legible in both light and dark paper themes.
  const EXT_LABELS = {
    jpg: 'JPG', jpeg: 'JPG', png: 'PNG', gif: 'GIF', webp: 'WEBP',
    avif: 'AVIF', heic: 'HEIC', heif: 'HEIF',
    mp4: 'MP4', mov: 'MOV', mkv: 'MKV', webm: 'WEBM',
    mp3: 'MP3', wav: 'WAV', m4a: 'M4A', flac: 'FLAC',
    pdf: 'PDF', zip: 'ZIP', rar: 'RAR', '7z': '7Z', tar: 'TAR', gz: 'GZ',
    doc: 'DOC', docx: 'DOCX', txt: 'TXT', md: 'MD',
  };

  function fileExt(name) {
    const raw = String(name || '');
    const dot = raw.lastIndexOf('.');
    if (dot <= 0 || dot === raw.length - 1) return 'FILE';
    const ext = raw.slice(dot + 1).toLowerCase();
    return EXT_LABELS[ext] || ext.toUpperCase().slice(0, 4);
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
