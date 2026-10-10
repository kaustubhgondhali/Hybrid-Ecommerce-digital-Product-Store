/* Minimal JSON API client. The server is the source of truth for every price, stock level and status;
   this file only transports requests and attaches the CSRF token. */
(function () {
  'use strict';

  class ApiError extends Error {
    constructor(status, code, message, data) {
      super(message);
      this.status = status;
      this.code = code;
      this.data = data || {};
    }
  }

  function cookie(name) {
    const match = document.cookie.split('; ').find((c) => c.startsWith(name + '='));
    return match ? decodeURIComponent(match.substring(name.length + 1)) : null;
  }

  async function ensureCsrfCookie() {
    if (!cookie('XSRF-TOKEN')) {
      await fetch('/actuator/health', { credentials: 'same-origin' });
    }
  }

  async function request(method, url, body, options, retried) {
    const opts = options || {};
    const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
    let payload;
    if (body instanceof FormData) {
      payload = body;
    } else if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    if (method !== 'GET') {
      await ensureCsrfCookie();
      const token = cookie('XSRF-TOKEN');
      if (token) headers['X-XSRF-TOKEN'] = token;
    }
    let res;
    try {
      res = await fetch(url, { method, headers, body: payload, credentials: 'same-origin' });
    } catch (e) {
      throw new ApiError(0, 'NETWORK', 'Could not reach the server. Check your connection and try again.');
    }
    if (res.status === 204) return null;
    const type = res.headers.get('content-type') || '';
    const data = type.includes('json') ? await res.json().catch(() => null) : null;
    if (!res.ok) {
      const code = (data && data.code) || 'HTTP_' + res.status;
      if (code === 'CSRF_INVALID' && !retried) {
        await fetch('/actuator/health', { credentials: 'same-origin' });
        return request(method, url, body, options, true);
      }
      const message = (data && (data.detail || data.title)) || res.statusText || 'Request failed';
      throw new ApiError(res.status, code, message, data);
    }
    return data;
  }

  window.ApiError = ApiError;
  window.Api = {
    get: (url) => request('GET', url),
    post: (url, body, opts) => request('POST', url, body, opts),
    put: (url, body) => request('PUT', url, body),
    patch: (url, body) => request('PATCH', url, body),
    del: (url) => request('DELETE', url),
    upload: (url, formData) => request('POST', url, formData)
  };
})();
