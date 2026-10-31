const http = require('http');
const { spawn } = require('child_process');
const { URL } = require('url');

const server = http.createServer((req, res) => {
    const parsedUrl = new URL(req.url, `http://${req.headers.host}`);
    if (parsedUrl.pathname !== '/stream' || !parsedUrl.searchParams.has('url')) {
        res.writeHead(400); return res.end('Missing url');
    }
    const streamUrl = parsedUrl.searchParams.get('url');
    const ffmpeg = spawn('ffmpeg', [
        '-hide_banner', '-loglevel', 'error', '-i', streamUrl, '-vn', 
        '-map', '0:a:0', '-acodec', 'libopus', '-b:a', '24k', '-ac', '1', '-ar', '48000', '-f', 'webm', 'pipe:1'
    ]);
    res.writeHead(200, { 'Content-Type': 'audio/webm', 'Transfer-Encoding': 'chunked' });
    ffmpeg.stdout.pipe(res);
    req.on('close', () => ffmpeg.kill('SIGKILL'));
});
server.listen(8080, () => console.log('Ready'));
