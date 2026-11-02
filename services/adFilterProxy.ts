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
import { fetchAndFilterPlaylist, resolveMediaPlaylistUrl } from "./adFilter";

export const AD_PROXY_PORT = 12347;

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

class AdFilterProxyService {
  private server: TCPHttpServer | null = null;
  private running = false;

  constructor() {
    this.server = new TCPHttpServer(AD_PROXY_PORT);
    this.server.setRequestHandler(async (request) => {
      try {
        const target = getQueryParam(request.url, "u");
        if (!target) {
          return { statusCode: 400, headers: { "Content-Type": "text/plain" }, body: "missing u" };
        }
        const original = target;

        // master -> 解析子列表，再对该子列表过滤
        let mediaUrl = original;
        const sub = await resolveMediaPlaylistUrl(original);
        if (sub) mediaUrl = sub;

        // 始终返回绝对化后的媒体列表（有广告则剔除，无广告则原样绝对化），避免相对路径 404
        const cleaned = await fetchAndFilterPlaylist(mediaUrl);
        if (cleaned) {
          return {
            statusCode: 200,
            headers: {
              "Content-Type": "application/vnd.apple.mpegurl",
              "Access-Control-Allow-Origin": "*",
            },
            body: cleaned,
          };
        }

        // 拉取失败（罕见）-> 兜底重定向到原始/子列表地址
        return redirect(mediaUrl);
      } catch (e) {
        return { statusCode: 500, headers: { "Content-Type": "text/plain" }, body: "proxy error" };
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
