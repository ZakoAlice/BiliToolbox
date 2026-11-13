package io.github.zakoalice.bilitoolbox.download;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 哔哩哔哩 playurl 接口的最小实现 —— 浏览器扩展里那套逻辑搬到 App 里。
 *
 * <p>两条路：
 * <ul>
 *   <li>{@code platform=html5&high_quality=1&fnval=0} 是「音画合一」的老接口，直接给一个
 *       mp4 的 durl，下载完就能播，代价是最多 720P；</li>
 *   <li>{@code fnval=4048} 是 DASH，音视频分开，画质能到 4K/8K，但要自己合并。</li>
 * </ul>
 *
 * <p>高清需要登录态。这里能拿到的 cookie 来自宿主 WebView 的 CookieManager，拿不到就匿名
 * 请求 —— 匿名一般只给到 480P/720P，这是服务端策略，模块绕不过去。
 */
public final class BiliApi {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String REFERER = "https://www.bilibili.com/";

    /** qn -> 中文名。顺序即展示顺序。 */
    private static final Map<Integer, String> QN_NAME = new LinkedHashMap<>();

    static {
        QN_NAME.put(127, "8K 超高清");
        QN_NAME.put(126, "杜比视界");
        QN_NAME.put(125, "HDR 真彩");
        QN_NAME.put(120, "4K 超清");
        QN_NAME.put(116, "1080P 60帧");
        QN_NAME.put(112, "1080P 高码率");
        QN_NAME.put(80, "1080P");
        QN_NAME.put(74, "720P 60帧");
        QN_NAME.put(64, "720P");
        QN_NAME.put(32, "480P");
        QN_NAME.put(16, "360P");
    }

    private BiliApi() {
    }

    public static String qualityName(int qn) {
        String name = QN_NAME.get(qn);
        return name != null ? name : qn + "P";
    }

    // ------------------------------------------------------------------ 数据类

    public static final class Meta {
        public long aid;
        public long cid;
        public String bvid = "";
        public String title = "";
        public String part = "";
    }

    public static final class Stream {
        public int qn;
        public String label = "";
        public String url;
        public long bandwidth;
        public int width;
        public int height;
        public String codecs = "";

        public String detail() {
            StringBuilder sb = new StringBuilder();
            if (width > 0 && height > 0) {
                sb.append(width).append('x').append(height);
            }
            if (bandwidth > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(bandwidth / 1000).append(" kbps");
            }
            if (codecs != null && codecs.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(codecs);
            }
            return sb.toString();
        }
    }

    public static final class Dash {
        public final List<Stream> videos = new ArrayList<>();
        public final List<Stream> audios = new ArrayList<>();

        public Stream bestAudio() {
            Stream best = null;
            for (Stream s : audios) {
                if (best == null || s.bandwidth > best.bandwidth) {
                    best = s;
                }
            }
            return best;
        }
    }

    public static final class FastMp4 {
        public String url;
        public int quality;
        public long size;
    }

    // ------------------------------------------------------------------ 接口

    /** 拿视频基本信息（标题 + cid）。aid / bvid 传一个就行。 */
    public static Meta meta(Context context, long aid, String bvid) throws IOException {
        String url;
        if (bvid != null && bvid.length() > 0) {
            url = "https://api.bilibili.com/x/web-interface/view?bvid=" + URLEncoder.encode(bvid, "UTF-8");
        } else {
            url = "https://api.bilibili.com/x/web-interface/view?aid=" + aid;
        }
        String body = get(context, url);
        try {
            JSONObject root = new JSONObject(body);
            checkCode(root, "view");
            JSONObject data = root.getJSONObject("data");
            Meta meta = new Meta();
            meta.aid = data.optLong("aid", aid);
            meta.cid = data.optLong("cid", 0);
            meta.bvid = data.optString("bvid", bvid == null ? "" : bvid);
            meta.title = data.optString("title", "");
            JSONArray pages = data.optJSONArray("pages");
            if (pages != null && pages.length() > 0) {
                JSONObject first = pages.optJSONObject(0);
                if (first != null) {
                    meta.part = first.optString("part", "");
                    if (meta.cid == 0) {
                        meta.cid = first.optLong("cid", 0);
                    }
                }
            }
            if (meta.aid == 0) {
                meta.aid = aid;
            }
            return meta;
        } catch (Exception e) {
            throw new IOException("解析视频信息失败：" + e, e);
        }
    }

    /** 音画合一 MP4（最高 720P）。 */
    public static FastMp4 fastMp4(Context context, long aid, long cid) throws IOException {
        String url = "https://api.bilibili.com/x/player/playurl?avid=" + aid
                + "&cid=" + cid
                + "&qn=80&fnval=0&platform=html5&high_quality=1";
        String body = get(context, url);
        try {
            JSONObject root = new JSONObject(body);
            checkCode(root, "playurl(mp4)");
            JSONObject data = root.getJSONObject("data");
            JSONArray durl = data.optJSONArray("durl");
            if (durl == null || durl.length() == 0) {
                throw new IOException("这个视频没有合成 MP4（可能是会员/充电专属）");
            }
            JSONObject first = durl.getJSONObject(0);
            FastMp4 out = new FastMp4();
            out.url = first.optString("url", "");
            out.size = first.optLong("size", 0);
            out.quality = data.optInt("quality", 64);
            if (out.url == null || out.url.length() == 0) {
                throw new IOException("接口没返回地址");
            }
            // 有些版本返回相对地址
            if (out.url.startsWith("//")) {
                out.url = "https:" + out.url;
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析 playurl 失败：" + e, e);
        }
    }

    /** DASH 流列表。 */
    public static Dash dash(Context context, long aid, long cid) throws IOException {
        String url = "https://api.bilibili.com/x/player/playurl?avid=" + aid
                + "&cid=" + cid
                + "&qn=127&fnval=4048&fourk=1&platform=pc";
        String body = get(context, url);
        try {
            JSONObject root = new JSONObject(body);
            checkCode(root, "playurl(dash)");
            JSONObject data = root.getJSONObject("data");
            JSONObject dash = data.optJSONObject("dash");
            if (dash == null) {
                throw new IOException("这个视频没有 DASH 流");
            }
            Dash out = new Dash();
            collect(dash.optJSONArray("video"), out.videos, true);
            collect(dash.optJSONArray("audio"), out.audios, false);
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析 DASH 失败：" + e, e);
        }
    }

    private static void collect(JSONArray array, List<Stream> into, boolean video) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Stream s = new Stream();
            s.qn = o.optInt("id", 0);
            s.url = o.optString("baseUrl", o.optString("base_url", ""));
            if (s.url != null && s.url.startsWith("//")) {
                s.url = "https:" + s.url;
            }
            s.bandwidth = o.optLong("bandwidth", 0);
            s.width = o.optInt("width", 0);
            s.height = o.optInt("height", 0);
            s.codecs = o.optString("codecs", "");
            if (video) {
                s.label = qualityName(s.qn);
            } else {
                s.label = s.bandwidth > 0 ? (s.bandwidth / 1000) + " kbps" : "音频";
            }
            if (s.url != null && s.url.length() > 0) {
                into.add(s);
            }
        }
    }

    // ------------------------------------------------------------------ 网络

    static String get(Context context, String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Accept", "application/json");
            String cookie = cookie();
            if (cookie != null && cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 宿主 WebView 里的登录 cookie，没有就返回 null（匿名请求）。 */
    public static String cookie() {
        try {
            String c = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            if (c == null || c.length() == 0) {
                c = CookieManager.getInstance().getCookie("https://www.bilibili.com");
            }
            return c;
        } catch (Throwable t) {
            XLog.w("读取 cookie 失败：" + t);
            return null;
        }
    }

    public static boolean loggedIn() {
        String c = cookie();
        return c != null && c.contains("SESSDATA");
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static void checkCode(JSONObject root, String what) throws IOException {
        int code = root.optInt("code", 0);
        if (code != 0) {
            String msg = root.optString("message", "");
            throw new IOException(what + " 返回 " + code + " " + msg);
        }
    }
}
