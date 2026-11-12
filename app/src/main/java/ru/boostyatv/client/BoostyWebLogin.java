package ru.boostyatv.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.function.Consumer;

final class BoostyWebLogin extends FrameLayout {
    private static final String[] PROVIDERS = {
        "boosty.to", "vk.com", "vk.ru", "ok.ru", "mail.ru", "ya.ru", "yandex.ru",
        "yandex.com", "telegram.org", "t.me", "twitch.tv", "apple.com"
    };
    private final WebView root;
    private WebView popup;
    private final Consumer<String> address;
    private final Consumer<String> error;
    private final String initialAuth;
    private Consumer<WebSession> onAuthorized;
    private int callbackChecks;
    private final Runnable checkAuthorization = this::checkAuthorizationCookie;

    BoostyWebLogin(Context context, Consumer<String> address, Consumer<String> error) {
        super(context);
        this.address = address;
        this.error = error;
        initialAuth = cookie(CookieManager.getInstance().getCookie(BoostyApi.ORIGIN + "/"), "auth");
        root = createBrowser();
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        root.loadUrl(BoostyApi.ORIGIN + "/");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createBrowser() {
        WebView web = new WebView(getContext());
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame() || allowed(request.getUrl())) return false;
                showNavigationError(request.getUrl());
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                Uri uri = Uri.parse(url);
                if (!allowed(uri)) {
                    view.stopLoading();
                    showNavigationError(uri);
                    return;
                }
                if (uri.getHost() != null) address.accept(uri.getHost());
                if (isOAuthReturn(uri)) {
                    callbackChecks = 20;
                    removeCallbacks(checkAuthorization);
                    postDelayed(checkAuthorization, 250);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message message) {
                if (popup != null) return false;
                popup = createBrowser();
                addView(popup, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                ((WebView.WebViewTransport) message.obj).setWebView(popup);
                message.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                if (window == popup) closePopup();
            }
        });
        return web;
    }

    static boolean isOAuthReturn(Uri uri) {
        return isBoostyPage(uri) && "/app/oauth_redirect".equals(uri.getPath())
            && present(uri.getQueryParameter("code")) && present(uri.getQueryParameter("state"));
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean isBoostyPage(Uri uri) {
        return "https".equals(uri.getScheme()) && "boosty.to".equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    void onAuthorized(Consumer<WebSession> listener) {
        onAuthorized = listener;
    }

    private void checkAuthorizationCookie() {
        WebSession session = newCookieSession();
        if (session != null && onAuthorized != null) {
            onAuthorized.accept(session);
            return;
        }
        if (--callbackChecks > 0) postDelayed(checkAuthorization, 500);
    }

    private WebSession newCookieSession() {
        String cookies = CookieManager.getInstance().getCookie(BoostyApi.ORIGIN + "/");
        String auth = cookie(cookies, "auth");
        if (auth.isEmpty() || auth.equals(initialAuth)) return null;
        try {
            return WebSession.parse(auth, cookie(cookies, "_clientId"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean allowed(Uri uri) {
        if ("about:blank".equals(uri.toString())) return true;
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        for (String provider : PROVIDERS) {
            if (host.equals(provider) || host.endsWith("." + provider)) return true;
        }
        return false;
    }

    private void showNavigationError(Uri uri) {
        if ("accounts.google.com".equals(uri.getHost())) {
            error.accept("Google требует системный браузер. Для входа в Boosty ATV можно использовать привязанный номер телефона.");
        } else {
            error.accept("Этот сайт не входит в поддерживаемые страницы входа Boosty.");
        }
    }

    void readSession(Consumer<WebSession> done) {
        WebView browser = popup == null ? root : popup;
        Uri current = Uri.parse(browser.getUrl() == null ? "" : browser.getUrl());
        if (!isBoostyPage(current) && popup != null) {
            // An OAuth pop-up can still be open after Boosty stores the session in the main window.
            WebSession session = newCookieSession();
            if (session != null) {
                done.accept(session);
                return;
            }
        }
        if (!isBoostyPage(current)) {
            error.accept("Завершите вход и дождитесь возврата на boosty.to.");
            return;
        }
        browser.evaluateJavascript("""
            (() => {
              if (location.origin !== 'https://boosty.to') return null;
              return JSON.stringify({auth: localStorage.getItem('auth'), device: localStorage.getItem('_clientId')});
            })()
            """, value -> {
                try {
                    Object result = new JSONTokener(value).nextValue();
                    if (!(result instanceof String json)) throw new IllegalStateException();
                    JSONObject data = new JSONObject(json);
                    String auth = Post.string(data, "auth");
                    String device = Post.string(data, "device").replace("\"", "");
                    String cookies = CookieManager.getInstance().getCookie(BoostyApi.ORIGIN + "/");
                    if (auth.isEmpty()) auth = cookie(cookies, "auth");
                    if (device.isEmpty()) device = cookie(cookies, "_clientId");
                    done.accept(WebSession.parse(auth, device));
                } catch (Exception e) {
                    error.accept("На boosty.to ещё нет активной сессии. Войдите на сайте и нажмите кнопку снова.");
                }
            });
    }

    private static String cookie(String header, String name) {
        if (header == null) return "";
        String key = name + "=";
        for (String value : header.split(";")) {
            value = value.trim();
            if (value.startsWith(key)) return value.substring(key.length());
        }
        return "";
    }

    boolean closePopup() {
        if (popup == null) return false;
        removeView(popup);
        popup.destroy();
        popup = null;
        String url = root.getUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        address.accept(host == null ? "boosty.to" : host);
        return true;
    }

    void close(boolean removeSession) {
        removeCallbacks(checkAuthorization);
        onAuthorized = null;
        if (removeSession) {
            WebStorage.getInstance().deleteOrigin(BoostyApi.ORIGIN);
            CookieManager cookies = CookieManager.getInstance();
            for (String name : new String[]{"auth", "_clientId"}) {
                cookies.setCookie(BoostyApi.ORIGIN, name + "=; Max-Age=0; Path=/; Secure");
                cookies.setCookie(BoostyApi.ORIGIN, name + "=; Max-Age=0; Path=/; Domain=boosty.to; Secure");
            }
        }
        closePopup();
        removeView(root);
        root.destroy();
    }
}
