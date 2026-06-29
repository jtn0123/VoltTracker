const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const args = process.argv.slice(2);

function argValue(name, fallback) {
  const index = args.indexOf(name);
  if (index === -1 || index + 1 >= args.length) return fallback;
  return args[index + 1];
}

const port = Number(argValue('--port', process.env.DASHBOARD_E2E_PORT || '8099'));
const host = argValue('--host', '127.0.0.1');
const root = path.resolve(argValue('--dir', path.resolve(__dirname, '../app/src/main/assets/dashboard')));

const MIME_TYPES = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

function send(res, status, body, headers = {}) {
  res.writeHead(status, headers);
  res.end(body);
}

function buildFileIndex(dir, prefix = '') {
  const files = new Map();
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    const urlPath = `${prefix}/${entry.name}`;
    if (entry.isDirectory()) {
      for (const [childUrlPath, child] of buildFileIndex(fullPath, urlPath)) {
        files.set(childUrlPath, child);
      }
      continue;
    }
    if (entry.isFile()) {
      files.set(urlPath, {
        fullPath,
        size: fs.statSync(fullPath).size,
        contentType: MIME_TYPES[path.extname(fullPath)] || 'application/octet-stream',
      });
    }
  }
  return files;
}

const fileIndex = buildFileIndex(root);
fileIndex.set('/', fileIndex.get('/index.html'));

function fileForRequest(urlPath) {
  const pathname = new URL(urlPath || '/', 'http://127.0.0.1').pathname;
  const decoded = decodeURIComponent(pathname);
  const requested = decoded === '/' ? '/' : path.posix.normalize(decoded);
  if (!requested.startsWith('/') || requested.split('/').includes('..') || requested.includes('\0')) {
    return null;
  }
  return fileIndex.get(requested) || null;
}

const server = http.createServer((req, res) => {
  if (!req.url || (req.method !== 'GET' && req.method !== 'HEAD')) {
    send(res, 405, 'Method not allowed');
    return;
  }
  let fileEntry;
  try {
    fileEntry = fileForRequest(req.url);
  } catch (_err) {
    send(res, 400, 'Bad request');
    return;
  }
  if (!fileEntry) {
    send(res, 404, 'Not found');
    return;
  }
  const headers = {
    'Content-Type': fileEntry.contentType,
    'Content-Length': fileEntry.size,
  };
  if (req.method === 'HEAD') {
    send(res, 200, '', headers);
    return;
  }
  res.writeHead(200, headers);
  const stream = fs.createReadStream(fileEntry.fullPath);
  stream.on('error', () => {
    if (!res.headersSent) {
      send(res, 500, 'Internal server error');
    } else {
      res.destroy();
    }
  });
  stream.pipe(res);
});

server.listen(port, host, () => {
  process.stdout.write(`dashboard-e2e server listening at http://${host}:${port}\n`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
