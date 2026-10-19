// Runs in the PWA's page, before its own scripts (MainActivity injects it for the PWA's origin
// only). Wraps the raw message channel the app provides (`window.mustardNativeChannel`) in
// `window.mustardNative`, the same promise-based API the iOS app (../mobile-ios) offers, so the
// website feature-detects it once for both:
//
//   if (window.mustardNative?.features.documentScanner) {
//     const scan = await mustardNative.scanDocument({ pdf: true });
//     if (scan) upload(scan.pdf.base64);   // null if the person cancelled
//   }
//
// Calls reject with an Error whose `code` is 'unsupported', 'unavailable', 'busy',
// 'encoding-failed' or 'failed'. The protocol underneath is JSON strings:
// {id, method, args} in, {id, result | error} out.
(function () {
  var channel = window.mustardNativeChannel;
  if (!channel || window.mustardNative) return;
  var config = __CONFIG__;

  var pending = {};
  var nextId = 1;
  channel.addEventListener('message', function (event) {
    var message = JSON.parse(event.data);
    var call = pending[message.id];
    if (!call) return;
    delete pending[message.id];
    if (message.error) {
      var error = new Error('mustardNative.' + call.method + ': ' + message.error.code);
      error.code = message.error.code;
      error.detail = message.error.message;
      call.reject(error);
    } else {
      call.resolve(message.result);
    }
  });

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { method: method, resolve: resolve, reject: reject };
      channel.postMessage(JSON.stringify({ id: id, method: method, args: args || {} }));
    });
  }

  function toBase64(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result).replace(/^data:[^,]*,/, '')); };
      reader.onerror = function () { reject(reader.error); };
      reader.readAsDataURL(blob);
    });
  }

  window.mustardNative = Object.freeze({
    platform: 'android',
    version: config.version,
    features: Object.freeze({ documentScanner: config.documentScanner }),

    /**
     * Opens Google's document scanner. Resolves with
     * {title: null, pageCount, pages?: [{mimeType, base64, width, height}], pdf?: {mimeType, base64}},
     * or null if the person cancelled. Options as on iOS: pages (default true), pdf (default
     * false), maxDimension (default 2000), quality (default 0.8); Android only: pageLimit,
     * galleryImport (default true), mode ('full' | 'filter' | 'base', default 'full').
     */
    scanDocument: function (options) { return call('scanDocument', options); },

    /** Android only: asks where to save `blob` ("Save to" screen). Resolves false if cancelled. */
    saveFile: function (blob, name) {
      return toBase64(blob).then(function (base64) {
        return call('saveFile', { name: name, mimeType: blob.type || 'application/octet-stream', base64: base64 });
      });
    },
  });

  // A web view ignores <a download> links; send blob: and data: downloads (the website's
  // exports) through saveFile instead.
  document.addEventListener('click', function (event) {
    var link = event.target instanceof Element && event.target.closest('a[download]');
    if (!link || !/^(blob|data):/.test(link.href)) return;
    event.preventDefault();
    var name = link.getAttribute('download') || 'download';
    fetch(link.href)
      .then(function (response) { return response.blob(); })
      .then(function (blob) { return window.mustardNative.saveFile(blob, name); })
      .catch(function (error) { console.error('Saving ' + name + ' failed', error); });
  }, true);

  // Reports the page's colors at the top and bottom edges whenever they change, so the app can
  // paint the status and navigation bars to match (the PWA has its own Appearance setting).
  var reported = '';
  function colorAt(y) {
    for (var element = document.elementFromPoint(window.innerWidth / 2, y); element; element = element.parentElement) {
      var match = /^rgba?\(([^)]+)\)/.exec(getComputedStyle(element).backgroundColor);
      if (!match) continue;
      var color = match[1].split(/[\s,\/]+/).map(parseFloat);
      if (color.length > 3 && color[3] < 0.5) continue;
      return '#' + color.slice(0, 3).map(function (c) { return ('0' + Math.round(c).toString(16)).slice(-2); }).join('');
    }
    return null;
  }
  function checkColors() {
    var top = colorAt(1);
    var bottom = colorAt(window.innerHeight - 2);
    var colors = JSON.stringify({ top: top, bottom: bottom });
    if (colors === reported || !top) return;
    reported = colors;
    channel.postMessage(JSON.stringify({ method: 'colors', args: { top: top, bottom: bottom || top } }));
  }
  var timer;
  function scheduleCheck() {
    clearTimeout(timer);
    timer = setTimeout(checkColors, 150);
  }
  function watchColors() {
    new MutationObserver(scheduleCheck).observe(document.documentElement, {
      subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style'],
    });
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', scheduleCheck);
    window.addEventListener('pageshow', scheduleCheck);
    window.addEventListener('resize', scheduleCheck);
    checkColors();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchColors);
  else watchColors();

  window.dispatchEvent(new Event('mustardnativeready'));
})();
