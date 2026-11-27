/* ============================================================================
 * SubConverter 规则配置 → Sub-Store 桥接脚本
 *
 * 两种用法（粘贴本文件全部内容）：
 *   1. 文件：新建「文件」→「脚本操作」→ 访问 /api/file/<文件名>?$options=...
 *   2. 组合订阅：在目标组合订阅上添加「修改响应」脚本 → 访问
 *      /download/collection/<组合名>/<平台>?$options=...
 * 最简用法：全部放在 URL query 上，不需要 $options：
 *
 *   /download/collection/<组合名>/<平台>?target=ClashMeta&url=<rule.ini 地址>
 *
 * <组合名> 会作为节点源（sub），target 决定输出平台。也可以继续用 $options：
 *
 *   ?$options={"url":"<rule.ini URL>","target":"mihomo","sub":"我的订阅"}
 *   ?$options=url=<encodeURIComponent(URL)>&target=qx&sub=我的订阅
 *
 * 参数（query 优先，其次 $options）：
 *   url / ini / src   必填，SubConverter .ini 地址
 *   target / t          mihomo(默认) | qx / quanx | sing-box / singbox
 *                       省略时自动读取 URL 上的 target 参数（ClashMeta/QX/sing-box 等）
 *   sub / subscription 可选，订阅名或组合订阅名；省略时自动取 URL 路径里的 <组合名>
 *                       用于解析正则组成员并把节点写入配置
 *   collection         可选，组合订阅名（sub 找不到订阅时自动尝试同名组合）
 *   proxyUrl           可选，原始订阅地址（代替 sub）
 *   noCache            可选，跳过缓存
 *   includeUnsupportedProxy  可选，true=包含被平台过滤器排除的节点（如 anytls+reality）
 *   remote             可选，true=远程引用规则集（默认拉取后内联）
 *   geoipTag / geositeTag  sing-box 的 geo 规则集 tag，默认 geoip-cn / geosite-cn
 *   timeout            可选，拉取超时毫秒，默认 15000
 *
 * 注意：这是「快捷脚本」，不要包成 function operator()，也不要在末尾 return。
 * ========================================================================== */

const SC_TAG = '[SUBCVT-RULES]';
const scInfo = (m) => console.log(`${SC_TAG} INFO: ${m}`);
const scWarn = (m) => console.log(`${SC_TAG} WARN: ${m}`);
const scError = (m) => console.log(`${SC_TAG} ERROR: ${m}`);

function scPick(o, keys, fallback) {
    for (const k of keys) {
        if (o[k] !== undefined && o[k] !== null && o[k] !== '') return o[k];
    }
    return fallback;
}

function scBool(v) {
    return v === true || v === 'true' || v === 1 || v === '1';
}

function scNormalizeOptions(raw) {
    if (!raw) return {};
    if (typeof raw === 'object') return raw;
    let s = String(raw).trim();
    if (!s) return {};
    if (s.charAt(0) === '{') {
        try {
            const parsed = JSON.parse(s);
            if (parsed && typeof parsed === 'object') return parsed;
        } catch (e) {
            scWarn(`$options 不是合法 JSON，按 k=v&k=v 解析：${e.message}`);
        }
    }
    const out = {};
    for (const part of s.split('&')) {
        const i = part.indexOf('=');
        if (i < 0) continue;
        try {
            out[decodeURIComponent(part.slice(0, i))] = decodeURIComponent(part.slice(i + 1));
        } catch (e) { /* ignore malformed pair */ }
    }
    return out;
}

function scDecodeURIComponent(s) {
    try {
        return decodeURIComponent(s);
    } catch (e) {
        return s;
    }
}

function scParseOptions() {
    let o = scNormalizeOptions(typeof $options !== 'undefined' ? $options : {});
    const req = o._req || {};
    const query = req.query || {};
    // 兼容 Sub-Store 对 $options 解码失败的情况：从 _req.query.$options 原始串再解析一次
    if (!scPick(o, ['url', 'ini', 'src', 'u'], undefined) && !scPick(query, ['url', 'ini', 'src'], undefined) && query.$options) {
        const raw = query.$options;
        let extra = null;
        if (typeof raw === 'object') {
            extra = raw;
        } else {
            for (const candidate of [String(raw), scDecodeURIComponent(String(raw))]) {
                try {
                    extra = JSON.parse(candidate);
                    break;
                } catch (e) { /* try next */ }
            }
            if (!extra) extra = scNormalizeOptions(String(raw));
        }
        if (extra && typeof extra === 'object') o = { ...o, ...extra };
    }
    // url 优先从 query 读取：?url=<rule.ini 地址>&target=<平台>
    const realQuery = (o._req && o._req.query) || query;
    const url = scPick(realQuery, ['url', 'ini', 'src'], undefined)
        || scPick(o, ['url', 'ini', 'src', 'u'], undefined);
    if (!url || typeof url !== 'string') {
        throw new Error('缺少 $options.url（SubConverter .ini 地址）');
    }
    // 优先用 query 上的 target 参数（与 url 一致）；没传再用 $options.target
    const rawTarget = String(
        scPick(realQuery, ['target', 't', 'platform'], undefined)
            || scPick(o, ['target', 't', 'platform'], 'mihomo'),
    ).toLowerCase();
    const targetMap = {
        mihomo: 'mihomo', clash: 'mihomo', clashmeta: 'mihomo', meta: 'mihomo',
        stash: 'mihomo',
        qx: 'qx', quanx: 'qx', quantumultx: 'qx',
        'sing-box': 'singbox', singbox: 'singbox', sing_box: 'singbox',
    };
    const target = targetMap[rawTarget];
    if (!target) {
        throw new Error(`不支持的 target: ${rawTarget}（可选 mihomo / qx / sing-box）`);
    }
    // sub 自动取 URL 路径里的组合订阅/订阅名（/download/collection/<name>/...）
    const sub = scPick(realQuery, ['sub', 'subscription'], undefined)
        || scPick(o, ['sub', 'subscription', 's'], undefined)
        || (req.params && req.params.name)
        || undefined;
    return {
        url: url.trim(),
        target,
        sub,
        collection: scPick(o, ['collection', 'col'], undefined),
        proxyUrl: scPick(o, ['proxyUrl', 'proxyURL', 'nodeUrl'], undefined),
        noCache: scBool(scPick(o, ['noCache', 'nocache', 'no-cache'], false)),
        includeUnsupportedProxy: scBool(scPick(
            o,
            ['includeUnsupportedProxy', 'include-unsupported-proxy'],
            scBool(scPick(realQuery, ['includeUnsupportedProxy', 'include-unsupported-proxy'], false)),
        )),
        remote: scBool(scPick(o, ['remote'], false)),
        geoipTag: String(scPick(o, ['geoipTag'], 'geoip-cn')),
        geositeTag: String(scPick(o, ['geositeTag'], 'geosite-cn')),
        timeout: Number(scPick(o, ['timeout'], 15000)) || 15000,
    };
}

const SC_CACHE = (typeof scriptResourceCache !== 'undefined') ? scriptResourceCache : null;

function scCacheKey(url) {
    return `subcvt-rules:${url}`;
}

async function scFetchText(url, ttlSec, opt) {
    if (!opt.noCache && SC_CACHE) {
        const hit = SC_CACHE.get(scCacheKey(url));
        if (typeof hit === 'string') return hit;
    }
    const res = await $substore.http.get({ url, timeout: opt.timeout });
    const status = res && res.statusCode;
    if (status && status >= 400) {
        throw new Error(`拉取 ${url} 失败：HTTP ${status}`);
    }
    const body = res && res.body != null ? String(res.body) : '';
    if (!body.trim()) throw new Error(`拉取 ${url} 得到空内容`);
    if (SC_CACHE && ttlSec > 0 && !opt.noCache) {
        SC_CACHE.set(scCacheKey(url), body, ttlSec * 1000);
    }
    return body;
}

function scSplitLines(text) {
    let s = String(text);
    if (/^payload\s*:/m.test(s)) {
        s = s.split(/\r?\n/).filter((l) => !/^payload\s*:/.test(l.trim())).join('\n');
    }
    return s.split(/\r?\n/)
        .map((l) => l.replace(/^\s*-\s*/, '').trim())
        .filter((l) => l && !l.startsWith('#') && !l.startsWith(';'));
}

function scParseRuleset(value) {
    const i = value.indexOf(',');
    if (i < 0) {
        scWarn(`忽略无法解析的 ruleset: ${value}`);
        return null;
    }
    const group = value.slice(0, i).trim();
    let rest = value.slice(i + 1).trim();
    if (!group || !rest) return null;
    let interval = 86400;
    let inline = false;
    if (rest.startsWith('[]')) {
        inline = true;
        rest = rest.slice(2).trim();
    } else {
        const j = rest.lastIndexOf(',');
        if (j >= 0) {
            const tail = rest.slice(j + 1).trim();
            if (/^\d+$/.test(tail)) {
                interval = parseInt(tail, 10);
                rest = rest.slice(0, j).trim();
            }
        }
    }
    return { group, value: rest, interval, inline };
}

function scParseGroup(value) {
    const v = value.split('`');
    if (v.length < 3) {
        scWarn(`忽略无法解析的 custom_proxy_group: ${value}`);
        return null;
    }
    const name = v[0].trim();
    const type = v[1].trim().toLowerCase();
    if (!name) return null;
    let members;
    let testUrl = '';
    let interval = 0;
    let timeout = 0;
    let tolerance = 0;
    if (['url-test', 'fallback', 'load-balance'].includes(type)) {
        if (v.length < 5) {
            scWarn(`组 ${name} 缺少测速地址/时间参数，按 select 处理`);
            members = v.slice(2);
        } else {
            const times = v[v.length - 1].split(',').map((x) => x.trim());
            interval = parseInt(times[0], 10) || 0;
            timeout = parseInt(times[1], 10) || 0;
            tolerance = parseInt(times[2], 10) || 0;
            testUrl = v[v.length - 2].trim();
            members = v.slice(2, v.length - 2);
        }
    } else {
        members = v.slice(2);
    }
    return { name, type, members, testUrl, interval, timeout, tolerance };
}

function scParseIni(text) {
    const rulesets = [];
    const groups = [];
    for (const raw of String(text).split(/\r?\n/)) {
        const line = raw.trim();
        if (!line || line.startsWith(';') || line.startsWith('#')) continue;
        if (/^\[.*\]$/.test(line)) continue;
        const eq = line.indexOf('=');
        if (eq < 0) continue;
        const key = line.slice(0, eq).trim().toLowerCase();
        const value = line.slice(eq + 1).trim();
        if (key === 'ruleset') {
            const rs = scParseRuleset(value);
            if (rs) rulesets.push(rs);
        } else if (key === 'custom_proxy_group') {
            const g = scParseGroup(value);
            if (g) groups.push(g);
        }
    }
    return { rulesets, groups };
}

function scStripRulesetScheme(value) {
    return value.replace(/^(?:clash-domain|clash-ipcidr|clash-classic|surge|quanx):/i, '');
}

async function scLoadRulesets(rulesets, opt) {
    const out = [];
    for (const rs of rulesets) {
        if (rs.inline) {
            out.push({ group: rs.group, interval: rs.interval, inline: true, lines: scSplitLines(rs.value) });
            continue;
        }
        const url = scStripRulesetScheme(rs.value);
        if (opt.remote) {
            out.push({ group: rs.group, interval: rs.interval, inline: false, remote: true, url });
            continue;
        }
        const text = await scFetchText(url, rs.interval, opt);
        out.push({ group: rs.group, interval: rs.interval, inline: false, remote: false, url, lines: scSplitLines(text) });
    }
    return out;
}

async function scGetProxies(opt, platform) {
    // sub 可能是单条订阅名，也可能是组合订阅名，两者都试
    const candidates = [];
    if (opt.sub) {
        candidates.push({ type: 'subscription', name: opt.sub });
        candidates.push({ type: 'collection', name: opt.sub });
    }
    if (opt.collection) {
        candidates.push({ type: 'collection', name: opt.collection });
    }
    for (const c of candidates) {
        try {
            const list = await produceArtifact({
                type: c.type,
                name: c.name,
                platform,
                produceType: 'internal',
                produceOpts: { 'include-unsupported-proxy': opt.includeUnsupportedProxy },
            });
            if (Array.isArray(list)) return list;
        } catch (e) {
            scWarn(`${c.type} ${c.name} 取节点失败：${e.message ?? e}`);
        }
    }
    if (opt.proxyUrl) {
        const raw = await scFetchText(opt.proxyUrl, 0, opt);
        const parsed = ProxyUtils.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    }
    return null;
}

// 文件脚本写 $content；作为「修改响应」脚本时写 $res.body
function scSetOutput(text) {
    if (typeof $res !== 'undefined' && $res && typeof $res === 'object') {
        $res.body = text;
    } else {
        $content = text;
    }
}

function scResolveMembers(groups, proxies) {
    const names = proxies
        ? proxies.map((p) => p && (p.name != null ? p.name : p['display-name'])).filter((x) => x != null).map(String)
        : [];
    return groups.map((g) => {
        const resolved = [];
        const seen = new Set();
        for (const raw of g.members) {
            const token = String(raw == null ? '' : raw).trim();
            if (!token) continue;
            if (token.startsWith('!!')) {
                scWarn(`组 ${g.name}：跳过特殊成员 ${token}`);
                continue;
            }
            if (token.startsWith('[]')) {
                const name = token.slice(2).trim();
                if (name && !seen.has(name)) {
                    seen.add(name);
                    resolved.push(name);
                }
                continue;
            }
            if (!names.length) {
                scWarn(`组 ${g.name}：无法解析正则 ${token}（未提供 sub / proxyUrl），已跳过`);
                continue;
            }
            let re;
            try {
                re = new RegExp(token);
            } catch (e) {
                scWarn(`组 ${g.name}：非法正则 ${token}，已跳过`);
                continue;
            }
            let matched = false;
            for (const n of names) {
                if (re.test(n) && !seen.has(n)) {
                    seen.add(n);
                    resolved.push(n);
                    matched = true;
                }
            }
            if (!matched) scWarn(`组 ${g.name}：正则 ${token} 未匹配到任何节点`);
        }
        if (!resolved.length) {
            scWarn(`组 ${g.name}：无可用成员，使用 DIRECT`);
            resolved.push('DIRECT');
        }
        return { ...g, resolved };
    });
}

function scParseRuleLine(line) {
    const p = String(line).split(',').map((x) => x.trim());
    if (p.length < 2 || !p[0] || !p[1]) return null;
    return {
        type: p[0].toUpperCase(),
        content: p[1],
        noResolve: p.slice(2).some((x) => /^no-resolve$/i.test(x)),
    };
}

const SC_MIHOMO_RULE_TYPES = new Set([
    'DOMAIN', 'DOMAIN-SUFFIX', 'DOMAIN-KEYWORD', 'DOMAIN-REGEX',
    'IP-CIDR', 'IP-CIDR6', 'GEOIP', 'GEOSITE', 'IP-ASN',
    'PROCESS-NAME', 'PROCESS-PATH', 'PROCESS-PATH-REGEX', 'USER-AGENT',
    'DST-PORT', 'SRC-PORT', 'SRC-IP-CIDR', 'INBOUND', 'RULE-SET',
    'MATCH', 'FINAL', 'AND', 'OR', 'NOT',
]);

function scMihomoType(t) {
    const map = {
        FINAL: 'MATCH', MATCH: 'MATCH',
        'DEST-PORT': 'DST-PORT', 'IN-PORT': 'SRC-PORT',
        'SRC-IP': 'SRC-IP-CIDR',
    };
    const type = map[t] || t;
    return SC_MIHOMO_RULE_TYPES.has(type) ? type : null;
}

function scQxType(t) {
    const map = {
        DOMAIN: 'HOST', 'DOMAIN-SUFFIX': 'HOST-SUFFIX', 'DOMAIN-KEYWORD': 'HOST-KEYWORD',
        'IP-CIDR6': 'IP6-CIDR', MATCH: 'FINAL', FINAL: 'FINAL',
    };
    return map[t] || null;
}

function scSingboxMatcher(t) {
    const map = {
        DOMAIN: 'domain', 'DOMAIN-SUFFIX': 'domain_suffix', 'DOMAIN-KEYWORD': 'domain_keyword',
        'IP-CIDR': 'ip_cidr', 'IP-CIDR6': 'ip_cidr',
        'PROCESS-NAME': 'process_name', 'PACKAGE-NAME': 'package_name',
        'SRC-IP': 'source_ip_cidr', 'SRC-IP-CIDR': 'source_ip_cidr',
        'DST-PORT': 'port', 'DEST-PORT': 'port',
        'IN-PORT': 'source_port', 'SRC-PORT': 'source_port',
        PROTOCOL: 'protocol', NETWORK: 'network',
    };
    return map[t] || null;
}

function scRuleForTarget(line, target, policy, opt) {
    // []FINAL / []MATCH 是单独一行的兜底规则，没有 content 字段
    const bare = String(line).trim().toUpperCase();
    if (bare === 'FINAL' || bare === 'MATCH') {
        return { final: true, policy };
    }
    const r = scParseRuleLine(line);
    if (!r) return null;
    if (r.type === 'FINAL' || r.type === 'MATCH') {
        return { final: true, policy };
    }
    if (target === 'mihomo') {
        const type = scMihomoType(r.type);
        if (!type) return null; // 如 URL-REGEX，mihomo 不支持，直接丢弃
        return `${type},${r.content},${policy}${r.noResolve ? ',no-resolve' : ''}`;
    }
    if (target === 'qx') {
        const type = scQxType(r.type);
        if (!type) return null;
        return `${type},${r.content},${policy}`;
    }
    const matcher = scSingboxMatcher(r.type);
    if (matcher) {
        const value = ['protocol', 'network'].includes(matcher)
            ? r.content.toLowerCase()
            : r.content;
        return { [matcher]: [value], action: 'route', outbound: policy };
    }
    if (r.type === 'GEOIP' || r.type === 'GEOSITE') {
        const isCn = String(r.content).toLowerCase() === 'cn';
        const prefix = r.type === 'GEOIP' ? opt.geoipTag : opt.geositeTag;
        const tag = isCn ? prefix : `${prefix}-${String(r.content).toLowerCase()}`;
        scWarn(`sing-box：${r.type},${r.content} 转为 rule_set ${tag}（请确保配置里定义了该 rule set）`);
        return { rule_set: [tag], action: 'route', outbound: policy };
    }
    return null;
}

function scRemoteRule(remote, target, opt, idx) {
    const tag = `sc_rs_${idx}`;
    if (target === 'mihomo') {
        return { tag, remote, rule: `RULE-SET,${tag},${remote.group}` };
    }
    if (target === 'qx') {
        return { tag, remote, rule: `${remote.url}, tag=${remote.group}, force-policy=${remote.group}, enabled=true` };
    }
    return { tag, remote, rule: { rule_set: [tag], action: 'route', outbound: remote.group } };
}

function scMihomoGroupType(type) {
    if (type === 'smart') return 'url-test';
    if (type === 'ssid') return 'select';
    return type;
}

function scQxGroupType(type) {
    const map = {
        select: 'static', relay: 'static', ssid: 'ssid',
        'url-test': 'url-latency-benchmark', smart: 'url-latency-benchmark',
        fallback: 'available', 'load-balance': 'round-robin',
    };
    return map[type] || 'static';
}

function scSingboxGroupType(type) {
    if (type === 'select') return 'selector';
    if (['fallback', 'load-balance', 'smart'].includes(type)) {
        scWarn(`sing-box 无 ${type} 类型，降级为 urltest（组 ${type}）`);
    }
    if (['url-test', 'fallback', 'load-balance', 'smart'].includes(type)) return 'urltest';
    if (type !== 'relay' && type !== 'ssid') {
        scWarn(`sing-box 不支持组类型 ${type}，降级为 selector`);
    }
    return 'selector';
}

function scDuration(sec) {
    if (!sec || sec <= 0) return null;
    if (sec % 60 === 0) return `${sec / 60}m`;
    return `${sec}s`;
}

const SC_TEST_URL = 'https://www.gstatic.com/generate_204';

function scEmitMihomo(ctx) {
    const { groups, rules, finalPolicy, proxies, remoteRules, opt } = ctx;
    const out = {};
    if (proxies && proxies.length) {
        try {
            const formatted = ProxyUtils.produce(proxies, 'ClashMeta', undefined, { 'include-unsupported-proxy': opt.includeUnsupportedProxy });
            const parsed = yaml.safeLoad(formatted);
            if (parsed && Array.isArray(parsed.proxies)) out.proxies = parsed.proxies;
        } catch (e) {
            scWarn(`生成 mihomo 节点失败：${e.message ?? e}`);
        }
    }
    if (groups.length) {
        out['proxy-groups'] = groups.map((g) => {
            const o = {
                name: g.name,
                type: scMihomoGroupType(g.type),
                proxies: g.resolved.slice(),
            };
            if (['url-test', 'fallback', 'load-balance', 'smart'].includes(g.type)) {
                o.url = g.testUrl || SC_TEST_URL;
                if (g.interval > 0) o.interval = g.interval;
                if (g.tolerance > 0) o.tolerance = g.tolerance;
                if (g.type === 'load-balance') o.strategy = 'consistent-hashing';
            }
            return o;
        });
    }
    if (remoteRules.length) {
        out['rule-providers'] = {};
        for (const rr of remoteRules) {
            out['rule-providers'][rr.tag] = {
                type: 'http',
                format: 'text',
                behavior: 'classical',
                url: rr.remote.url,
                path: `./ruleset/${rr.tag}.list`,
                interval: rr.remote.interval || 86400,
            };
        }
    }
    const allRules = rules.concat(remoteRules.map((rr) => rr.rule));
    out.rules = allRules.concat([`MATCH,${finalPolicy || 'DIRECT'}`]);
    return yaml.safeDump(out);
}

function scEmitQx(ctx) {
    const { groups, rules, finalPolicy, proxies, remoteRules, opt } = ctx;
    const lines = [];
    if (proxies && proxies.length) {
        try {
            lines.push('[server_local]');
            lines.push(String(ProxyUtils.produce(proxies, 'QX', undefined, { 'include-unsupported-proxy': opt.includeUnsupportedProxy })).trim());
            lines.push('');
        } catch (e) {
            scWarn(`生成 QX 节点失败：${e.message ?? e}`);
        }
    }
    if (groups.length) {
        lines.push('[policy]');
        for (const g of groups) {
            let s = `${scQxGroupType(g.type)}=${g.name}, ${g.resolved.join(', ')}`;
            if (['url-test', 'fallback', 'load-balance', 'smart'].includes(g.type)) {
                if (g.interval > 0) s += `, check-interval=${g.interval}`;
                if (g.tolerance > 0) s += `, tolerance=${g.tolerance}`;
            }
            lines.push(s);
        }
        lines.push('');
    }
    lines.push('[filter_local]');
    lines.push(...rules);
    lines.push(`FINAL,${finalPolicy || 'DIRECT'}`);
    if (remoteRules.length) {
        lines.push('');
        lines.push('[filter_remote]');
        lines.push(...remoteRules.map((rr) => rr.rule));
    }
    return lines.join('\n');
}

function scEmitSingbox(ctx) {
    const { groups, rules, finalPolicy, proxies, remoteRules, opt } = ctx;
    let base = {};
    if (proxies && proxies.length) {
        try {
            base = JSON.parse(String(ProxyUtils.produce(proxies, 'sing-box', undefined, { 'include-unsupported-proxy': opt.includeUnsupportedProxy })));
        } catch (e) {
            scWarn(`生成 sing-box 节点失败：${e.message ?? e}`);
        }
    }
    const outbounds = Array.isArray(base.outbounds) ? base.outbounds.slice() : [];
    for (const g of groups) {
        const o = {
            type: scSingboxGroupType(g.type),
            tag: g.name,
            outbounds: g.resolved.slice(),
        };
        if (o.type === 'urltest') {
            o.url = g.testUrl || SC_TEST_URL;
            const d = scDuration(g.interval);
            if (d) o.interval = d;
            if (g.tolerance > 0) o.tolerance = g.tolerance;
        }
        outbounds.push(o);
    }
    const route = base.route && typeof base.route === 'object' ? { ...base.route } : {};
    if (remoteRules.length) {
        route.rule_set = (Array.isArray(route.rule_set) ? route.rule_set : []).concat(
            remoteRules.map((rr) => ({
                tag: rr.tag,
                type: 'remote',
                format: 'source',
                url: rr.remote.url,
                update_interval: scDuration(rr.remote.interval) || '24h',
            })),
        );
    }
    const allRules = rules.concat(remoteRules.map((rr) => rr.rule));
    if (allRules.length) {
        route.rules = (Array.isArray(route.rules) ? route.rules : []).concat(allRules);
    }
    route.final = finalPolicy || 'direct';
    return JSON.stringify({ ...base, outbounds, route }, null, 2);
}

async function scMain() {
    const opt = scParseOptions();
    const iniText = await scFetchText(opt.url, 86400, opt);
    const parsed = scParseIni(iniText);
    scInfo(`解析到 ${parsed.rulesets.length} 个 ruleset、${parsed.groups.length} 个 proxy group`);

    const loaded = await scLoadRulesets(parsed.rulesets, opt);
    const platform = opt.target === 'mihomo' ? 'ClashMeta' : opt.target === 'qx' ? 'QX' : 'sing-box';
    const proxies = await scGetProxies(opt, platform);
    const groups = scResolveMembers(parsed.groups, proxies);

    const rules = [];
    const seenRules = new Set();
    let finalPolicy = '';
    let dropped = 0;
    for (const rs of loaded) {
        if (rs.remote) continue;
        for (const line of rs.lines) {
            const normalized = scRuleForTarget(line, opt.target, rs.group, opt);
            if (normalized == null) {
                dropped++;
                continue;
            }
            if (normalized.final) {
                if (!finalPolicy) finalPolicy = normalized.policy;
                continue;
            }
            const key = typeof normalized === 'string' ? normalized : JSON.stringify(normalized);
            if (!seenRules.has(key)) {
                seenRules.add(key);
                rules.push(normalized);
            }
        }
    }
    const remoteRules = [];
    if (opt.remote) {
        loaded.filter((rs) => rs.remote).forEach((rs, i) => remoteRules.push(scRemoteRule(rs, opt.target, opt, i)));
    }

    const ctx = { groups, rules, finalPolicy, proxies, remoteRules, opt };
    if (opt.target === 'mihomo') scSetOutput(scEmitMihomo(ctx));
    else if (opt.target === 'qx') scSetOutput(scEmitQx(ctx));
    else scSetOutput(scEmitSingbox(ctx));

    scInfo(`输出 ${rules.length} 条规则、${remoteRules.length} 个远程规则集、${groups.length} 个组；丢弃 ${dropped} 条不支持规则`);
}

try {
    await scMain();
} catch (e) {
    const msg = `${SC_TAG} 执行失败: ${e && (e.stack || e.message) ? (e.stack || e.message) : e}`;
    scError(msg);
    scSetOutput(`# ${msg.replace(/\n/g, '\n# ')}`);
}
