package com.ai.codeplatform.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页抓取工具：给模型提供「看别人的网站、提炼设计特征」的能力。
 *
 * <h2>为什么不用正则去标签</h2>
 * 旧实现用 {@code <[^>]+>} 粗暴剥离标签，有三个硬伤：
 * <ul>
 *   <li>HTML 实体不解码，{@code &nbsp;} / {@code &amp;} / {@code &#x27;} 原样进入 prompt；</li>
 *   <li>正文里出现 {@code a < b} 会被当成标签误删；</li>
 *   <li>属性值里的 {@code >} 会让标签提前截断。</li>
 * </ul>
 * 改用 jsoup（项目早已引入依赖），顺带能拿到 DOM 结构、meta 标签、样式表链接。
 *
 * <h2>为什么要把「设计特征」抽出来</h2>
 * 这个工具的使用场景是「参考这个站的设计风格」，而模型真正需要的不是 8000 字符的
 * 噪声正文，而是标题、描述、主色、字体、区域结构。抽出这些之后单次返回体积从数千字符
 * 降到几百字符，模型看得更准，也顺带减轻了对话记忆的写回压力。
 *
 * <h2>SSRF 防护</h2>
 * 字符串前缀匹配（{@code host.startsWith("10.")}）防不住任何真正的绕过：
 * {@code 169.254.169.254}（云元数据）、{@code [::1]}、{@code 2130706433}（十进制 IP）、
 * {@code 127.0.0.1.nip.io}（DNS 指回本机）都能穿过去。这里改为<b>先解析 DNS，再按
 * InetAddress 的类型判定</b>，并且重定向的每一跳都重新校验 —— 否则一个 302 跳到元数据服务
 * 就绕过了全部检查。
 */
@Slf4j
@Component
public class WebFetchTool extends BaseTool {

    /** 识别成浏览器的 UA。旧版的 AiCodeBot/1.0 会被 Cloudflare、知乎、淘宝等直接 403 */
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** 单页最多读取的字节数，防止一个几百 MB 的响应把堆打爆 */
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

    /** 最多解析几个外部样式表（主色和字体基本都在里面） */
    private static final int MAX_CSS_FILES = 3;

    private static final int MAX_CSS_BYTES = 200 * 1024;

    /** 正文摘要的字符上限（按 codePoint 计，避免切坏 emoji） */
    private static final int MAX_BODY_CHARS = 2500;

    private static final int MAX_COLORS = 8;

    private static final int MAX_FONTS = 6;

    /** 手写重定向循环，每一跳都要重新做地址校验，所以不能用 HttpClient 自带的跟随 */
    private static final int MAX_REDIRECTS = 5;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    /** 同一 appId + URL 的结果缓存时长：模型压缩后可能忘记抓过什么，避免重复抓 */
    private static final int CACHE_MAX_ENTRIES = 64;

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Map<String, CachedPage> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedPage>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    // ------------------------------------------------------------------ 正则

    private static final Pattern CHARSET_IN_CONTENT_TYPE =
            Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern META_CHARSET =
            Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEX_COLOR =
            Pattern.compile("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\\b");

    private static final Pattern FUNC_COLOR =
            Pattern.compile("\\b(rgba?|hsla?)\\([^)]{5,60}\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FONT_FAMILY =
            Pattern.compile("font-family\\s*:\\s*([^;}\"']+)", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------ 入口

    @Tool("获取指定URL的网页内容，用于分析网站的设计风格、布局、配色、字体与页面结构。"
            + "返回标题、描述、主色、字体、区域结构统计与正文摘要。"
            + "注意：只做静态抓取，由 JS 动态渲染的内容可能拿不到，返回结果里会标注。"
            + "同一会话内对同一URL重复调用会命中缓存，可以放心重试。")
    public String fetchWebPage(
            @P("要访问的完整URL，必须以http://或https://开头") String url,
            @ToolMemoryId Long appId
    ) {
        if (StrUtil.isBlank(url)) {
            return "错误：URL不能为空";
        }

        URI target;
        try {
            target = URI.create(url.trim());
        } catch (Exception e) {
            return "错误：URL格式无效 - " + url;
        }
        String scheme = target.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "错误：URL必须以http://或https://开头";
        }
        if (StrUtil.isBlank(target.getHost())) {
            return "错误：URL格式无效，缺少主机名 - " + url;
        }

        long ownerId = appId == null ? 0L : appId;
        String cacheKey = ownerId + "|" + target;

        // 1. 命中缓存直接返回（同一轮对话里模型经常重复抓同一个站）
        CachedPage cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.expireAt > System.currentTimeMillis()) {
                log.debug("网页抓取命中缓存: {}", target);
                return cached.content;
            }
            cache.remove(cacheKey);
        }

        // 2. 抓取
        String content;
        try {
            content = doFetch(target);
        } catch (FetchException e) {
            return e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            return "错误：请求超时（连接 5s / 读取 12s）";
        } catch (java.net.ConnectException e) {
            return "错误：无法连接到服务器，请检查URL是否正确";
        } catch (IOException e) {
            log.warn("获取网页失败: {}", target, e);
            return "错误：获取网页失败 - " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误：请求被中断";
        }

        cache.put(cacheKey, new CachedPage(content, System.currentTimeMillis() + CACHE_TTL_MS));
        return content;
    }

    // ------------------------------------------------------------------ 抓取

    private String doFetch(URI start) throws IOException, InterruptedException, FetchException {
        FetchResponse response = fetchFollowingRedirects(start, MAX_HTML_BYTES, false);

        String rawHtml = new String(response.body, response.charset);
        Document doc = Jsoup.parse(rawHtml, response.finalUri.toString());

        // 1. 先把样式捞出来（内联 style 块），再删掉它们避免污染正文
        //    注意：样式块数量要在 remove() 之前记下来，否则统计永远是 0
        StringBuilder css = new StringBuilder();
        int styleBlockCount = 0;
        for (Element style : doc.select("style")) {
            css.append(style.data()).append('\n');
            styleBlockCount++;
        }

        // 2. 外部样式表：现代站点的色板/字体几乎都在这儿，内联块往往只有几行
        List<String> cssUrls = new ArrayList<>();
        for (Element link : doc.select("link[href]")) {
            if (!link.hasAttr("rel") || !link.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet")) {
                continue;
            }
            String href = link.attr("href").trim();
            if (href.isEmpty() || href.startsWith("data:")) {
                continue;
            }
            try {
                cssUrls.add(response.finalUri.resolve(href).toString());
            } catch (Exception ignored) {
                // 单个坏链接忽略
            }
            if (cssUrls.size() >= MAX_CSS_FILES) {
                break;
            }
        }
        int cssFetched = 0;
        for (String cssUrl : cssUrls) {
            try {
                FetchResponse cssResponse = fetchFollowingRedirects(URI.create(cssUrl), MAX_CSS_BYTES, true);
                css.append(new String(cssResponse.body, cssResponse.charset)).append('\n');
                cssFetched++;
            } catch (Exception e) {
                log.debug("外部样式表抓取失败（忽略）: {} - {}", cssUrl, e.toString());
            }
        }

        // 3. 结构化信息在删标签之前取
        String title = doc.title();
        String description = firstMetaContent(doc, "meta[name=description]", "meta[property=og:description]");
        String lang = doc.selectFirst("html[lang]") != null ? doc.selectFirst("html[lang]").attr("lang") : "";
        StructureStats stats = collectStructure(doc);
        List<String> navItems = collectNavItems(doc);

        // 4. 删掉不可见/无意义节点后取正文（jsoup 的 text() 会自动解码实体、合并空白）
        doc.select("script, style, noscript, svg, canvas, template, iframe").remove();
        String bodyText = doc.body() == null ? "" : doc.body().text();

        // 5. 样式特征
        Map<String, Integer> colors = topColors(css.toString(), MAX_COLORS);
        Map<String, Integer> fonts = topFonts(css.toString(), MAX_FONTS);

        // 6. 拼装
        StringBuilder out = new StringBuilder(1024);
        out.append("[网页信息]\n");
        out.append("URL: ").append(response.finalUri).append('\n');
        if (StrUtil.isNotBlank(title)) {
            out.append("标题: ").append(clip(title, 200)).append('\n');
        }
        if (StrUtil.isNotBlank(description)) {
            out.append("描述: ").append(clip(description, 300)).append('\n');
        }
        if (StrUtil.isNotBlank(lang)) {
            out.append("语言: ").append(lang).append('\n');
        }

        out.append("\n[设计特征]\n");
        out.append("主色: ").append(formatCounts(colors, "（未在样式中找到颜色）")).append('\n');
        out.append("字体: ").append(formatCounts(fonts, "（未在样式中找到字体声明）")).append('\n');
        out.append("样式来源: 内联 style 块 ").append(styleBlockCount)
                .append(" 处；外部样式表 ").append(cssUrls.size()).append(" 个（成功分析 ")
                .append(cssFetched).append(" 个）").append('\n');

        out.append("\n[页面结构]\n");
        out.append("区域: ").append(stats.regions()).append('\n');
        out.append("元素: ").append(stats.elements()).append('\n');
        if (!navItems.isEmpty()) {
            out.append("导航项: ").append(String.join(" / ", navItems)).append('\n');
        }

        out.append("\n[正文]\n");
        int bodyCodePoints = bodyText.codePointCount(0, bodyText.length());
        if (bodyText.isEmpty()) {
            out.append("（空）");
        } else if (bodyCodePoints > MAX_BODY_CHARS) {
            out.append(bodyText, 0, bodyText.offsetByCodePoints(0, MAX_BODY_CHARS))
                    .append("\n...（已截断至 ").append(MAX_BODY_CHARS).append(" 字符）");
        } else {
            out.append(bodyText);
        }

        // 7. SPA 判定：静态 HTML 里几乎没有正文，说明内容靠 JS 渲染
        if (bodyText.length() < 200 && looksLikeCsr(rawHtml)) {
            out.append("\n\n⚠️ 该页面静态HTML中的正文只有 ").append(bodyText.length())
                    .append(" 字符，内容由 JavaScript 动态渲染（Vue/React 等 CSR 站点），"
                            + "静态抓取看不到渲染后的结构与样式。如需参考它的视觉效果，"
                            + "建议直接依据 URL 与品牌猜测描述，不要臆断具体布局。");
        }

        return out.toString();
    }

    /**
     * 手动跟随重定向：每一跳都重新做地址校验。
     * <p>用 {@code Redirect.NORMAL} 让 JDK 自己跟，等于把「重定向到 169.254.169.254」这条
     * SSRF 路径直接开出来 —— 校验只发生在第一次请求上。
     *
     * @param cssOnly true 时只接受 text/css，避免把 HTML 当样式表塞进颜色统计
     */
    private FetchResponse fetchFollowingRedirects(URI start, int maxBytes, boolean cssOnly)
            throws IOException, InterruptedException, FetchException {

        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            assertPublicHost(current.getHost());

            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", cssOnly
                            ? "text/css,*/*;q=0.1"
                            : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();

            // 重定向：读完就关，手动解出 Location 再校验
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (StrUtil.isBlank(location)) {
                    throw new FetchException("错误：服务器返回 " + status + " 但未提供 Location");
                }
                current = current.resolve(location.trim());
                continue;
            }

            if (status != 200) {
                closeQuietly(response.body());
                throw new FetchException(describeStatus(status));
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String typeError = checkContentType(contentType, cssOnly);
            if (typeError != null) {
                closeQuietly(response.body());
                throw new FetchException(typeError);
            }

            byte[] body = readAtMost(response.body(), maxBytes);
            Charset charset = detectCharset(body, contentType);
            return new FetchResponse(current, body, charset);
        }

        throw new FetchException("错误：重定向次数超过 " + MAX_REDIRECTS + " 次");
    }

    /**
     * 只放行文本类响应。旧实现不看 Content-Type，抓 zip / exe / 图片时会把二进制
     * 当字符串塞进 prompt，既浪费 token 又让模型看到乱码。
     */
    private String checkContentType(String contentType, boolean cssOnly) {
        if (StrUtil.isBlank(contentType)) {
            return null;    // 有的老站点不返回 Content-Type，交给 jsoup 兜住
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int semicolon = lower.indexOf(';');
        String mime = (semicolon > 0 ? lower.substring(0, semicolon) : lower).trim();

        if (cssOnly) {
            // 外部样式表链接可能指向一个 HTML 错误页，别把整页 HTML 当 CSS 统计颜色
            return mime.startsWith("text/css") || mime.startsWith("text/plain")
                    ? null
                    : "错误：该链接返回的不是样式表（Content-Type: " + mime + "）";
        }
        if (mime.startsWith("text/") || mime.contains("html") || mime.contains("xml")
                || mime.contains("json")) {
            return null;
        }
        return "错误：该地址返回的不是网页内容（Content-Type: " + mime + "），无法分析";
    }

    private String describeStatus(int status) {
        return switch (status) {
            case 401 -> "错误：目标站点要求登录认证（401）";
            case 403 -> "错误：目标站点拒绝访问（403），可能有反爬限制，换一个站点试试";
            case 404 -> "错误：页面不存在（404）";
            case 429 -> "错误：目标站点限流（429），稍后再试";
            default -> "错误：服务器返回状态码 " + status;
        };
    }

    // ------------------------------------------------------------------ SSRF

    /**
     * 解析域名后逐个校验地址，任一命中内网/保留段就拒绝。
     * <p>这样写法能覆盖 {@code 127.0.0.1.nip.io}（域名指回本机）、
     * {@code 2130706433}（十进制 IP）、{@code [::1]} 这类字符串匹配防不住的输入。
     */
    private void assertPublicHost(String host) throws FetchException {
        if (StrUtil.isBlank(host)) {
            throw new FetchException("错误：URL 缺少主机名");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new FetchException("错误：域名无法解析 - " + host);
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw new FetchException("错误：不允许访问内网或保留地址（" + address.getHostAddress() + "）");
            }
        }
    }

    private boolean isForbiddenAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] ip = address.getAddress();
        if (ip.length == 4) {
            return isForbiddenIpv4(ip, 0);
        }
        if (ip.length == 16) {
            // IPv4-mapped（::ffff:a.b.c.d）—— isLoopbackAddress 未必认得出来
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (ip[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (ip[10] & 0xFF) == 0xFF && (ip[11] & 0xFF) == 0xFF) {
                return isForbiddenIpv4(ip, 12);
            }
            // fc00::/7 唯一本地地址
            if ((ip[0] & 0xFE) == 0xFC) {
                return true;
            }
            // 2001:db8::/32 文档示例段
            return (ip[0] & 0xFF) == 0x20 && (ip[1] & 0xFF) == 0x01
                    && (ip[2] & 0xFF) == 0x0D && (ip[3] & 0xFF) == 0xB8;
        }
        return true;
    }

    /**
     * isSiteLocalAddress / isLinkLocalAddress 已覆盖 10/8、172.16/12、192.168/16、169.254/16，
     * 这里补它漏掉的几段。
     */
    private boolean isForbiddenIpv4(byte[] ip, int offset) {
        int b0 = ip[offset] & 0xFF;
        int b1 = ip[offset + 1] & 0xFF;
        int b2 = ip[offset + 2] & 0xFF;

        if (b0 == 0) {
            return true;                                    // 0.0.0.0/8
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return true;                                    // 100.64/10 运营商级 NAT
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return true;                                    // 192.0.0.0/24
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return true;                                    // 198.18/15 基准测试段
        }
        if (b0 >= 240) {
            return true;                                    // 240/4 保留 + 广播
        }
        return false;
    }

    // ------------------------------------------------------------------ 工具方法

    /** 有上限地读取响应体，超出部分直接丢弃（旧实现会把整个 body 读进内存） */
    private byte[] readAtMost(InputStream in, int maxBytes) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while (total < maxBytes && (read = stream.read(buffer)) != -1) {
                int readable = Math.min(read, maxBytes - total);
                out.write(buffer, 0, readable);
                total += readable;
            }
            return out.toByteArray();
        }
    }

    /**
     * 编码探测：Content-Type 的 charset → {@code <meta charset>} → UTF-8。
     * <p>旧实现写死 UTF-8，GBK 的中文站点整页乱码。
     */
    private Charset detectCharset(byte[] body, String contentType) {
        String declared = null;
        if (StrUtil.isNotBlank(contentType)) {
            Matcher matcher = CHARSET_IN_CONTENT_TYPE.matcher(contentType);
            if (matcher.find()) {
                declared = matcher.group(1);
            }
        }
        if (declared == null && body.length > 0) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher matcher = META_CHARSET.matcher(head);
            if (matcher.find()) {
                declared = matcher.group(1);
            }
        }
        if (declared != null) {
            try {
                return Charset.forName(declared.trim());
            } catch (Exception ignored) {
                log.debug("不支持的字符集: {}，回退 UTF-8", declared);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String firstMetaContent(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element element = doc.selectFirst(selector);
            if (element != null && StrUtil.isNotBlank(element.attr("content"))) {
                return element.attr("content").trim();
            }
        }
        return "";
    }

    private StructureStats collectStructure(Document doc) {
        return new StructureStats(
                doc.select("header").size(), doc.select("nav").size(),
                doc.select("main").size(), doc.select("section").size(),
                doc.select("article").size(), doc.select("aside").size(),
                doc.select("footer").size(),
                doc.select("h1").size(), doc.select("h2").size(), doc.select("h3").size(),
                doc.select("img").size(), doc.select("button").size(),
                doc.select("a[href]").size(), doc.select("form").size());
    }

    private List<String> collectNavItems(Document doc) {
        Set<String> items = new LinkedHashSet<>();
        for (Element link : doc.select("nav a")) {
            String text = link.text().trim();
            if (!text.isEmpty() && text.length() <= 20) {
                items.add(text);
            }
            if (items.size() >= 8) {
                break;
            }
        }
        if (items.isEmpty()) {
            for (Element link : doc.select("header a")) {
                String text = link.text().trim();
                if (!text.isEmpty() && text.length() <= 20) {
                    items.add(text);
                }
                if (items.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(items);
    }

    /** 统计样式里的十六进制与 rgb/hsl 颜色，按出现次数取前 N 个 */
    private Map<String, Integer> topColors(String css, int limit) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        Matcher hexMatcher = HEX_COLOR.matcher(css);
        while (hexMatcher.find()) {
            String color = normalizeHex(hexMatcher.group(1));
            if (!isTransparent(color)) {
                increment(counter, color);
            }
        }
        Matcher funcMatcher = FUNC_COLOR.matcher(css);
        while (funcMatcher.find()) {
            String color = funcMatcher.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (!isTransparent(color)) {
                increment(counter, color);
            }
        }
        return topN(counter, limit);
    }

    /**
     * 过滤全透明色。{@code #00000000} / {@code rgba(0,0,0,0)} 在 CSS 里通常是
     * 清空占位或渐变哨兵，出现频次极高但没有任何设计参考价值 ——
     * 实测 Vue 官网它排到主色第一，会把真正的品牌色挤下去。
     */
    private boolean isTransparent(String color) {
        if (color.startsWith("#")) {
            // normalizeHex 已把 4 位统一扩成 8 位，alpha 是末尾两位
            return color.length() == 9 && color.endsWith("00");
        }
        return color.endsWith(",0)") || color.endsWith(",0.0)") || color.endsWith(",0.00)");
    }

    private String normalizeHex(String raw) {
        String hex = raw.toLowerCase(Locale.ROOT);
        if (hex.length() == 3 || hex.length() == 4) {
            StringBuilder expanded = new StringBuilder("#");
            for (char c : hex.toCharArray()) {
                expanded.append(c).append(c);
            }
            return expanded.toString();
        }
        return "#" + hex;
    }

    /** 统计 font-family 声明里的首个字体名（后备字体各家都写 system-ui，参考价值低） */
    private Map<String, Integer> topFonts(String css, int limit) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        Matcher matcher = FONT_FAMILY.matcher(css);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (value.isEmpty()) {
                continue;
            }
            String first = value.split(",")[0].trim()
                    .replace("\"", "").replace("'", "");
            if (!first.isEmpty() && !first.startsWith("var(")) {
                increment(counter, first);
            }
        }
        return topN(counter, limit);
    }

    private void increment(Map<String, Integer> counter, String key) {
        if (StrUtil.isBlank(key)) {
            return;
        }
        counter.merge(key, 1, Integer::sum);
    }

    private Map<String, Integer> topN(Map<String, Integer> counter, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counter.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            result.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return result;
    }

    private String formatCounts(Map<String, Integer> counts, String emptyText) {
        if (counts.isEmpty()) {
            return emptyText;
        }
        List<String> parts = new ArrayList<>(counts.size());
        counts.forEach((key, value) -> parts.add(key + " ×" + value));
        return String.join(", ", parts);
    }

    private String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        return codePoints > maxChars
                ? trimmed.substring(0, trimmed.offsetByCodePoints(0, maxChars)) + "…"
                : trimmed;
    }

    /**
     * 判断是不是「内容靠 JS 渲染」的站点：静态 HTML 里几乎没有正文，
     * 且有框架挂载点或 ESM 入口。
     */
    private boolean looksLikeCsr(String rawHtml) {
        if (StrUtil.isBlank(rawHtml)) {
            return false;
        }
        String lower = rawHtml.toLowerCase(Locale.ROOT);
        return lower.contains("id=\"app\"") || lower.contains("id='app'")
                || lower.contains("id=\"root\"") || lower.contains("id='root'")
                || lower.contains("__next_data__") || lower.contains("__nuxt__")
                || lower.contains("type=\"module\"") || lower.contains("type='module'");
    }

    private void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }

    // ------------------------------------------------------------------ BaseTool

    @Override
    public String getToolName() {
        return "fetchWebPage";
    }

    @Override
    public String getDisplayName() {
        return "获取网页内容";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String url = arguments.getStr("url");
        return String.format("\n\n🌐[工具调用] 获取网页内容 %s\n\n", url);
    }

    // ------------------------------------------------------------------ 内部类型

    private record FetchResponse(URI finalUri, byte[] body, Charset charset) {
    }

    private record CachedPage(String content, long expireAt) {
    }

    /** 抓取过程的业务失败：消息直接作为工具结果返回给模型，不打印堆栈 */
    private static class FetchException extends Exception {
        FetchException(String message) {
            super(message);
        }
    }

    private record StructureStats(int header, int nav, int main, int section, int article,
                                  int aside, int footer, int h1, int h2, int h3,
                                  int img, int button, int link, int form) {

        String regions() {
            List<String> parts = new ArrayList<>();
            appendIfPresent(parts, "header", header);
            appendIfPresent(parts, "nav", nav);
            appendIfPresent(parts, "main", main);
            appendIfPresent(parts, "section", section);
            appendIfPresent(parts, "article", article);
            appendIfPresent(parts, "aside", aside);
            appendIfPresent(parts, "footer", footer);
            return parts.isEmpty() ? "（未识别到语义化区域标签）" : String.join(", ", parts);
        }

        String elements() {
            List<String> parts = new ArrayList<>();
            appendIfPresent(parts, "h1", h1);
            appendIfPresent(parts, "h2", h2);
            appendIfPresent(parts, "h3", h3);
            appendIfPresent(parts, "img", img);
            appendIfPresent(parts, "button", button);
            appendIfPresent(parts, "a", link);
            appendIfPresent(parts, "form", form);
            return parts.isEmpty() ? "（无）" : String.join(", ", parts);
        }

        private static void appendIfPresent(List<String> parts, String name, int count) {
            if (count > 0) {
                parts.add(name + "×" + count);
            }
        }
    }
}
