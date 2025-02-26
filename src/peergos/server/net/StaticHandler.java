package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.util.*;
import peergos.shared.crypto.hash.Hash;
import peergos.shared.user.*;
import peergos.shared.util.ArrayOps;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.util.zip.GZIPOutputStream;

public abstract class StaticHandler implements HttpHandler
{
    private final boolean isGzip;
    private final boolean includeCsp;
    private final CspHost host;
    private final List<String> blockstoreDomain;
    private final List<String> appsubdomains;
    private final List<String> frameDomains;
    private final Map<String, String> appDomains;
    private final Optional<HttpPoster> appDevTarget;

    public StaticHandler(CspHost host,
                         List<String> blockstoreDomain,
                         List<String> frameDomains,
                         List<String> appSubdomains,
                         boolean includeCsp,
                         boolean isGzip,
                         Optional<HttpPoster> appDevTarget) {
        this.host = host;
        this.includeCsp = includeCsp;
        this.blockstoreDomain = blockstoreDomain;
        this.frameDomains = frameDomains;
        this.appsubdomains = appSubdomains;
        this.appDomains = appSubdomains.stream()
                .collect(Collectors.toMap(s -> s + domainSuffix(), s -> s));
        this.isGzip = isGzip;
        this.appDevTarget = appDevTarget;
    }

    private String domainSuffix() {
        return "." + host.domain + host.port.map(p -> ":" + p).orElse("");
    }

    public abstract Asset getAsset(String resourcePath) throws IOException;

    public static class Asset {
        public final byte[] data;
        public final String hash;

        public Asset(byte[] data) {
            this.data = data;
            byte[] digest = Hash.sha256(data);
            this.hash = ArrayOps.bytesToHex(Arrays.copyOfRange(digest, 0, 8));
        }
    }

    protected boolean isGzip() {
        return isGzip;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        String path = httpExchange.getRequestURI().getPath();
        try {
            path = path.substring(1);
            path = path.replaceAll("//", "/");
            if (path.length() == 0)
                path = "index.html";

            String reqHost = httpExchange.getRequestHeaders().get("Host").stream().findFirst().orElse("");
            boolean isSubdomain = reqHost.contains(".") && reqHost.substring(reqHost.indexOf(".")).equals(domainSuffix());
//            Logging.LOG().info("Req host: " + reqHost + ", isSub: " + isSubdomain + ", path: " + path);
            String app = appDomains.getOrDefault(reqHost, "sandbox");
            if (isSubdomain && app.equals("sandbox")) { // serve sandbox assets from sandbox sub dir for root path
                path = "apps/sandbox" + (path.startsWith("/") ? "" : "/") + path;
            }

            boolean isRoot = path.equals("index.html");
            Asset res;
            boolean isAppDevResource = false;
            if (appDevTarget.isEmpty() || ! isSubdomain || ! app.equals("sandbox")) {
                res = getAsset(path);
            } else {
                try {
                    res = getAsset(path);
                } catch (Throwable t) {
                    isAppDevResource = true;
                    HttpPoster poster = appDevTarget.get();
                    String urlBase = poster.toString();
                    String assetPath = path.substring("apps/sandbox/".length());
                    String fullUrl = urlBase.endsWith("/") ? urlBase + assetPath : urlBase + "/" + assetPath;
                    byte[] data = poster.get(fullUrl).join();
                    res = new Asset(data);
                }
            }

            if (isGzip && !isAppDevResource)
                httpExchange.getResponseHeaders().set("Content-Encoding", "gzip");
            if (path.endsWith(".js"))
                httpExchange.getResponseHeaders().set("Content-Type", "text/javascript");
            else if (path.endsWith(".html"))
                httpExchange.getResponseHeaders().set("Content-Type", "text/html");
            else if (path.endsWith(".css"))
                httpExchange.getResponseHeaders().set("Content-Type", "text/css");
            else if (path.endsWith(".json"))
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
            else if (path.endsWith(".png"))
                httpExchange.getResponseHeaders().set("Content-Type", "image/png");
            else if (path.endsWith(".woff"))
                httpExchange.getResponseHeaders().set("Content-Type", "application/font-woff");
            else if (path.endsWith(".svg"))
                httpExchange.getResponseHeaders().set("Content-Type", "image/svg+xml");


            if (httpExchange.getRequestMethod().equals("HEAD")) {
                httpExchange.getResponseHeaders().set("Content-Length", "" + res.data.length);
                httpExchange.sendResponseHeaders(200, -1);
                return;
            }
            if (! isRoot && ! isAppDevResource) {
                httpExchange.getResponseHeaders().set("Cache-Control", "public, max-age=600");
                httpExchange.getResponseHeaders().set("ETag", res.hash);
            }

            // only allow app-specific subdomain to access app-specific assets folder, or sandbox for generated subdomains
            if (isSubdomain ^ path.startsWith("apps/" + app)) {
                System.err.println("404 FileNotFound: " + path);
                httpExchange.sendResponseHeaders(404, 0);
                httpExchange.getResponseBody().close();
                return;
            }

            // Only allow assets to be loaded from the original host
            // Todo work on removing unsafe-inline from sub domains
            if (includeCsp)
                httpExchange.getResponseHeaders().set("content-security-policy", "default-src 'self' " + this.host + ";" +
                        "style-src 'self' " +
                        " " + this.host +
                        (isSubdomain ? " 'unsafe-inline' https://" + reqHost : "") + // calendar, editor, todoboard, pdfviewer
                        ";" +
                        (isSubdomain ? "sandbox allow-same-origin allow-scripts allow-forms;" : "") +
                        "frame-src 'self' " + frameDomains.stream().collect(Collectors.joining(" ")) + " " + (isSubdomain ? "" : this.host.wildcard()) + ";" +
                        "frame-ancestors 'self' " + this.host + ";" +
                        "prefetch-src 'self' " + this.host + ";" + // prefetch can be used to leak data via DNS
                        "connect-src 'self' " + this.host +
                        (isSubdomain ? "" : blockstoreDomain.stream().map(d -> " https://" + d).collect(Collectors.joining())) + ";" +
                        "webrtc 'block';" +
                        "media-src 'self' " + this.host + " blob:;" +
                        "img-src 'self' " + this.host + " data: blob:;" +
                        "object-src 'none';"
                );

            // Enable COEP, CORP, COOP
            httpExchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
            httpExchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", isSubdomain ? "cross-origin" : "same-origin");
            httpExchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");

            // Request same site, cross origin isolation
            httpExchange.getResponseHeaders().set("Origin-Agent-Cluster", "?1");

            // Don't let anyone to load main Peergos site in an iframe (legacy header)
            if (!isSubdomain)
                httpExchange.getResponseHeaders().set("x-frame-options", "sameorigin");
            // Enable cross site scripting protection
            httpExchange.getResponseHeaders().set("x-xss-protection", "1; mode=block");
            // Disable prefetch which can be used to exfiltrate data cross domain
            httpExchange.getResponseHeaders().set("x-dns-prefetch-control", "off");
            // Don't let browser sniff mime types
            httpExchange.getResponseHeaders().set("x-content-type-options", "nosniff");
            // Don't send Peergos referrer to anyone
            httpExchange.getResponseHeaders().set("referrer-policy", "no-referrer");
            // allow list of permissions
            httpExchange.getResponseHeaders().set("permissions-policy",
                    "interest-cohort=(), geolocation=(), gyroscope=(), magnetometer=(), accelerometer=(), microphone=(), " +
                    "camera=(self), fullscreen=(self)");
            if (! isRoot) {
                String previousEtag = httpExchange.getRequestHeaders().getFirst("If-None-Match");
                if (res.hash.equals(previousEtag)) {
                    httpExchange.sendResponseHeaders(304, -1); // NOT MODIFIED
                    return;
                }
            }

            httpExchange.sendResponseHeaders(200, res.data.length);
            httpExchange.getResponseBody().write(res.data);
            httpExchange.getResponseBody().close();
        } catch (Throwable t) {
            System.err.println("404 FileNotFound: " + path);
            httpExchange.sendResponseHeaders(404, 0);
            httpExchange.getResponseBody().close();
        }
    }


    protected static byte[] readResource(InputStream in, boolean gzip) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        OutputStream gout = gzip ? new GZIPOutputStream(bout) : new DataOutputStream(bout);
        byte[] tmp = new byte[4096];
        int r;
        while ((r=in.read(tmp)) >= 0)
            gout.write(tmp, 0, r);
        gout.flush();
        gout.close();
        in.close();
        return bout.toByteArray();
    }


    public StaticHandler withCache() {
        Map<String, Asset> cache = new ConcurrentHashMap<>();
        StaticHandler that = this;

        return new StaticHandler(host, blockstoreDomain, frameDomains, appsubdomains, includeCsp, isGzip, appDevTarget) {
            @Override
            public Asset getAsset(String resourcePath) throws IOException {
                if (! cache.containsKey(resourcePath))
                    cache.put(resourcePath, that.getAsset(resourcePath));
                return cache.get(resourcePath);
            }
        };
    }
}
