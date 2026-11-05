const SORT_HOT = 0;
const SORT_NEW = 1;
const SORT_TOP = 2;
const SORT_RND = 3;

const CATS = [
  ["POPULAR", "popular"],
  ["ALL", "all"],
  ["AI", "artificial"],
  ["CANADA", "canada"],
  ["NEWS", "news"],
  ["WORLD NEWS", "worldnews"],
  ["TECH", "technology"],
  ["PROG", "programming"],
  ["SCIENCE", "science"],
  ["SPACE", "space"],
  ["GADGETS", "gadgets"],
  ["DIY", "DIY"],
  ["ARDUINO", "arduino"],
  ["PI", "raspberry_pi"],
  ["3D PRINT", "3Dprinting"],
  ["ELECTRONICS", "electronics"],
  ["CYBER", "cybersecurity"],
  ["SELF-HOSTED", "selfhosted"],
  ["FUTUROLOGY", "Futurology"],
  ["GAMING", "games"],
];

const state = {
  posts: [],
  postIds: new Set(),
  searchResults: [],
  searchIds: new Set(),
  visible: [],
  pageRefs: [],
  currentSub: localStorage.getItem('redditscroll-sub') || 'popular',
  sortMode: parseInt(localStorage.getItem('redditscroll-sort') || SORT_HOT, 10),
  searchQuery: '',
  loading: false,
  renderedOnce: false,
  shuffled: null,
  shuffledBase: null,
  pendingStartPage: -1,
  currentPage: 0,
  firstPageEvent: true,
  hintShown: localStorage.getItem('redditscroll-hint') === '1'
};

const els = {
  pager: document.getElementById('pager'),
  topbar: document.getElementById('topbar'),
  catRow: document.getElementById('cat-row'),
  sortHot: document.getElementById('sort-hot'),
  sortNew: document.getElementById('sort-new'),
  sortTop: document.getElementById('sort-top'),
  sortRnd: document.getElementById('sort-rnd'),
  refresh: document.getElementById('refresh'),
  searchToggle: document.getElementById('search-toggle'),
  searchExpanded: document.getElementById('search-expanded'),
  searchInput: document.getElementById('search-input'),
  searchGo: document.getElementById('search-go'),
  searchClear: document.getElementById('search-clear'),
  matchInfo: document.getElementById('match-info'),
  scrollUp: document.getElementById('scroll-up'),
  scrollDown: document.getElementById('scroll-down'),
  offline: document.getElementById('offline'),
  loading: document.getElementById('loading'),
  error: document.getElementById('error'),
  errorBtn: document.getElementById('error-btn'),
  hint: document.getElementById('hint')
};

function stripTags(html) {
  return (html || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
}

function decodeEntities(str) {
  if (!str) return '';
  const txt = document.createElement('textarea');
  txt.innerHTML = str;
  return txt.value;
}

function parseIso(s) {
  if (!s) return 0;
  s = s.trim();
  const z = s.indexOf('Z');
  const cut = s.lastIndexOf('+', 19);
  if (z > 0) s = s.substring(0, z);
  else if (cut > 0) s = s.substring(0, cut);
  const t = Date.parse(s);
  return isNaN(t) ? 0 : t;
}

function relTime(ms) {
  if (!ms) return '';
  const diff = Date.now() - ms;
  if (diff < 60000) return 'just now';
  const m = Math.floor(diff / 60000);
  if (m < 60) return m + 'm ago';
  const h = Math.floor(m / 60);
  if (h < 24) return h + 'h ago';
  const d = Math.floor(h / 24);
  if (d < 7) return d + 'd ago';
  return new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function makeExcerpt(html) {
  const sc = (html || '').indexOf('<!-- SC_OFF -->');
  const body = sc >= 0 ? html.substring(sc) : html;
  let text = decodeEntities(stripTags(body)).trim();
  if (text.length > 240) {
    let sub = text.slice(0, 240);
    const last = sub.lastIndexOf(' ');
    if (last > 120) sub = sub.slice(0, last);
    return sub + '…';
  }
  return text;
}

function firstImage(html) {
  if (!html) return '';
  const m = html.match(/<img[^>]+?src=["']([^"']+)["']/i);
  return m ? m[1].trim() : '';
}

function parseAtom(xmlText) {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xmlText, 'application/xml');
  const entries = doc.querySelectorAll('entry');
  const out = [];
  entries.forEach(entry => {
    const title = entry.querySelector('title')?.textContent || '';
    const linkEl = entry.querySelector('link');
    const link = linkEl?.getAttribute('href')?.trim() || '';
    const id = entry.querySelector('id')?.textContent?.trim() || link;
    if (!title || !link) return;

    const published = entry.querySelector('published')?.textContent?.trim() || '';
    const content = entry.querySelector('content')?.textContent || '';
    const sub = entry.querySelector('category')?.getAttribute('term') || '';

    let author = '';
    const authorName = entry.querySelector('author > name');
    if (authorName) author = authorName.textContent.trim();
    if (author.startsWith('/u/')) author = author.substring(3);
    else if (author.startsWith('u/')) author = author.substring(2);

    const thumb = entry.getElementsByTagNameNS('http://search.yahoo.com/mrss/', 'thumbnail')[0]?.getAttribute('url') || '';
    const imageUrl = thumb || firstImage(content);

    out.push({
      title: decodeEntities(stripTags(title)).trim(),
      link,
      id,
      subreddit: sub,
      author,
      imageUrl,
      excerpt: makeExcerpt(content),
      dateMillis: parseIso(published),
      dateText: relTime(parseIso(published))
    });
  });
  return out;
}

function sortKey() {
  switch (state.sortMode) {
    case SORT_NEW: return 'new';
    case SORT_TOP: return 'top';
    default: return 'hot';
  }
}

function searchSortKey() {
  switch (state.sortMode) {
    case SORT_NEW: return 'new';
    case SORT_TOP: return 'top';
    default: return 'relevance';
  }
}

function computeVisible() {
  const base = state.searchQuery ? state.searchResults : state.posts;
  if (state.sortMode === SORT_RND) {
    if (!state.shuffled || state.shuffledBase !== base) {
      state.shuffled = [...base].sort(() => Math.random() - 0.5);
      state.shuffledBase = base;
    }
    return [...state.shuffled];
  }
  return [...base];
}

function buildPage(post) {
  const page = document.createElement('div');
  page.className = 'page';

  const img = document.createElement('img');
  img.className = 'bg';
  img.alt = '';
  img.dataset.src = post.imageUrl || '';
  img.onload = () => img.classList.add('loaded');
  img.onerror = () => img.classList.remove('loaded');
  page.appendChild(img);

  const grad = document.createElement('div');
  grad.className = 'gradient';
  page.appendChild(grad);

  const content = document.createElement('div');
  content.className = 'content';

  const kicker = document.createElement('div');
  kicker.className = 'kicker';
  const brandSub = post.subreddit || state.currentSub;
  kicker.textContent = 'r/' + brandSub.toLowerCase() + '  ·  REDDITSCROLL';
  content.appendChild(kicker);

  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = post.title;
  content.appendChild(title);

  let metaText = post.dateText || '';
  if (post.author) {
    metaText += (metaText ? '   ·   ' : '') + 'u/' + post.author;
  }
  if (metaText) {
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = metaText;
    content.appendChild(meta);
  }

  if (post.excerpt) {
    const ex = document.createElement('div');
    ex.className = 'excerpt';
    ex.textContent = post.excerpt;
    content.appendChild(ex);
  }

  const btn = document.createElement('button');
  btn.className = 'read-btn';
  btn.textContent = 'OPEN ON REDDIT  →';
  btn.addEventListener('click', () => openLink(post.link));
  content.appendChild(btn);

  page.appendChild(content);
  state.pageRefs.push({ post, image: img });
  return page;
}

function updateVisibleImages(i) {
  state.pageRefs.forEach((ref, idx) => {
    const near = Math.abs(idx - i) <= 1;
    if (near) {
      if (ref.image.dataset.src) {
        ref.image.src = ref.image.dataset.src;
      }
    } else {
      ref.image.removeAttribute('src');
      ref.image.classList.remove('loaded');
    }
  });
}

function updateMatchInfo() {
  if (!state.searchQuery) {
    els.matchInfo.classList.remove('visible');
    return;
  }
  els.matchInfo.classList.add('visible');
  els.matchInfo.textContent = 'r/' + state.currentSub.toLowerCase()
    + ' · “' + state.searchQuery + '” · '
    + state.searchResults.length + ' loaded';
}

function setLoadingPill(show, text) {
  if (text) els.loading.textContent = text;
  els.loading.style.display = show ? 'block' : 'none';
}

function flashOfflinePill() {
  els.offline.style.opacity = '1';
  els.offline.style.visibility = 'visible';
  setTimeout(() => {
    els.offline.style.opacity = '0';
  }, 200);
  setTimeout(() => {
    els.offline.style.visibility = 'hidden';
  }, 2600);
}

function showError(title, button, callback) {
  els.error.querySelector('.error-title').textContent = title;
  els.errorBtn.textContent = button;
  els.errorBtn.onclick = () => {
    els.error.classList.add('hidden');
    callback();
  };
  els.error.classList.remove('hidden');
}

function hideError() {
  els.error.classList.add('hidden');
}

function rebuild(preserve) {
  let savedLink = null;
  if (preserve && state.visible.length > 0) {
    const idx = Math.min(state.currentPage, state.visible.length - 1);
    savedLink = state.visible[idx]?.link;
  }

  state.visible = computeVisible();
  els.pager.innerHTML = '';
  state.pageRefs.length = 0;

  state.visible.forEach(post => {
    const page = buildPage(post);
    els.pager.appendChild(page);
  });

  let target = 0;
  if (state.pendingStartPage >= 0) {
    target = Math.min(state.pendingStartPage, Math.max(0, state.visible.length - 1));
    state.pendingStartPage = -1;
  } else if (savedLink) {
    const found = state.visible.findIndex(a => a.link === savedLink);
    if (found >= 0) target = found;
  }

  state.currentPage = target;
  els.pager.scrollTop = target * els.pager.clientHeight;
  updateVisibleImages(target);
  updateMatchInfo();
  state.renderedOnce = true;
  hideError();
  stylePills();
}

async function loadListing() {
  if (state.loading) return;
  state.loading = true;
  setLoadingPill(true, state.renderedOnce ? 'LOADING…' : 'LOADING…');

  try {
    const res = await fetch('/api/feed?sub=' + encodeURIComponent(state.currentSub) + '&sort=' + sortKey());
    const cached = res.headers.get('X-RedditScroll-Cached') === '1';
    const xml = await res.text();
    const parsed = parseAtom(xml);

    state.posts.length = 0;
    state.postIds.clear();
    state.shuffled = null;
    state.shuffledBase = null;

    parsed.forEach(post => {
      const key = post.id || post.link;
      if (!state.postIds.has(key)) {
        state.postIds.add(key);
        state.posts.push(post);
      }
    });

    state.loading = false;
    setLoadingPill(false);
    if (cached) flashOfflinePill();

    if (state.posts.length === 0 && !state.renderedOnce) {
      showError('0 posts · can’t reach Reddit', 'RETRY', loadListing);
      return;
    }

    rebuild(false);
  } catch (err) {
    state.loading = false;
    setLoadingPill(false);
    if (!state.renderedOnce || state.visible.length === 0) {
      showError('Can’t reach Reddit', 'RETRY', loadListing);
    } else {
      flashOfflinePill();
    }
  }
}

async function runSearch() {
  if (state.loading) return;
  state.loading = true;
  setLoadingPill(true, state.renderedOnce ? 'SEARCHING…' : 'LOADING…');

  try {
    const res = await fetch('/api/search?sub=' + encodeURIComponent(state.currentSub)
      + '&q=' + encodeURIComponent(state.searchQuery)
      + '&sort=' + searchSortKey());
    const cached = res.headers.get('X-RedditScroll-Cached') === '1';
    const xml = await res.text();
    const parsed = parseAtom(xml);

    state.searchResults.length = 0;
    state.searchIds.clear();
    state.shuffled = null;
    state.shuffledBase = null;

    parsed.forEach(post => {
      const key = post.id || post.link;
      if (!state.searchIds.has(key)) {
        state.searchIds.add(key);
        state.searchResults.push(post);
      }
    });

    state.loading = false;
    setLoadingPill(false);
    if (cached) flashOfflinePill();

    if (state.searchResults.length === 0) {
      showError('No results for “' + state.searchQuery + '” in r/' + state.currentSub, 'CLEAR', clearSearch);
    }
    rebuild(false);
  } catch (err) {
    state.loading = false;
    setLoadingPill(false);
    if (!state.renderedOnce || state.visible.length === 0) {
      showError('Can’t reach Reddit', 'RETRY', runSearch);
    } else {
      flashOfflinePill();
    }
  }
}

function onRefreshTap() {
  if (state.loading) return;
  els.refresh.style.transition = 'transform 0.5s';
  els.refresh.style.transform = 'rotate(360deg)';
  setTimeout(() => { els.refresh.style.transition = 'none'; els.refresh.style.transform = 'rotate(0deg)'; }, 500);
  if (state.searchQuery) runSearch();
  else loadListing();
}

function setCategory(sub) {
  if (sub === state.currentSub) return;
  state.currentSub = sub;
  localStorage.setItem('redditscroll-sub', sub);
  state.searchQuery = '';
  state.searchResults.length = 0;
  state.searchIds.clear();
  state.searchInput.value = '';
  hideSearchBox();
  state.posts.length = 0;
  state.postIds.clear();
  state.shuffled = null;
  state.shuffledBase = null;
  state.renderedOnce = false;
  stylePills();
  updateMatchInfo();
  loadListing();
}

function setSort(mode) {
  if (state.sortMode === mode && mode !== SORT_RND) return;
  state.sortMode = mode;
  localStorage.setItem('redditscroll-sort', mode);
  state.shuffled = null;
  state.shuffledBase = null;
  stylePills();
  if (mode === SORT_RND) {
    const base = state.searchQuery ? state.searchResults : state.posts;
    if (base.length > 0) state.pendingStartPage = Math.floor(Math.random() * base.length);
    rebuild(false);
  } else if (state.searchQuery) {
    runSearch();
  } else {
    loadListing();
  }
}

function buildCatPills() {
  els.catRow.innerHTML = '';
  CATS.forEach(([label, sub]) => {
    const pill = document.createElement('div');
    pill.className = 'pill';
    pill.textContent = label;
    pill.dataset.sub = sub;
    pill.addEventListener('click', () => setCategory(sub));
    els.catRow.appendChild(pill);
  });
}

function stylePills() {
  Array.from(els.catRow.children).forEach(pill => {
    pill.classList.toggle('active', pill.dataset.sub === state.currentSub);
  });
  els.sortHot.classList.toggle('active', state.sortMode === SORT_HOT);
  els.sortNew.classList.toggle('active', state.sortMode === SORT_NEW);
  els.sortTop.classList.toggle('active', state.sortMode === SORT_TOP);
  els.sortRnd.classList.toggle('active', state.sortMode === SORT_RND);
}

function expandSearch() {
  els.searchToggle.classList.add('hidden');
  els.searchExpanded.classList.remove('hidden');
  els.searchInput.placeholder = 'Search r/' + state.currentSub + '…';
  els.searchInput.focus();
}

function hideSearchBox() {
  els.searchExpanded.classList.add('hidden');
  els.searchToggle.classList.remove('hidden');
  els.searchInput.blur();
}

function submitSearch() {
  const q = els.searchInput.value.trim();
  if (!q || q === state.searchQuery) return;
  state.searchQuery = q;
  state.searchResults.length = 0;
  state.searchIds.clear();
  state.shuffled = null;
  state.shuffledBase = null;
  hideSearchBox();
  stylePills();
  updateMatchInfo();
  runSearch();
}

function clearSearch() {
  state.searchQuery = '';
  state.searchResults.length = 0;
  state.searchIds.clear();
  els.searchInput.value = '';
  hideSearchBox();
  stylePills();
  updateMatchInfo();
  rebuild(false);
}

function openLink(url) {
  if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.openLink) {
    window.webkit.messageHandlers.openLink.postMessage(url);
    return;
  }
  fetch('/api/open?url=' + encodeURIComponent(url)).catch(() => {});
}

function hideHint() {
  if (!els.hint.classList.contains('hidden')) {
    localStorage.setItem('redditscroll-hint', '1');
    els.hint.classList.add('hidden');
  }
}

function onScroll() {
  const h = els.pager.clientHeight || window.innerHeight;
  const i = Math.max(0, Math.min(state.pageRefs.length - 1, Math.round(els.pager.scrollTop / h)));
  if (i !== state.currentPage) {
    state.currentPage = i;
    if (state.firstPageEvent) {
      state.firstPageEvent = false;
      return;
    }
    hideHint();
    updateVisibleImages(i);
  }
}

let scrollTimer;
els.pager.addEventListener('scroll', () => {
  window.cancelAnimationFrame(scrollTimer);
  scrollTimer = window.requestAnimationFrame(onScroll);
}, { passive: true });

els.sortHot.addEventListener('click', () => setSort(SORT_HOT));
els.sortNew.addEventListener('click', () => setSort(SORT_NEW));
els.sortTop.addEventListener('click', () => setSort(SORT_TOP));
els.sortRnd.addEventListener('click', () => setSort(SORT_RND));
els.refresh.addEventListener('click', onRefreshTap);
els.searchToggle.addEventListener('click', expandSearch);
els.searchGo.addEventListener('click', submitSearch);
els.searchClear.addEventListener('click', clearSearch);
els.searchInput.addEventListener('keydown', e => {
  if (e.key === 'Enter') submitSearch();
});

window.addEventListener('keydown', e => {
  if (e.key === 'ArrowDown' || e.key === 'PageDown') {
    els.pager.scrollBy({ top: els.pager.clientHeight, behavior: 'smooth' });
  } else if (e.key === 'ArrowUp' || e.key === 'PageUp') {
    els.pager.scrollBy({ top: -els.pager.clientHeight, behavior: 'smooth' });
  }
});

if (els.scrollUp && els.scrollDown) {
  els.scrollUp.addEventListener('click', () => {
    els.pager.scrollBy({ top: -els.pager.clientHeight, behavior: 'smooth' });
  });
  els.scrollDown.addEventListener('click', () => {
    els.pager.scrollBy({ top: els.pager.clientHeight, behavior: 'smooth' });
  });
}

window.addEventListener('resize', () => {
  if (state.pageRefs.length > 0) {
    els.pager.scrollTop = state.currentPage * els.pager.clientHeight;
  }
});

buildCatPills();
stylePills();
if (!state.hintShown) {
  els.hint.classList.remove('hidden');
}
loadListing();
