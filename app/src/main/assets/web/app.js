(function() {
  'use strict';

  // State
  let currentFolderId = null;
  let currentFolderPath = '';
  let breadcrumbTrail = [];
  let allItems = [];
  let displayedItems = [];
  let isGridView = true;
  let activeTheme = localStorage.getItem('localshare_theme') || 'dark';
  let currentPreviewItem = null;

  // Real-time SSE State
  let eventSource = null;
  let sseReconnectDelay = 1000;
  let sseReconnectTimer = null;

  // DOM Elements
  const itemsContainer = document.getElementById('itemsContainer');
  const loadingState = document.getElementById('loadingState');
  const emptyState = document.getElementById('emptyState');
  const errorState = document.getElementById('errorState');
  const errorMessage = document.getElementById('errorMessage');
  const btnRetry = document.getElementById('btnRetry');
  const searchInput = document.getElementById('searchInput');
  const btnClearSearch = document.getElementById('btnClearSearch');
  const sortSelect = document.getElementById('sortSelect');
  const btnViewGrid = document.getElementById('btnViewGrid');
  const btnViewList = document.getElementById('btnViewList');
  const contentArea = document.getElementById('contentArea');
  const breadcrumbsContainer = document.getElementById('breadcrumbsContainer');
  const itemsSummary = document.getElementById('itemsSummary');
  const btnZipAll = document.getElementById('btnZipAll');
  const btnThemeToggle = document.getElementById('btnThemeToggle');
  const authModal = document.getElementById('authModal');
  const authForm = document.getElementById('authForm');
  const passwordInput = document.getElementById('passwordInput');
  const authError = document.getElementById('authError');
  const previewModal = document.getElementById('previewModal');
  const previewTitle = document.getElementById('previewTitle');
  const previewMeta = document.getElementById('previewMeta');
  const previewIcon = document.getElementById('previewIcon');
  const previewBody = document.getElementById('previewBody');
  const btnDownloadPreview = document.getElementById('btnDownloadPreview');
  const btnClosePreview = document.getElementById('btnClosePreview');

  // SVG Icons
  const ICONS = {
    folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>',
    image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>',
    video: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>',
    audio: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>',
    pdf: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="9" y1="13" x2="15" y2="13"></line><line x1="9" y1="17" x2="13" y2="17"></line></svg>',
    code: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"></polyline><polyline points="8 6 2 12 8 18"></polyline></svg>',
    zip: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-3"></path><line x1="10" y1="3" x2="10" y2="21"></line></svg>',
    file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path><polyline points="13 2 13 9 20 9"></polyline></svg>'
  };

  // Initialize
  function init() {
    applyTheme(activeTheme);
    setupEventListeners();
    checkAuthAndLoad();
  }

  // Theme Handling
  function applyTheme(theme) {
    activeTheme = theme;
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('localshare_theme', theme);
    const themeIcon = document.getElementById('themeIcon');
    if (themeIcon) {
      if (theme === 'light') {
        // Moon icon when in light mode (to indicate clicking will switch to dark)
        themeIcon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>';
        if (btnThemeToggle) {
          btnThemeToggle.setAttribute('title', 'Switch to dark theme');
          btnThemeToggle.setAttribute('aria-label', 'Switch to dark theme');
        }
      } else {
        // Sun icon when in dark mode (to indicate clicking will switch to light)
        themeIcon.innerHTML = '<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>';
        if (btnThemeToggle) {
          btnThemeToggle.setAttribute('title', 'Switch to light theme');
          btnThemeToggle.setAttribute('aria-label', 'Switch to light theme');
        }
      }
    }
  }

  function toggleTheme() {
    applyTheme(activeTheme === 'dark' ? 'light' : 'dark');
  }

  // Event Listeners
  function setupEventListeners() {
    btnThemeToggle.addEventListener('click', toggleTheme);

    btnViewGrid.addEventListener('click', () => setViewMode(true));
    btnViewList.addEventListener('click', () => setViewMode(false));

    searchInput.addEventListener('input', () => {
      const query = searchInput.value.trim();
      btnClearSearch.classList.toggle('hidden', query.length === 0);
      filterAndSortItems();
    });

    btnClearSearch.addEventListener('click', () => {
      searchInput.value = '';
      btnClearSearch.classList.add('hidden');
      filterAndSortItems();
    });

    sortSelect.addEventListener('change', () => filterAndSortItems());

    btnRetry.addEventListener('click', () => loadItems(currentFolderId, currentFolderPath));

    btnZipAll.addEventListener('click', downloadAllZip);

    authForm.addEventListener('submit', handleAuthSubmit);

    btnClosePreview.addEventListener('click', closePreview);
    previewModal.addEventListener('click', (e) => {
      if (e.target === previewModal) closePreview();
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closePreview();
    });
  }

  function setViewMode(grid) {
    isGridView = grid;
    btnViewGrid.classList.toggle('active', grid);
    btnViewList.classList.toggle('active', !grid);
    contentArea.classList.toggle('grid-view', grid);
    contentArea.classList.toggle('list-view', !grid);
  }

  // Build API URL preserving query tokens and session authentication
  function buildApiUrl(base, paramsObj) {
    const p = new URLSearchParams();
    if (paramsObj) {
      for (const [k, v] of Object.entries(paramsObj)) {
        if (v !== undefined && v !== null && v !== '') p.set(k, v);
      }
    }
    const token = sessionStorage.getItem('localshare_token');
    const auth = sessionStorage.getItem('localshare_auth');
    if (token && !p.has('token')) p.set('token', token);
    if (auth && !p.has('auth')) p.set('auth', auth);
    const queryStr = p.toString();
    return queryStr ? `${base}?${queryStr}` : base;
  }

  // Auth & Status Check
  async function checkAuthAndLoad() {
    showLoading();
    try {
      const urlToken = new URLSearchParams(window.location.search).get('token');
      if (urlToken) {
        sessionStorage.setItem('localshare_token', urlToken);
      }

      const statusUrl = buildApiUrl('/api/status', {});
      const res = await fetch(statusUrl);
      if (!res.ok) throw new Error('Server responded with HTTP ' + res.status);
      const data = await res.json();

      if ((data.passwordRequired || data.globalAuthRequired) && !data.authenticated) {
        hideLoading();
        authModal.classList.remove('hidden');
        if (data.globalAuthRequired) {
          const authTitle = authModal.querySelector('h2');
          if (authTitle) authTitle.textContent = 'Enter Access PIN or Password';
          const authSub = authModal.querySelector('p');
          if (authSub) authSub.textContent = 'Enter the access PIN provided by the LocalShare host to view files.';
        }
        passwordInput.focus();
        return;
      }

      authModal.classList.add('hidden');
      const connectionBadge = document.getElementById('connectionBadge');
      if (connectionBadge) {
        connectionBadge.textContent = data.sharingMode === 'GLOBAL' ? 'Global Link Active' : 'Local Wi-Fi Active';
      }
      loadRootItems();
      initEventSource();
    } catch (err) {
      showError(err.message || 'Cannot reach LocalShare server');
    }
  }

  async function handleAuthSubmit(e) {
    e.preventDefault();
    authError.classList.add('hidden');
    const pwd = passwordInput.value;
    try {
      const res = await fetch('/api/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pwd })
      });
      const data = await res.json();
      if (data.success) {
        if (data.token) {
          sessionStorage.setItem('localshare_auth', data.token);
        }
        authModal.classList.add('hidden');
        loadRootItems();
        initEventSource();
      } else {
        authError.textContent = data.message || 'Incorrect password';
        authError.classList.remove('hidden');
      }
    } catch (err) {
      authError.textContent = 'Network authentication failed';
      authError.classList.remove('hidden');
    }
  }

  // Load Items
  async function loadRootItems() {
    currentFolderId = null;
    currentFolderPath = '';
    breadcrumbTrail = [{ name: 'Root Shared Files', id: null, path: '' }];
    renderBreadcrumbs();
    loadItems(null, '');
  }

  async function loadItems(folderId, relativePath, isReconcile = false) {
    if (!isReconcile) showLoading();
    try {
      const url = folderId
        ? buildApiUrl('/api/folder', { id: folderId, path: relativePath || '' })
        : buildApiUrl('/api/items', {});

      const res = await fetch(url);
      if (res.status === 401) {
        authModal.classList.remove('hidden');
        hideLoading();
        return;
      }
      if (!res.ok) throw new Error('HTTP ' + res.status);

      const data = await res.json();
      if (folderId && data.items) {
        allItems = data.items;
      } else if (Array.isArray(data)) {
        allItems = data;
      } else {
        allItems = [];
      }

      hideLoading();
      filterAndSortItems();
    } catch (err) {
      if (!isReconcile) showError(err.message || 'Failed to fetch items');
    }
  }

  function compareItems(a, b, sortVal) {
    // Folders always at top
    if (a.isDirectory && !b.isDirectory) return -1;
    if (!a.isDirectory && b.isDirectory) return 1;

    switch (sortVal) {
      case 'name_asc': return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
      case 'name_desc': return b.name.localeCompare(a.name, undefined, { sensitivity: 'base' });
      case 'size_desc': return (b.size || 0) - (a.size || 0);
      case 'size_asc': return (a.size || 0) - (b.size || 0);
      case 'date_desc': return (b.lastModified || 0) - (a.lastModified || 0);
      case 'date_asc': return (a.lastModified || 0) - (b.lastModified || 0);
      default: return 0;
    }
  }

  function filterAndSortItems() {
    const query = searchInput.value.toLowerCase().trim();
    let items = allItems.slice();

    if (query) {
      items = items.filter(it => it.name.toLowerCase().includes(query));
    }

    const sortVal = sortSelect.value;
    items.sort((a, b) => compareItems(a, b, sortVal));

    displayedItems = items;
    renderItems();
  }

  function renderItems() {
    itemsContainer.innerHTML = '';

    if (displayedItems.length === 0) {
      emptyState.classList.remove('hidden');
      updateSummary(0, 0, 0);
      return;
    }
    emptyState.classList.add('hidden');

    let totalBytes = 0;
    let fileCount = 0;
    let folderCount = 0;

    displayedItems.forEach(item => {
      if (item.isDirectory) folderCount++;
      else {
        fileCount++;
        totalBytes += item.size || 0;
      }

      const card = createItemElement(item);
      itemsContainer.appendChild(card);
    });

    updateSummary(fileCount, folderCount, totalBytes);
  }

  function createItemElement(item) {
    const el = document.createElement('div');
    el.className = 'item-card file-list-item';
    el.setAttribute('data-id', item.id);

    const type = getItemCategory(item);
    const sizeStr = item.isDirectory ? (item.itemCount !== undefined ? `${item.itemCount} items` : 'Folder') : formatBytes(item.size);

    let visualHtml = '';
    if (type === 'image') {
      const thumbUrl = getThumbnailUrl(item);
      visualHtml = `<img class="item-thumb" src="${thumbUrl}" alt="${escapeHtml(item.name)}" loading="lazy" onerror="this.style.display='none'; this.nextElementSibling.style.display='block';">
                    <div class="item-type-icon" style="display:none;">${ICONS.image}</div>`;
    } else {
      visualHtml = `<div class="item-type-icon">${ICONS[type] || ICONS.file}</div>`;
    }

    const dateStr = item.lastModified ? formatDate(item.lastModified) : '';

    el.innerHTML = `
      <div class="file-info-wrapper">
        <div class="item-visual">${visualHtml}</div>
        <div class="file-details item-info">
          <div class="file-name item-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</div>
          <div class="file-meta item-details">
            <span class="file-size">${sizeStr}</span>
            ${dateStr ? `<span class="file-meta-divider file-date">${dateStr}</span>` : ''}
          </div>
        </div>
      </div>
      <div class="item-actions">
        ${item.isDirectory ? `
          <button class="btn btn-secondary btn-block btn-open-folder">Open</button>
        ` : `
          <button class="btn btn-secondary btn-preview">Preview</button>
          <a class="btn btn-primary btn-download" href="${getDownloadUrl(item)}" download="${escapeHtml(item.name)}">Download</a>
        `}
      </div>
    `;

    // Click behavior
    if (item.isDirectory) {
      el.addEventListener('click', (e) => {
        if (!e.target.closest('button')) {
          navigateToFolder(item);
        }
      });
      el.querySelector('.btn-open-folder').addEventListener('click', (e) => {
        e.stopPropagation();
        navigateToFolder(item);
      });
    } else {
      el.addEventListener('click', (e) => {
        if (!e.target.closest('a') && !e.target.closest('button')) {
          openPreview(item);
        }
      });
      el.querySelector('.btn-preview').addEventListener('click', (e) => {
        e.stopPropagation();
        openPreview(item);
      });
    }

    return el;
  }

  function navigateToFolder(folderItem) {
    const targetFolderId = folderItem.folderId || currentFolderId || folderItem.id;
    const newPath = folderItem.relativePath || (currentFolderPath ? `${currentFolderPath}/${folderItem.name}` : folderItem.name);
    breadcrumbTrail.push({
      name: folderItem.name,
      id: targetFolderId,
      path: newPath
    });
    currentFolderId = targetFolderId;
    currentFolderPath = newPath;
    renderBreadcrumbs();
    loadItems(currentFolderId, currentFolderPath);
  }

  function renderBreadcrumbs() {
    breadcrumbsContainer.innerHTML = '';
    breadcrumbTrail.forEach((bc, idx) => {
      const isLast = idx === breadcrumbTrail.length - 1;
      const span = document.createElement('span');
      span.className = `breadcrumb-item ${isLast ? 'active' : ''}`;
      span.textContent = bc.name;

      if (!isLast) {
        span.addEventListener('click', () => {
          breadcrumbTrail = breadcrumbTrail.slice(0, idx + 1);
          currentFolderId = bc.id;
          currentFolderPath = bc.path;
          renderBreadcrumbs();
          loadItems(currentFolderId, currentFolderPath);
        });
      }

      breadcrumbsContainer.appendChild(span);

      if (!isLast) {
        const sep = document.createElement('span');
        sep.className = 'breadcrumb-separator';
        sep.textContent = '/';
        breadcrumbsContainer.appendChild(sep);
      }
    });
  }

  function updateSummary(files, folders, bytes) {
    let text = [];
    if (folders > 0) text.push(`${folders} folder${folders > 1 ? 's' : ''}`);
    if (files > 0) text.push(`${files} file${files > 1 ? 's' : ''} (${formatBytes(bytes)})`);
    if (text.length === 0) text.push('0 items');
    itemsSummary.textContent = text.join(' • ');
  }

  // Previews
  function openPreview(item) {
    currentPreviewItem = item;
    const type = getItemCategory(item);
    previewTitle.textContent = item.name;
    previewTitle.title = item.name;
    previewMeta.textContent = `${formatBytes(item.size)} • ${item.mimeType || 'unknown'}`;
    previewIcon.innerHTML = ICONS[type] || ICONS.file;

    const downloadUrl = getDownloadUrl(item);
    btnDownloadPreview.href = downloadUrl;
    btnDownloadPreview.setAttribute('download', item.name);

    previewBody.innerHTML = '<div class="spinner"></div>';
    previewModal.classList.remove('hidden');

    const previewUrl = getPreviewUrl(item);

    if (type === 'image') {
      const img = document.createElement('img');
      img.className = 'preview-img';
      img.src = previewUrl;
      img.onload = () => { previewBody.innerHTML = ''; previewBody.appendChild(img); };
      img.onerror = () => showPreviewFallback(item, downloadUrl);
    } else if (type === 'video') {
      previewBody.innerHTML = `
        <video class="preview-video" controls autoplay>
          <source src="${previewUrl}" type="${item.mimeType}">
          Your browser does not support the video tag.
        </video>
      `;
    } else if (type === 'audio') {
      previewBody.innerHTML = `
        <div style="width:100%; text-align:center;">
          <div style="font-size:3rem; margin-bottom:1rem; color:var(--accent-cyan);">${ICONS.audio}</div>
          <audio class="preview-audio" controls autoplay>
            <source src="${previewUrl}" type="${item.mimeType}">
            Your browser does not support the audio tag.
          </audio>
        </div>
      `;
    } else if (type === 'pdf') {
      previewBody.innerHTML = `<iframe class="preview-iframe" src="${previewUrl}#toolbar=1"></iframe>`;
    } else if (type === 'code' || item.size < 500000 && isTextMime(item.mimeType)) {
      fetch(previewUrl)
        .then(r => r.text())
        .then(text => {
          previewBody.innerHTML = `<pre class="preview-text"><code>${escapeHtml(text)}</code></pre>`;
        })
        .catch(() => showPreviewFallback(item, downloadUrl));
    } else {
      showPreviewFallback(item, downloadUrl);
    }
  }

  function showPreviewFallback(item, downloadUrl) {
    previewBody.innerHTML = `
      <div class="state-message">
        <div style="width:64px;height:64px;margin:0 auto 1rem;color:var(--accent-cyan);">${ICONS.file}</div>
        <h3>No direct preview available</h3>
        <p>This file type (${escapeHtml(item.mimeType || 'unknown')}) can be downloaded directly.</p>
        <a class="btn btn-primary" href="${downloadUrl}" download="${escapeHtml(item.name)}">Download File</a>
      </div>
    `;
  }

  function closePreview() {
    currentPreviewItem = null;
    previewModal.classList.add('hidden');
    previewBody.innerHTML = '';
  }

  // Download ZIP
  function downloadAllZip() {
    if (displayedItems.length === 0) return;
    let url;
    if (currentFolderId) {
      url = buildApiUrl('/api/zip', { id: currentFolderId, path: currentFolderPath || '' });
    } else {
      const ids = displayedItems.map(it => it.id).join(',');
      url = buildApiUrl('/api/zip', { ids: ids });
    }
    window.location.href = url;
  }

  // Helpers
  function getDownloadUrl(item) {
    const folderId = item.folderId || currentFolderId || item.id;
    const path = item.relativePath || (currentFolderPath ? `${currentFolderPath}/${item.name}` : '');
    return buildApiUrl('/api/download', { id: folderId, path: path });
  }

  function getPreviewUrl(item) {
    const folderId = item.folderId || currentFolderId || item.id;
    const path = item.relativePath || (currentFolderPath ? `${currentFolderPath}/${item.name}` : '');
    return buildApiUrl('/api/preview', { id: folderId, path: path });
  }

  function getThumbnailUrl(item) {
    const folderId = item.folderId || currentFolderId || item.id;
    const path = item.relativePath || (currentFolderPath ? `${currentFolderPath}/${item.name}` : '');
    return buildApiUrl('/api/thumbnail', { id: folderId, path: path });
  }

  function getItemCategory(item) {
    if (item.isDirectory) return 'folder';
    const mime = (item.mimeType || '').toLowerCase();
    const ext = (item.name.split('.').pop() || '').toLowerCase();

    if (mime.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return 'image';
    if (mime.startsWith('video/') || ['mp4', 'mkv', 'webm', 'mov', 'avi'].includes(ext)) return 'video';
    if (mime.startsWith('audio/') || ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'].includes(ext)) return 'audio';
    if (mime === 'application/pdf' || ext === 'pdf') return 'pdf';
    if (['zip', 'rar', 'tar', 'gz', '7z', 'bz2'].includes(ext)) return 'zip';
    if (['json', 'js', 'ts', 'html', 'css', 'xml', 'kt', 'java', 'py', 'md', 'txt', 'c', 'cpp'].includes(ext)) return 'code';
    return 'file';
  }

  function isTextMime(mime) {
    if (!mime) return false;
    return mime.startsWith('text/') || mime.includes('json') || mime.includes('xml') || mime.includes('javascript');
  }

  function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function formatDate(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function showLoading() {
    loadingState.classList.remove('hidden');
    emptyState.classList.add('hidden');
    errorState.classList.add('hidden');
    itemsContainer.innerHTML = '';
  }

  function hideLoading() {
    loadingState.classList.add('hidden');
  }

  function showError(msg) {
    hideLoading();
    emptyState.classList.add('hidden');
    errorMessage.textContent = msg;
    errorState.classList.remove('hidden');
  }

  // Real-Time Status & Notifications
  function setLiveStatus(status, text) {
    const badge = document.getElementById('liveStatusBadge');
    const label = document.getElementById('liveStatusText');
    if (badge && label) {
      badge.className = `badge status-${status}`;
      label.textContent = text;
    }
  }

  function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;

    let iconSvg = ICONS.file;
    if (type === 'warning') {
      iconSvg = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#f59e0b" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>';
    } else if (type === 'success') {
      iconSvg = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#10b981" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>';
    } else {
      iconSvg = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="8"></line></svg>';
    }

    toast.innerHTML = `
      <span class="toast-icon">${iconSvg}</span>
      <span class="toast-message">${escapeHtml(message)}</span>
    `;
    container.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(12px)';
      setTimeout(() => {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
      }, 300);
    }, 3500);
  }

  // Real-Time SSE Connection Management
  let lastEventSignature = '';
  let lastEventTime = 0;
  let lastReceivedVersion = 0;

  function getEventSourceUrl() {
    return buildApiUrl('/events', {});
  }

  function initEventSource() {
    if (eventSource) {
      try { eventSource.close(); } catch (_) {}
      eventSource = null;
    }

    const sseUrl = getEventSourceUrl();
    console.log('[LocalShare-SSE] Opening EventSource stream to:', sseUrl);
    setLiveStatus('reconnecting', 'Connecting...');

    try {
      eventSource = new EventSource(sseUrl);

      eventSource.onopen = function() {
        console.log('[LocalShare-SSE] Connection established successfully to:', sseUrl);
        sseReconnectDelay = 1000;
        setLiveStatus('live', 'Live / Connected');
        reconcileWithServer();
      };

      function onEventReceived(e, eventName) {
        try {
          const data = JSON.parse(e.data);
          const eventId = e.lastEventId || e.id || data.version;
          console.log(`[LocalShare-SSE] Event [${eventName}] (ID: ${eventId}):`, data);
          processFileUpdate(data, eventName);
        } catch (err) {
          console.error('[LocalShare-SSE] Parse error on incoming SSE data:', e.data, err);
        }
      }

      eventSource.onmessage = function(e) {
        onEventReceived(e, 'message');
      };

      eventSource.addEventListener('FILE_LIST_UPDATED', function(e) {
        onEventReceived(e, 'FILE_LIST_UPDATED');
      });

      eventSource.addEventListener('file_added', function(e) {
        onEventReceived(e, 'file_added');
      });

      eventSource.addEventListener('file_removed', function(e) {
        onEventReceived(e, 'file_removed');
      });

      eventSource.addEventListener('folder_updated', function(e) {
        onEventReceived(e, 'folder_updated');
      });

      eventSource.addEventListener('sharing_stopped', function(e) {
        onEventReceived(e, 'sharing_stopped');
      });

      eventSource.onerror = function(err) {
        console.warn('[LocalShare-SSE] Connection error/closed on:', sseUrl, err);
        if (eventSource) {
          try { eventSource.close(); } catch (_) {}
          eventSource = null;
        }
        const waitSec = Math.max(1, Math.round(sseReconnectDelay / 1000));
        setLiveStatus('reconnecting', `Reconnecting (${waitSec}s)...`);

        if (sseReconnectTimer) clearTimeout(sseReconnectTimer);
        sseReconnectTimer = setTimeout(() => {
          initEventSource();
        }, sseReconnectDelay);

        sseReconnectDelay = Math.min(Math.round(sseReconnectDelay * 1.5), 15000);
      };
    } catch (err) {
      console.error('[LocalShare-SSE] Failed to construct EventSource:', err);
      setLiveStatus('offline', 'Offline');
    }
  }

  function processFileUpdate(data, eventName) {
    if (!data) return;
    const sig = `${data.version || ''}:${data.action || eventName}:${data.path || data.id || ''}:${data.totalFiles}:${data.totalBytes}`;
    const now = Date.now();
    if (sig === lastEventSignature && (now - lastEventTime) < 300) {
      return;
    }
    lastEventSignature = sig;
    lastEventTime = now;

    if (data.version && data.version > 0 && data.action !== 'init') {
      if (data.version < lastReceivedVersion) {
        console.warn('[LocalShare-SSE] Skipping stale event version:', data.version, 'current version:', lastReceivedVersion);
        return;
      }
      lastReceivedVersion = data.version;
    }

    handleFileUpdate(data);
  }

  async function reconcileWithServer() {
    try {
      const url = currentFolderId
        ? buildApiUrl('/api/folder', { id: currentFolderId, path: currentFolderPath || '' })
        : buildApiUrl('/api/items', {});
      const res = await fetch(url);
      if (!res.ok) return;
      const data = await res.json();
      const serverItems = Array.isArray(data) ? data : (data.items || []);
      allItems = serverItems;
      filterAndSortItems();
    } catch (err) {
      console.warn('[LocalShare-SSE] Reconcile error:', err);
    }
  }

  function handleFileUpdate(data) {
    if (!data) return;

    // 1. Action: REMOVE
    if (data.action === 'remove' || data.event === 'file_removed') {
      const removedId = data.path || data.id;
      console.log('[LocalShare-SSE] Processing REMOVE event for id:', removedId);

      // Active preview handling: close modal immediately and show toast notification
      if (currentPreviewItem && (currentPreviewItem.id === removedId || currentPreviewItem.name === removedId)) {
        closePreview();
        showToast('This file was removed by the host', 'warning');
      }

      // If user currently browsing inside that folder
      if (currentFolderId === removedId) {
        loadRootItems();
        showToast('This folder was removed by the host', 'warning');
        return;
      }

      // Remove from internal collections
      allItems = allItems.filter(it => it.id !== removedId && it.name !== removedId);
      displayedItems = displayedItems.filter(it => it.id !== removedId && it.name !== removedId);

      // Cleanly animate and remove the specific file card/row element from DOM instantly
      let removedFromDOM = false;
      const cards = Array.from(itemsContainer.querySelectorAll('.item-card'));
      for (const targetEl of cards) {
        if (targetEl.getAttribute('data-id') === removedId) {
          removedFromDOM = true;
          targetEl.classList.add('item-removing');
          setTimeout(() => {
            if (targetEl.parentNode) {
              targetEl.parentNode.removeChild(targetEl);
            }
            if (itemsContainer.children.length === 0) {
              emptyState.classList.remove('hidden');
            }
          }, 320);
        }
      }
      console.log(`[LocalShare-SSE] DOM removal status for ${removedId}: ${removedFromDOM}`);

      // Recalculate and update summary metrics dynamically
      if (data.totalFiles !== undefined) {
        updateSummary(data.totalFiles, data.totalFolders, data.totalBytes);
      } else {
        recalculateMetrics();
      }

      if (allItems.length === 0) {
        emptyState.classList.remove('hidden');
      }
      return;
    }

    // 2. Action: ADD
    if (data.action === 'add' || data.event === 'file_added') {
      const newItem = data.item;
      if (!newItem) return;
      console.log('[LocalShare-SSE] Processing ADD event for item:', newItem.name);

      const existsIdx = allItems.findIndex(it => it.id === newItem.id);
      if (existsIdx >= 0) {
        allItems[existsIdx] = newItem;
      } else {
        allItems.push(newItem);
      }

      // Append/insert into DOM grid/list dynamically without resetting scroll position or search filter
      if (currentFolderId === null) {
        const query = searchInput.value.toLowerCase().trim();
        const matchesSearch = !query || newItem.name.toLowerCase().includes(query);

        if (matchesSearch) {
          const existingEl = Array.from(itemsContainer.querySelectorAll('.item-card'))
            .find(el => el.getAttribute('data-id') === newItem.id);
          if (!existingEl) {
            const sortVal = sortSelect.value;
            let insertIdx = displayedItems.length;

            for (let i = 0; i < displayedItems.length; i++) {
              if (compareItems(newItem, displayedItems[i], sortVal) < 0) {
                insertIdx = i;
                break;
              }
            }

            const newCard = createItemElement(newItem);
            newCard.classList.add('item-entering');

            if (insertIdx < displayedItems.length && itemsContainer.children[insertIdx]) {
              itemsContainer.insertBefore(newCard, itemsContainer.children[insertIdx]);
              displayedItems.splice(insertIdx, 0, newItem);
            } else {
              itemsContainer.appendChild(newCard);
              displayedItems.push(newItem);
            }

            emptyState.classList.add('hidden');
            console.log('[LocalShare-SSE] Added card directly to DOM at index', insertIdx);
          }
        }
      }

      // Update total file count and total size
      if (data.totalFiles !== undefined) {
        updateSummary(data.totalFiles, data.totalFolders, data.totalBytes);
      } else {
        recalculateMetrics();
      }
      return;
    }

    // 3. Action: CLEAR
    if (data.action === 'clear' || data.event === 'sharing_stopped') {
      console.log('[LocalShare-SSE] Processing CLEAR / sharing_stopped event');
      if (currentPreviewItem) {
        closePreview();
        showToast('File sharing was stopped by the host', 'warning');
      }
      allItems = [];
      displayedItems = [];
      itemsContainer.innerHTML = '';
      emptyState.classList.remove('hidden');
      if (data.totalFiles !== undefined) {
        updateSummary(data.totalFiles, data.totalFolders, data.totalBytes);
      } else {
        updateSummary(0, 0, 0);
      }
      return;
    }

    // 4. Action: REFRESH / INIT
    if (data.action === 'refresh' || data.action === 'init') {
      console.log('[LocalShare-SSE] Processing REFRESH / INIT event, action:', data.action);
      if (data.items) {
        allItems = data.items;
        if (currentPreviewItem && !allItems.some(it => it.id === currentPreviewItem.id)) {
          closePreview();
          showToast('This file was removed by the host', 'warning');
        }
        if (currentFolderId === null) {
          if (allItems.length === 0) {
            displayedItems = [];
            itemsContainer.innerHTML = '';
            emptyState.classList.remove('hidden');
          } else {
            filterAndSortItems();
          }
        } else {
          if (!data.items.some(it => it.id === currentFolderId || (it.isDirectory && it.name === (breadcrumbTrail[1] ? breadcrumbTrail[1].name : '')))) {
            loadRootItems();
            showToast('The folder you were viewing is no longer shared', 'warning');
          } else {
            loadItems(currentFolderId, currentFolderPath, true);
          }
        }
      }
      if (data.totalFiles !== undefined) {
        updateSummary(data.totalFiles, data.totalFolders, data.totalBytes);
      } else {
        recalculateMetrics();
      }
      return;
    }

    // 5. Action: FOLDER_UPDATED
    if (data.action === 'folder_updated' || data.event === 'folder_updated') {
      if (currentFolderId && (currentFolderId === data.folderId || currentFolderId.startsWith(data.folderId || ''))) {
        loadItems(currentFolderId, currentFolderPath, true);
      }
      return;
    }
  }

  function recalculateMetrics() {
    let totalBytes = 0;
    let fileCount = 0;
    let folderCount = 0;
    allItems.forEach(item => {
      if (item.isDirectory) folderCount++;
      else {
        fileCount++;
        totalBytes += item.size || 0;
      }
    });
    updateSummary(fileCount, folderCount, totalBytes);
  }

  // Start
  document.addEventListener('DOMContentLoaded', init);
})();
