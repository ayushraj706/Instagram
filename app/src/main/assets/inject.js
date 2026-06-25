/**
 * inject.js — Instagram WebView DOM Injector
 * Strategy: MutationObserver watches for article/video elements across feed, reels, stories.
 * Injects floating download buttons per media container.
 * Uses Android.downloadMedia() JavascriptInterface to pass URLs + type to native code.
 */

(function () {
  'use strict';

  // ─── Constants ───────────────────────────────────────────────────────────────
  const BTN_CLASS = '__igdl_btn__';
  const BTN_ATTR = 'data-igdl-injected';
  const PROCESSED_ATTR = 'data-igdl-processed';

  // ─── Styles ───────────────────────────────────────────────────────────────────
  const style = document.createElement('style');
  style.textContent = `
    .${BTN_CLASS} {
      position: absolute;
      top: 10px;
      right: 10px;
      z-index: 999999;
      background: linear-gradient(135deg, #833ab4, #fd1d1d, #fcb045);
      color: #fff;
      border: none;
      border-radius: 8px;
      padding: 7px 13px;
      font-size: 12px;
      font-weight: 700;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 5px;
      box-shadow: 0 2px 12px rgba(0,0,0,0.45);
      letter-spacing: 0.3px;
      pointer-events: auto;
      transition: transform 0.15s ease, opacity 0.15s ease;
      -webkit-tap-highlight-color: transparent;
    }
    .${BTN_CLASS}:active {
      transform: scale(0.93);
      opacity: 0.85;
    }
    .${BTN_CLASS} svg {
      width: 14px;
      height: 14px;
      fill: white;
      flex-shrink: 0;
    }
    .__igdl_wrapper__ {
      position: relative;
    }
    .__igdl_toast__ {
      position: fixed;
      bottom: 80px;
      left: 50%;
      transform: translateX(-50%) translateY(0);
      background: rgba(20,20,20,0.92);
      color: #fff;
      padding: 10px 20px;
      border-radius: 20px;
      font-size: 13px;
      font-family: -apple-system, BlinkMacSystemFont, sans-serif;
      z-index: 9999999;
      pointer-events: none;
      opacity: 1;
      transition: opacity 0.4s ease;
    }
  `;
  document.head.appendChild(style);

  // ─── Download icon SVG ────────────────────────────────────────────────────────
  const DOWNLOAD_SVG = `<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 16l-6-6h4V4h4v6h4l-6 6zm-7 2h14v2H5v-2z"/>
  </svg>`;

  // ─── Toast notification ───────────────────────────────────────────────────────
  function showToast(msg) {
    const existing = document.querySelector('.__igdl_toast__');
    if (existing) existing.remove();
    const toast = document.createElement('div');
    toast.className = '__igdl_toast__';
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(() => { toast.style.opacity = '0'; }, 1800);
    setTimeout(() => toast.remove(), 2300);
  }

  // ─── Extract best-resolution image from srcset ────────────────────────────────
  function extractBestSrc(img) {
    if (img.srcset) {
      const parts = img.srcset.split(',')
        .map(s => s.trim().split(/\s+/))
        .filter(p => p.length >= 2)
        .sort((a, b) => {
          const wa = parseInt(a[1]) || 0;
          const wb = parseInt(b[1]) || 0;
          return wb - wa;
        });
      if (parts.length && parts[0][0]) return parts[0][0];
    }
    return img.src || null;
  }

  // ─── Collect all media URLs from a container ─────────────────────────────────
  function collectMediaFromContainer(container) {
    const results = [];

    // Videos — prefer the highest quality source
    const videos = container.querySelectorAll('video');
    videos.forEach(v => {
      let url = null;
      // Try <source> children first (higher quality)
      const sources = v.querySelectorAll('source');
      sources.forEach(s => { if (!url && s.src) url = s.src; });
      if (!url && v.src) url = v.src;
      if (url && url.startsWith('http')) {
        results.push({ url, type: 'video', label: 'Video' });
      }
    });

    // Images — only if no video found (avoid icon/avatar noise)
    if (results.length === 0) {
      const imgs = container.querySelectorAll('img[srcset], img[src]');
      imgs.forEach(img => {
        // Skip tiny avatars / icons
        const w = img.naturalWidth || img.width || 0;
        const h = img.naturalHeight || img.height || 0;
        if (w < 100 && h < 100) return;
        // Skip profile pics (usually in links to profile pages)
        if (img.closest('a[href*="/p/"]') || img.closest('[role="img"]')) {
          const url = extractBestSrc(img);
          if (url && url.startsWith('http')) {
            results.push({ url, type: 'image', label: 'Photo' });
          }
        }
      });
    }

    return results;
  }

  // ─── Create download button ───────────────────────────────────────────────────
  function createDownloadButton(mediaList, container) {
    const btn = document.createElement('button');
    btn.className = BTN_CLASS;
    btn.setAttribute(BTN_ATTR, '1');
    btn.innerHTML = `${DOWNLOAD_SVG} Download`;

    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      e.preventDefault();

      if (!window.Android) {
        showToast('⚠ Android bridge not available');
        return;
      }

      if (mediaList.length === 1) {
        const m = mediaList[0];
        window.Android.downloadMedia(m.url, m.type);
        showToast(`⬇ Downloading ${m.label}…`);
      } else {
        // Multiple media (carousel) — pass JSON array
        const payload = JSON.stringify(mediaList);
        window.Android.downloadMultiple(payload);
        showToast(`⬇ Downloading ${mediaList.length} items…`);
      }
    });

    return btn;
  }

  // ─── Inject button into a container ──────────────────────────────────────────
  function injectIntoContainer(container) {
    if (container.getAttribute(PROCESSED_ATTR)) return;
    container.setAttribute(PROCESSED_ATTR, '1');

    const mediaList = collectMediaFromContainer(container);
    if (!mediaList.length) return;

    // Ensure parent is positioned for absolute button placement
    const computed = window.getComputedStyle(container);
    if (computed.position === 'static') {
      container.style.position = 'relative';
    }

    const btn = createDownloadButton(mediaList, container);
    container.appendChild(btn);
  }

  // ─── Find all injectable containers on page ───────────────────────────────────
  function scanPage() {
    // Feed posts and Reels: Instagram wraps each post in <article>
    document.querySelectorAll('article').forEach(article => {
      injectIntoContainer(article);
    });

    // Direct video elements not inside article (Stories, Clips)
    document.querySelectorAll('video').forEach(video => {
      const article = video.closest('article');
      if (!article) {
        // Walk up to find best wrapper
        const wrapper = video.closest('div[role="dialog"]') ||
                        video.closest('section') ||
                        video.parentElement;
        if (wrapper && !wrapper.getAttribute(PROCESSED_ATTR)) {
          injectIntoContainer(wrapper);
        }
      }
    });
  }

  // ─── MutationObserver — watch DOM for new posts (infinite scroll) ─────────────
  const observer = new MutationObserver((mutations) => {
    let shouldScan = false;
    for (const mutation of mutations) {
      if (mutation.addedNodes.length > 0) {
        for (const node of mutation.addedNodes) {
          if (node.nodeType === Node.ELEMENT_NODE) {
            // Only re-scan if something meaningful was added
            if (
              node.tagName === 'ARTICLE' ||
              node.tagName === 'VIDEO' ||
              node.querySelector?.('article, video')
            ) {
              shouldScan = true;
              break;
            }
          }
        }
      }
      if (shouldScan) break;
    }
    if (shouldScan) scanPage();
  });

  // ─── Start observing once DOM is ready ───────────────────────────────────────
  function init() {
    scanPage(); // Initial scan

    observer.observe(document.body, {
      childList: true,
      subtree: true,
    });

    // Re-scan on navigation (Instagram is a SPA — URL changes don't reload page)
    let lastUrl = location.href;
    setInterval(() => {
      if (location.href !== lastUrl) {
        lastUrl = location.href;
        // Short delay to let React render the new route
        setTimeout(scanPage, 800);
        setTimeout(scanPage, 1800); // Second pass for slow renders
      }
    }, 500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
