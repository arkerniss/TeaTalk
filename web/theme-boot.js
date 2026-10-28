/**
 * Resolves the page's colour scheme before the stylesheet paints.
 *
 * Loaded synchronously in <head>, ahead of /style.css, so an explicitly pinned
 * theme never flashes the other one. The Android host app forwards its own
 * light/dark setting as ?theme=light|dark; browsers simply omit the parameter and
 * fall back to the prefers-color-scheme media query in the stylesheet.
 */
(function () {
  var forced = null;
  try {
    forced = new URLSearchParams(location.search).get('theme');
  } catch (_) {
    forced = null;
  }
  if (forced !== 'light' && forced !== 'dark') forced = null;

  var root = document.documentElement;
  if (forced) {
    root.setAttribute('data-theme', forced);
    // Keep the browser chrome / iOS status bar in step with the pinned theme.
    var scheme = document.querySelector('meta[name="color-scheme"]');
    if (scheme) scheme.setAttribute('content', forced);
    var colors = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < colors.length; i += 1) {
      colors[i].removeAttribute('media');
      colors[i].setAttribute('content', forced === 'dark' ? '#121110' : '#faf8f3');
    }
  }
})();
