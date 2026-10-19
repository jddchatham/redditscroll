package com.redditscroll.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One Reddit post as displayed in the pager. */
public class Reddit {

    public String title;
    public String link;        // absolute permalink to the Reddit thread
    public String id;          // t3_... (stable dedupe key)
    public String subreddit;
    public String author;      // without the u/ prefix
    public String imageUrl;    // best-effort image, may be null (self/text post)
    public String excerpt;     // cleaned selftext, may be empty
    public String dateText;    // relative time
    public long createdUtc;    // millis

    /** Random-order copy (Fisher-Yates via Collections.shuffle). */
    public static ArrayList<Reddit> shuffledCopy(List<Reddit> in, Random rnd) {
        ArrayList<Reddit> copy = new ArrayList<Reddit>(in);
        Collections.shuffle(copy, rnd);
        return copy;
    }

    /** "just now" / "12m ago" / "3h ago" / "2d ago" / "Mar 4". */
    public static String relTime(long createdMillis) {
        if (createdMillis <= 0) return "";
        long diff = System.currentTimeMillis() - createdMillis;
        if (diff < 60 * 1000L) return "just now";
        long m = diff / (60 * 1000L);
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 7) return d + "d ago";
        SimpleDateFormat f = new SimpleDateFormat("MMM d", Locale.US);
        return f.format(new Date(createdMillis));
    }

    // ------------------------------------------------------------- parsing

    /** Atom feed parse result. Reddit RSS has no pagination token. */
    public static final class Listing {
        public final ArrayList<Reddit> posts = new ArrayList<Reddit>();
    }

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    private static final String NS_MEDIA = "http://search.yahoo.com/mrss/";

    private static final Pattern IMG =
            Pattern.compile("<img[^>]+?src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);|&(\\w+);");

    private static final Map<String, String> ENTITIES = new HashMap<String, String>();
    static {
        ENTITIES.put("amp", "&");
        ENTITIES.put("lt", "<");
        ENTITIES.put("gt", ">");
        ENTITIES.put("quot", "\"");
        ENTITIES.put("apos", "'");
        ENTITIES.put("nbsp", " ");
        ENTITIES.put("hellip", "\u2026");
        ENTITIES.put("mdash", "\u2014");
        ENTITIES.put("ndash", "\u2013");
        ENTITIES.put("lsquo", "\u2018");
        ENTITIES.put("rsquo", "\u2019");
        ENTITIES.put("ldquo", "\u201C");
        ENTITIES.put("rdquo", "\u201D");
    }

    /** Last parse outcome, surfaced on the error screen for diagnosis. */
    public static String lastError = "";

    /** Parse a subreddit Atom feed (listing or search). Returns null on total failure. */
    public static Listing parse(String xml) {
        if (xml == null) { lastError = "null body"; return null; }
        Listing listing = new Listing();
        lastError = "";
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new StringReader(xml));
            Reddit cur = null;
            int seen = 0;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String ns = p.getNamespace();
                    String name = p.getName();
                    if (isAtomNs(ns) && "entry".equals(name)) {
                        cur = new Reddit();
                        seen++;
                    } else if (cur != null) {
                        readField(p, cur, ns, name);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (cur != null && isAtomNs(p.getNamespace())
                            && "entry".equals(p.getName())) {
                        finish(cur);
                        if (cur.title != null && !cur.title.isEmpty()
                                && cur.link != null && !cur.link.isEmpty()) {
                            listing.posts.add(cur);
                        }
                        cur = null;
                    }
                }
                ev = p.next();
            }
            lastError = "atom ok, entries=" + seen + ", posts=" + listing.posts.size();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            if (listing.posts.isEmpty()) return null;
        }
        return listing;
    }

    private static void readField(XmlPullParser p, Reddit r, String ns, String name)
            throws Exception {
        if (isAtomNs(ns)) {
            if ("title".equals(name)) {
                r.title = p.nextText();
            } else if ("link".equals(name)) {
                String href = p.getAttributeValue(null, "href");
                if (r.link == null && href != null && !href.isEmpty()) r.link = href.trim();
                skipElement(p);
            } else if ("id".equals(name)) {
                r.id = p.nextText().trim();
            } else if ("published".equals(name)) {
                r.createdUtc = parseIso(p.nextText().trim());
            } else if ("content".equals(name)) {
                r.rawContent = p.nextText();
                r.excerpt = makeExcerpt(r.rawContent);
            } else if ("author".equals(name)) {
                readAuthor(p, r);
            } else if ("category".equals(name)) {
                if (r.subreddit == null || r.subreddit.isEmpty()) {
                    String term = p.getAttributeValue(null, "term");
                    if (term != null && !term.isEmpty()) r.subreddit = term;
                }
                skipElement(p);
            } else {
                skipElement(p);
            }
        } else if (NS_MEDIA.equals(ns) && "thumbnail".equals(name)) {
            String url = p.getAttributeValue(null, "url");
            if (url != null && !url.isEmpty() && r.imageUrl == null) {
                r.imageUrl = decode(url.trim());
            }
            skipElement(p);
        } else {
            skipElement(p);
        }
    }

    /** Atom namespace, with an empty-ns fallback for parser quirks. */
    private static boolean isAtomNs(String ns) {
        return ns == null || ns.isEmpty() || NS_ATOM.equals(ns);
    }

    /** <author><name>..</name></author> — pure event walk, no nextText. */
    private static void readAuthor(XmlPullParser p, Reddit r) throws Exception {
        int depth = 1;
        boolean wantText = false;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                wantText = depth == 2 && "name".equals(p.getName());
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
                wantText = false;
            } else if (ev == XmlPullParser.TEXT && wantText
                    && (r.author == null || r.author.isEmpty())) {
                r.author = p.getText();
            }
        }
    }

    /** Consume everything up to and including the end tag of the current element. */
    private static void skipElement(XmlPullParser p) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
        }
    }

    private static void finish(Reddit r) {
        r.title = decode(r.title).trim();
        if (r.author != null) {
            r.author = r.author.trim();
            if (r.author.startsWith("/u/")) r.author = r.author.substring(3);
            else if (r.author.startsWith("u/")) r.author = r.author.substring(2);
        }
        if (r.imageUrl == null) r.imageUrl = firstImage(r.rawContent);
        if (r.imageUrl != null) r.imageUrl = r.imageUrl.trim();
        if (r.id == null || r.id.isEmpty()) r.id = r.link;
        if (r.subreddit == null || r.subreddit.isEmpty()) r.subreddit = "";
        r.dateText = relTime(r.createdUtc);
    }

    // rawContent is stashed on the instance during readField so finish() can
    // mine it for a fallback image after the entry closes.
    private String rawContent;

    /** Strip HTML to text; keep only the actual post body (the SC_OFF md div). */
    private static String makeExcerpt(String html) {
        if (html == null) return "";
        int sc = html.indexOf("<!-- SC_OFF -->");
        String body = sc >= 0 ? html.substring(sc) : html;
        String text = decode(stripTags(body)).trim();
        if (text.length() > 240) {
            text = text.substring(0, 240);
            int sp = text.lastIndexOf(' ');
            if (sp > 120) text = text.substring(0, sp);
            text = text + "\u2026";
        }
        return text;
    }

    /** Atom date, e.g. 2026-10-01T14:15:49+00:00. 'X' offset pattern needs API 24+,
     *  so strip the offset manually to stay safe on older devices. */
    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        int cut = s.lastIndexOf('+', 19); // +00:00 style offset
        int z = s.indexOf('Z');
        if (z > 0) s = s.substring(0, z);
        else if (cut > 0) s = s.substring(0, cut);
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstImage(String html) {
        if (html == null) return null;
        Matcher m = IMG.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    static String stripTags(String html) {
        if (html == null) return "";
        return TAG.matcher(html).replaceAll(" ").replaceAll("\\s+", " ");
    }

    static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep;
            try {
                int cp;
                if (m.group(1) != null) cp = Integer.parseInt(m.group(1));
                else if (m.group(2) != null) cp = Integer.parseInt(m.group(2), 16);
                else {
                    String named = ENTITIES.get(m.group(3).toLowerCase(Locale.US));
                    rep = named != null ? named : m.group();
                    m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                    continue;
                }
                rep = new String(Character.toChars(cp));
            } catch (Exception e) {
                rep = " ";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- URLs

    /** listing sort key: "hot" | "new" | "top" (top = this week). */
    public static String listingUrl(String sub, String sortKey, int limit) {
        StringBuilder sb = new StringBuilder("https://www.reddit.com/r/");
        sb.append(enc(sub)).append('/');
        if ("new".equals(sortKey) || "top".equals(sortKey)) sb.append(sortKey).append('/');
        sb.append(".rss?limit=").append(limit);
        if ("top".equals(sortKey)) sb.append("&t=week");
        return sb.toString();
    }

    /** Search inside one subreddit (restrict_sr). sort: relevance|new|top. */
    public static String searchUrl(String sub, String query, String sort, int limit) {
        StringBuilder sb = new StringBuilder("https://www.reddit.com/r/");
        sb.append(enc(sub)).append("/search.rss?q=").append(enc(query));
        sb.append("&restrict_sr=on&sort=").append(sort);
        sb.append("&limit=").append(limit);
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}

/** Minimal HTTPS GET. */
final class Fetch {

    static final String UA = "RedditScroll/1.0 Android (personal reader)";

    /** Status code of the most recent request (0 = connection-level failure). */
    static int lastCode;

    private Fetch() {}

    static String get(String urlString) {
        HttpURLConnection con = null;
        lastCode = 0;
        try {
            URL url = new URL(urlString);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", UA);
            con.setConnectTimeout(12000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            lastCode = code;
            if (code < 200 || code >= 300) return null;
            return readAll(new BufferedInputStream(con.getInputStream()));
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return new String(buf.toByteArray(), "UTF-8");
    }
}

/** Persists the last successfully downloaded feed XML per (subreddit, sort). */
final class CacheStore {

    private CacheStore() {}

    public static String load(android.content.Context c, String key) {
        File f = c.getFileStreamPath(fileFor(key));
        if (!f.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            try {
                return Fetch.readAll(new BufferedInputStream(fis));
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(android.content.Context c, String key, String xml) {
        if (xml == null) return;
        String name = fileFor(key);
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        File out = c.getFileStreamPath(name);
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(xml.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) {
                FileInputStream fis = new FileInputStream(tmp);
                FileOutputStream fos2 = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos2.write(buf, 0, n);
                } finally {
                    fis.close();
                    fos2.close();
                }
                tmp.delete();
            }
        } catch (Exception e) {
            // Cache write failure is non-fatal; the app keeps running.
        }
    }

    private static String fileFor(String key) {
        return "rl_" + key.replaceAll("[^A-Za-z0-9_]", "_") + ".xml";
    }
}
