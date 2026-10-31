import { Container, getRandom } from '@cloudflare/containers';

export class FFmpegContainer extends Container {
    defaultPort = 8080;
    sleepAfter = '5m';
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (url.pathname !== '/stream') return new Response('Not Found', { status: 404 });
        const streamUrl = url.searchParams.get('url');
        if (!streamUrl) return new Response('Missing url', { status: 400 });
        
        const container = await getRandom(env.FFMPEG_CONTAINER, 3);
        return container.fetch(new Request(`http://container/stream?url=${encodeURIComponent(streamUrl)}`));
    }
};
