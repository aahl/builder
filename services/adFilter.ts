/**
 * 广告过滤服务（A 类：中段插块）
 *
 * 原理：HLS 播放列表里，广告块通常由 #EXT-X-DISCONTINUITY 切出，且满足：
 *   - 块很短（分片少、总时长短）
 *   - 块的首个视频 PTS 与"正片累计时间轴"严重不符（外来时间轴，常从 ~1.4s 重新开始）
 *   - 可选辅助：块的加密状态与正片不同（正片 AES-128，广告块 METHOD=NONE）
 *
 * 识别后，将广告块整体剔除，并重建一份"干净"的播放列表：
 *   - 去掉所有广告分片
 *   - 去掉所有 #EXT-X-DISCONTINUITY
 *   - 顶部写一条正片 key（加密源），丢弃块内 METHOD=NONE
 *   - 分片地址全部绝对化（避免相对路径在代理下 404）
 *
 * 只改写 playlist，分片仍由播放器直连 CDN（代理零流量、不解密）。
 *
 * 安全兜底：任何解析/网络异常 -> 返回 null，调用方回退到原始 URL。
 *
 * 说明：RN 环境无 ffprobe，PTS 通过解析 MPEG-TS 前若干包的 PES 头得到。
 */

// 判断一个分片 URI 是否为 TS（含伪装成 .jpeg 的 TS 分片）
const TS_EXT_RE = /\.(ts|jpe?g|m4s|aac)(\?|$)/i;

export interface AdFilterOptions {
  // 单块时长上限（秒）：超过视为正片
  maxAdBlockDuration?: number;
  // 单块分片数上限
  maxAdBlockSegments?: number;
  // PTS 与期望累计时间轴的最大容差（秒）
  ptsTolerance?: number;
  // 最多检查多少个候选块（性能保护）
  maxCandidatesToCheck?: number;
}

const DEFAULTS: Required<AdFilterOptions> = {
  maxAdBlockDuration: 25,
  maxAdBlockSegments: 8,
  ptsTolerance: 30,
  maxCandidatesToCheck: 6,
};

interface ParsedSegment {
  duration: number;
  uri: string; // 原始（可能是相对）
  absUri: string; // 绝对化后
}

interface ParsedBlock {
  segments: ParsedSegment[];
  discontinuityBefore: boolean;
  keyMethod: string; // NONE | AES-128 | ...
  keyUri?: string;
  keyIv?: string;
  isAd: boolean;
}

interface ParsedPlaylist {
  headerLines: string[]; // VERSION/TARGETDURATION/PLAYLIST-TYPE/MEDIA-SEQUENCE 等
  blocks: ParsedBlock[];
  dominantKey: { method: string; uri?: string; iv?: string };
}

/** 将相对 URI 解析为绝对 URI（避免依赖 RN 不完整的 URL 实现） */
export function absolutize(uri: string, baseUrl: string): string {
  const u = uri.trim();
  if (/^https?:\/\//i.test(u)) return u;
  const m = baseUrl.match(/^(https?:\/\/[^/]+)(\/.*)?$/i);
  const origin = m ? m[1] : "";
  const basePath = m && m[2] ? m[2] : "/";
  if (u.startsWith("//")) {
    const proto = origin.split("://")[0] || "https";
    return proto + ":" + u;
  }
  if (u.startsWith("/")) return origin + u;
  const dir = basePath.replace(/[^/]*$/, "");
  return origin + dir + u;
}

/** 解析 m3u8 文本为 header + 分块 */
function parsePlaylist(text: string, baseUrl: string): ParsedPlaylist {
  const lines = text.split(/\r?\n/);
  const headerLines: string[] = [];
  const blocks: ParsedBlock[] = [];

  let cur: ParsedBlock | null = null;
  let discPending = false;
  let keyMethod = "NONE";
  let keyUri: string | undefined;
  let keyIv: string | undefined;
  let pendingDuration: number | null = null;
  let sawSegment = false;

  const ensureBlock = () => {
    if (!cur) {
      cur = {
        segments: [],
        discontinuityBefore: discPending,
        keyMethod,
        keyUri,
        keyIv,
        isAd: false,
      };
      blocks.push(cur);
      discPending = false;
    }
    return cur;
  };

  for (const raw of lines) {
    const s = raw.trim();
    if (!s) continue;

    if (s.startsWith("#EXTM3U")) continue;

    if (s.startsWith("#EXT-X-DISCONTINUITY")) {
      discPending = true;
      cur = null;
      continue;
    }

    if (s.startsWith("#EXT-X-KEY")) {
      const m = s.match(/METHOD=([^,\s]+)/);
      keyMethod = m ? m[1] : "NONE";
      const mu = s.match(/URI="([^"]+)"/);
      keyUri = mu ? mu[1] : undefined;
      const mi = s.match(/IV=([^,\s]+)/);
      keyIv = mi ? mi[1] : undefined;
      continue;
    }

    if (s.startsWith("#EXTINF")) {
      const m = s.match(/#EXTINF:([0-9.]+)/);
      pendingDuration = m ? parseFloat(m[1]) : 0;
      continue;
    }

    if (s.startsWith("#")) {
      // 其它标签：仅在尚未出现分片前归入 header
      if (!sawSegment && cur === null && blocks.length === 0) {
        if (
          s.startsWith("#EXT-X-VERSION") ||
          s.startsWith("#EXT-X-TARGETDURATION") ||
          s.startsWith("#EXT-X-PLAYLIST-TYPE") ||
          s.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
          s.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
        ) {
          headerLines.push(s);
        }
      }
      continue;
    }

    // 分片 URI 行
    sawSegment = true;
    const blk = ensureBlock();
    blk.segments.push({
      duration: pendingDuration ?? 0,
      uri: s,
      absUri: absolutize(s, baseUrl),
    });
    pendingDuration = null;
  }

  // 主导 key：第一个"长块"(>=10 分片) 的 key，否则第一块
  let dominantKey = { method: blocks[0]?.keyMethod ?? "NONE", uri: blocks[0]?.keyUri, iv: blocks[0]?.keyIv };
  for (const b of blocks) {
    if (b.segments.length >= 10) {
      dominantKey = { method: b.keyMethod, uri: b.keyUri, iv: b.keyIv };
      break;
    }
  }

  return { headerLines, blocks, dominantKey };
}

const blockDuration = (b: ParsedBlock) => b.segments.reduce((a, s) => a + s.duration, 0);

// ---------- MPEG-TS 首个视频 PTS 解析 ----------

/** 解析 MPEG-TS 中第一个带 PTS 的视频帧 PTS（秒）。失败返回 null。 */
export function firstVideoPtsFromTs(buf: ArrayBuffer): number | null {
  const data = new Uint8Array(buf);
  const len = data.length;
  // 必须以 TS 同步字节 0x47 开头（允许前面有少量垃圾）
  let start = -1;
  for (let i = 0; i < Math.min(len, 4096); i++) {
    if (data[i] === 0x47 && (i + 188 >= len || data[i + 188] === 0x47)) {
      start = i;
      break;
    }
  }
  if (start < 0) return null;

  for (let p = start; p + 188 <= len; p += 188) {
    if (data[p] !== 0x47) continue;
    const b1 = data[p + 1];
    const payloadStart = (b1 & 0x40) !== 0; // PUSI
    const pid = ((b1 & 0x1f) << 8) | data[p + 2];
    if (pid === 0x1fff) continue; // null packet
    if (!payloadStart) continue;

    const afc = (data[p + 3] >> 4) & 0x03;
    let off = p + 4;
    if (afc === 2 || afc === 3) {
      const afLen = data[off];
      off += 1 + afLen;
    }
    if (afc === 0 || afc === 2) continue; // 无 payload
    if (off + 9 > p + 188) continue;

    // PES 起始码 00 00 01
    if (!(data[off] === 0x00 && data[off + 1] === 0x00 && data[off + 2] === 0x01)) continue;
    const streamId = data[off + 3];
    // 视频 stream id 范围 0xE0-0xEF
    if (streamId < 0xe0 || streamId > 0xef) continue;

    const flags = data[off + 7];
    const ptsDtsFlag = (flags & 0x80) !== 0;
    if (!ptsDtsFlag) continue;

    const b = off + 9; // PTS 从 PES 头第 9 字节开始
    if (b + 5 > p + 188) continue;
    const pts =
      (data[b] & 0x0e) * 536870912 + // <<29
      (data[b + 1] & 0xff) * 4194304 + // <<22
      (data[b + 2] & 0xfe) * 16384 + // <<14
      (data[b + 3] & 0xff) * 128 + // <<7
      (data[b + 4] & 0xfe) / 2;
    return pts / 90000;
  }
  return null;
}

// 拉取播放列表的超时（ms）。必须远小于播放器(ExoPlayer 默认 ~8s)的连接/读取超时，
// 否则代理迟迟不回包会让播放器判超时而“一直加载”。
const MANIFEST_TIMEOUT = 9000;
// 探测首帧 PTS 只需文件开头若干 KB（PTS 位于首个视频 PES 头，远在 128KB 内）
const PTS_PROBE_BYTES = 128 * 1024;
const PTS_PROBE_TIMEOUT = 6000;

/** 带超时的 fetch；失败/超时/外部中止均返回 null，绝不抛出。 */
async function fetchWithTimeout(
  url: string,
  init: any,
  timeoutMs: number,
  external?: AbortSignal
): Promise<Response | null> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  if (external && typeof (external as any).addEventListener === "function") {
    if (external.aborted) ctrl.abort();
    else (external as any).addEventListener("abort", () => ctrl.abort());
  }
  try {
    return await fetch(url, { ...init, signal: ctrl.signal });
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchText(url: string, signal?: AbortSignal): Promise<string | null> {
  const resp = await fetchWithTimeout(url, {}, MANIFEST_TIMEOUT, signal);
  if (!resp || !resp.ok) return null;
  try {
    return await resp.text();
  } catch {
    return null;
  }
}

/**
 * 只取文件开头 maxBytes 字节（HTTP Range）。
 * 若服务端不支持 Range 会返回 200 全量，此时 arrayBuffer 仍是整段，功能不受影响（只是慢一点）。
 */
async function fetchArrayBufferRange(
  url: string,
  maxBytes: number,
  signal?: AbortSignal
): Promise<ArrayBuffer | null> {
  const resp = await fetchWithTimeout(
    url,
    { headers: { Range: `bytes=0-${maxBytes - 1}` } },
    PTS_PROBE_TIMEOUT,
    signal
  );
  if (!resp || !resp.ok) return null;
  try {
    return await resp.arrayBuffer();
  } catch {
    return null;
  }
}

/**
 * 尝试获取某块首分片的视频 PTS。
 * 仅处理明文（METHOD=NONE）分片；加密分片无法在不解密的情况下取 PTS -> 返回 null（交由其它信号判定）。
 */
async function probeBlockFirstPts(block: ParsedBlock, signal?: AbortSignal): Promise<number | null> {
  if (block.keyMethod !== "NONE") return null; // 加密块不解密，跳过 PTS 信号
  const first = block.segments[0];
  if (!first) return null;
  // 只取前 128KB（Range），避免下载整段（数百 KB~数 MB）导致响应超时
  const buf = await fetchArrayBufferRange(first.absUri, PTS_PROBE_BYTES, signal);
  if (!buf) return null;
  return firstVideoPtsFromTs(buf);
}

/**
 * 分析播放列表并判定各块是否为广告（就地标记 blocks[].isAd）。
 * 仅处理媒体播放列表（含 EXTINF）；master/失败返回 null。
 */
export async function analyzePlaylist(
  playlistUrl: string,
  opts: AdFilterOptions = {},
  signal?: AbortSignal
): Promise<{ parsed: ParsedPlaylist; anyAd: boolean; baseUrl: string } | null> {
  const text = await fetchText(playlistUrl, signal);
  if (!text) return null;
  return analyzePlaylistText(text, playlistUrl, opts, signal);
}

/** 同 analyzePlaylist，但直接分析已取得的文本（避免重复下载同一列表）。 */
export async function analyzePlaylistText(
  text: string,
  baseUrl: string,
  opts: AdFilterOptions = {},
  signal?: AbortSignal
): Promise<{ parsed: ParsedPlaylist; anyAd: boolean; baseUrl: string } | null> {
  const cfg = { ...DEFAULTS, ...opts };
  if (!/#EXTINF/.test(text)) return null; // master 或非媒体列表

  const parsed = parsePlaylist(text, baseUrl);
  if (parsed.blocks.length < 2) {
    return { parsed, anyAd: false, baseUrl };
  }

  const { blocks, dominantKey } = parsed;

  // 候选块：disc 切出、短块
  const candidates = blocks.filter(
    (b, i) =>
      i > 0 &&
      b.discontinuityBefore &&
      b.segments.length > 0 &&
      b.segments.length <= cfg.maxAdBlockSegments &&
      blockDuration(b) <= cfg.maxAdBlockDuration
  );

  // 需要靠 PTS 判定的候选块（加密状态与正片相同，key 差异判据失效）
  const needPtsCandidates = candidates.filter((b) => b.keyMethod === dominantKey.method);

  // 并行探测：基线(块0) + 各需判定的候选块首帧 PTS，避免串行累加导致响应超时
  const ptsMap = new Map<ParsedBlock, number | null>();
  let baseline = 0;
  if (needPtsCandidates.length > 0) {
    const toProbe = [blocks[0], ...needPtsCandidates.slice(0, cfg.maxCandidatesToCheck)];
    const results = await Promise.all(toProbe.map((b) => probeBlockFirstPts(b, signal)));
    toProbe.forEach((b, idx) => ptsMap.set(b, results[idx]));
    const b0 = ptsMap.get(blocks[0]);
    if (b0 !== null && b0 !== undefined) baseline = b0;
  }

  let contentCum = 0;
  let anyAd = false;
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    const dur = blockDuration(b);

    let isAd = false;
    if (candidates.includes(b)) {
      if (b.keyMethod !== dominantKey.method) {
        // 快路径：加密状态与正片不同 -> 直接判定广告（零下载）
        isAd = true;
      } else {
        // 慢路径：比对首帧 PTS 与累计时间轴（已并行取回）
        const pts = ptsMap.get(b);
        if (pts !== null && pts !== undefined) {
          isAd = Math.abs(pts - (contentCum + baseline)) > cfg.ptsTolerance;
        }
        // 未取到 PTS（超时/失败）-> 保守保留，绝不误删正片
      }
    }

    b.isAd = isAd;
    if (isAd) anyAd = true;
    else contentCum += dur;
  }

  return { parsed, anyAd, baseUrl };
}

/**
 * 拉取并过滤播放列表。始终返回一份“可播放”的媒体列表文本（URI 已绝对化）：
 *   - 有广告 -> 剔除广告块后重建
 *   - 无广告 -> 原样绝对化重建（保证相对路径在代理下可用）
 *   - 失败 -> null（调用方回退原始地址）
 */
export async function fetchAndFilterPlaylist(
  playlistUrl: string,
  opts: AdFilterOptions = {},
  signal?: AbortSignal
): Promise<string | null> {
  const res = await analyzePlaylist(playlistUrl, opts, signal);
  if (!res) return null;
  return rebuildPlaylist(res.parsed, res.baseUrl);
}

export function rebuildPlaylist(parsed: ParsedPlaylist, baseUrl: string): string {
  const { headerLines, blocks, dominantKey } = parsed;
  const out: string[] = ["#EXTM3U"];

  const keep = headerLines.filter(
    (h) =>
      h.startsWith("#EXT-X-VERSION") ||
      h.startsWith("#EXT-X-TARGETDURATION") ||
      h.startsWith("#EXT-X-PLAYLIST-TYPE") ||
      h.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
      h.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
  );
  out.push(...keep);

  // 顶部写一条正片 key（仅加密源）
  if (dominantKey.method === "AES-128" && dominantKey.uri) {
    const absKey = absolutize(dominantKey.uri, baseUrl);
    out.push(`#EXT-X-KEY:METHOD=AES-128,URI="${absKey}",IV=${dominantKey.iv || "0x0"}`);
  }

  // 遍历所有块：剔除广告分片，但在「删除广告块」造成的缺口处保留一条
  // #EXT-X-DISCONTINUITY，让播放器在拼接点重新同步时间戳/解码器，
  // 避免视频解码器在时间轴跳变处卡顿（声音不受影响、画面停顿）。
  let prevSegmentWasDroppedOrDiscontinuity = false;
  let emitted = false;
  for (const b of blocks) {
    if (b.isAd) {
      // 广告块：整块丢弃，标记此处存在时间轴断点
      prevSegmentWasDroppedOrDiscontinuity = true;
      continue;
    }
    // 若上一块是广告(被删)或原始列表在此块前有 DISCONTINUITY，
    // 且已经输出过分片，则在拼接点插入一条 DISCONTINUITY。
    if (emitted && (prevSegmentWasDroppedOrDiscontinuity || b.discontinuityBefore)) {
      out.push("#EXT-X-DISCONTINUITY");
    }
    for (const seg of b.segments) {
      out.push(`#EXTINF:${seg.duration},`);
      out.push(seg.absUri);
    }
    emitted = true;
    prevSegmentWasDroppedOrDiscontinuity = false;
  }

  out.push("#EXT-X-ENDLIST");
  return out.join("\n") + "\n";
}

/**
 * 若给定的是 master 播放列表，返回最高码率子列表的绝对 URL；否则返回 null。
 */
export async function resolveMediaPlaylistUrl(
  playlistUrl: string,
  signal?: AbortSignal
): Promise<string | null> {
  const text = await fetchText(playlistUrl, signal);
  if (!text) return null;
  return pickBestMediaUrl(text, playlistUrl);
}

/**
 * 同步版本：从已取得的 master 文本中解析最高码率子列表的绝对 URL；
 * 若文本已是媒体列表或不是 master，返回 null。
 */
export function pickBestMediaUrl(text: string, baseUrl: string): string | null {
  if (/#EXTINF/.test(text)) return null; // 已经是媒体列表
  if (!/#EXT-X-STREAM-INF/.test(text)) return null;

  const lines = text.split(/\r?\n/);
  let bestBandwidth = -1;
  let bestUri: string | null = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.startsWith("#EXT-X-STREAM-INF")) {
      const m = line.match(/BANDWIDTH=(\d+)/);
      const bw = m ? parseInt(m[1], 10) : 0;
      // 下一非注释行为 URI
      for (let j = i + 1; j < lines.length; j++) {
        const cand = lines[j].trim();
        if (!cand || cand.startsWith("#")) continue;
        if (bw > bestBandwidth) {
          bestBandwidth = bw;
          bestUri = absolutize(cand, baseUrl);
        }
        break;
      }
    }
  }
  return bestUri;
}
