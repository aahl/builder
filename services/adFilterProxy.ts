/**
 * 广告过滤本地代理
 *
 * 启动一个本地 HTTP 服务（默认 127.0.0.1:12347）。播放器把 playlist 请求打到本代理：
 *   http://127.0.0.1:12347/proxy?u=<encodeURIComponent(原始m3u8)>
 *
 * 代理行为（一跳完成，不依赖 302 重定向）：
 *   - 若 u 是 master 播放列表 -> 解析出最高码率子列表 URL，再直接对该子列表做过滤并返回
 *   - 若是媒体播放列表 -> 拉取、去广告、重建，回吐"干净"的 m3u8
 *   - 任何异常/无广告 -> 302 重定向到原始 URL（兜底，绝不阻断播放）
 *
 * 分片不走代理：过滤后的 playlist 里全是绝对 URL，播放器直连 CDN。
 */
import TCPHttpServer from "./tcpHttpServer";
import { analyzePlaylistText, pickBestMediaUrl, rebuildPlaylist, absolutize } from "./adFilter";

export const AD_PROXY_PORT = 12347;

// 简易内存缓存：同一 playlist 在短时间内重复请求（播放器重试/预加载）直接命中，秒回。
const CACHE_TTL = 60_000;
const CACHE_MAX = 120;
const playlistCache = new Map<string, { body: string; exp: number }>();

function cacheGet(key: string): string | null {
  const hit = playlistCache.get(key);
  if (!hit) return null;
  if (hit.exp < Date.now()) {
    playlistCache.delete(key);
    return null;
  }
  return hit.body;
}

function cacheSet(key: string, body: string) {
  if (playlistCache.size >= CACHE_MAX) playlistCache.clear();
  playlistCache.set(key, { body, exp: Date.now() + CACHE_TTL });
}

function isPlaylistUrl(u: string): boolean {
  return /\.m3u8(\?|$)/i.test(u) || /\/proxy\?/i.test(u);
}

function getQueryParam(urlPath: string, name: string): string | null {
  const qIndex = urlPath.indexOf("?");
  if (qIndex < 0) return null;
  const query = urlPath.slice(qIndex + 1);
  for (const pair of query.split("&")) {
    const eq = pair.indexOf("=");
    const k = eq >= 0 ? pair.slice(0, eq) : pair;
    if (k === name) {
      const v = eq >= 0 ? pair.slice(eq + 1) : "";
      try {
        return decodeURIComponent(v);
      } catch {
        return v;
      }
    }
  }
  return null;
}

const redirect = (to: string) => ({
  statusCode: 302,
  headers: { Location: to, "Content-Type": "text/plain" },
  body: "",
});

/** 把播放列表里所有相对路径的分片/子列表 URI 绝对化（不改动注释行）。 */
function absolutizePlaylistText(text: string, baseUrl: string): string {
  return text
    .split(/\r?\n/)
    .map((line) => {
      const s = line.trim();
      if (!s || s.startsWith("#")) return line;
      return absolutize(s, baseUrl);
    })
    .join("\n");
}

class AdFilterProxyService {
  private server: TCPHttpServer | null = null;
  private running = false;

  constructor() {
    this.server = new TCPHttpServer(AD_PROXY_PORT);
    this.server.setRequestHandler(async (request) => {
      const target = getQueryParam(request.url, "u");
      if (!target) {
        return { statusCode: 400, headers: { "Content-Type": "text/plain" }, body: "missing u" };
      }
      const okHeaders = {
        "Content-Type": "application/vnd.apple.mpegurl",
        "Access-Control-Allow-Origin": "*",
      };

      // 命中缓存直接返回（重试/重复请求秒回，避免超时）
      const cachedBody = cacheGet(target);
      if (cachedBody !== null) {
        return { statusCode: 200, headers: okHeaders, body: cachedBody };
      }

      // 兜底：直接回吐原始内容（仍以 200 返回，避免播放器跨协议 302 失败）
      // 并把相对路径分片绝对化，避免非标准 master 在代理下 404。
      const passthrough = async (url: string) => {
        try {
          const r = await fetch(url);
          if (r.ok) {
            const raw = await r.text();
            const body = absolutizePlaylistText(raw, url);
            return { statusCode: 200, headers: okHeaders, body };
          }
        } catch {}
        return redirect(url);
      };

      try {
        // 只下载一次原始地址
        let resp: Response | null = null;
        try {
          resp = await fetch(target);
        } catch {
          return redirect(target);
        }
        if (!resp || !resp.ok) return redirect(target);
        const text = await resp.text();

        // master -> 解析出最高码率子列表，再取一次子列表内容
        let mediaText = text;
        let mediaBase = target;
        if (!/#EXTINF/.test(text) && /#EXT-X-STREAM-INF/.test(text)) {
          const sub = pickBestMediaUrl(text, target);
          if (sub) {
            mediaBase = sub;
            try {
              const r2 = await fetch(sub);
              if (r2.ok) mediaText = await r2.text();
              else return passthrough(sub);
            } catch {
              return passthrough(sub);
            }
          }
        }

        // 始终返回绝对化后的媒体列表（有广告则剔除，无广告则原样绝对化），避免相对路径 404
        const cleaned = await analyzePlaylistText(mediaText, mediaBase);
        if (cleaned) {
          const body = rebuildPlaylist(cleaned.parsed, cleaned.baseUrl);
          cacheSet(target, body);
          return { statusCode: 200, headers: okHeaders, body };
        }

        // 分析失败（例如非标准列表）-> 原样透传（绝对化分片）
        const body = absolutizePlaylistText(mediaText, mediaBase);
        cacheSet(target, body);
        return { statusCode: 200, headers: okHeaders, body };
      } catch (e) {
        console.info("[AdFilterProxy] handler error:", e);
        return passthrough(target);
      }
    });
  }

  async start(): Promise<string | null> {
    if (this.running) return `http://127.0.0.1:${AD_PROXY_PORT}`;
    try {
      await this.server!.start();
      this.running = true;
      return `http://127.0.0.1:${AD_PROXY_PORT}`;
    } catch (e) {
      console.info("[AdFilterProxy] start failed:", e);
      this.running = false;
      return null;
    }
  }

  stop() {
    this.server?.stop();
    this.running = false;
  }

  isRunning() {
    return this.running;
  }

  /** 把原始播放地址包装成走本代理的地址；非播放列表或未运行时原样返回 */
  wrap(originalUrl: string): string {
    if (!this.running) return originalUrl;
    if (!isPlaylistUrl(originalUrl)) return originalUrl;
    if (originalUrl.startsWith(`http://127.0.0.1:${AD_PROXY_PORT}/proxy`)) return originalUrl;
    return `http://127.0.0.1:${AD_PROXY_PORT}/proxy?u=${encodeURIComponent(originalUrl)}`;
  }
}

export const adFilterProxy = new AdFilterProxyService();
