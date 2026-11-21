package net.micode.notes.ai;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 讯飞星火 Lite 总结客户端。
 *
 * <p>维护说明：本类从 {@code NoteEditActivity} 中抽取而来，原来的界面类同时承担了
 * 鉴权签名、WebSocket 连接、JSON 组包与流式解析四项职责，既难以复用也无法单独测试。
 * 抽取后本类只关心“把一段文本发给星火、把流式结果拼起来、通过回调交回结果”，
 * 界面类只负责进度框与结果对话框。</p>
 *
 * <p>一次完整调用包含四个步骤：</p>
 * <ol>
 *     <li>用 apiKey/apiSecret 按 HMAC-SHA256 生成带 authorization 的鉴权 URL；</li>
 *     <li>把鉴权 URL 的 http(s) 前缀换成 ws(s)，建立 WebSocket 连接；</li>
 *     <li>连接建立后发送 chat 请求报文，报文分 header/parameter/payload 三段；</li>
 *     <li>逐帧接收响应，按 header.status 判断流是否结束（2 表示尾包），结束后回调结果。</li>
 * </ol>
 *
 * <p>线程约束：{@link #summarize(String, Callback)} 可在主线程调用，
 * {@link Callback#onSummaryStart()} 在调用线程执行；
 * {@link Callback#onSummarySuccess(String)} 与 {@link Callback#onSummaryError(Throwable)}
 * 在 OkHttp 的工作线程执行，调用方需要自行切回主线程更新界面。</p>
 */
public class AiSummaryClient {

    private static final String TAG = "AiSummaryClient";

    /** 单次请求允许生成的最大 token 数。 */
    private static final int MAX_TOKENS = 2048;

    /** 采样温度，0.5 兼顾结果稳定与表达自然。 */
    private static final double TEMPERATURE = 0.5;

    /** 星火接口要求的对话角色名。 */
    private static final String ROLE_USER = "user";

    /** 总结请求的提示词前缀，后面拼接便签正文。 */
    private static final String PROMPT_PREFIX = "请简要总结以下便签内容的核心要点：\n\n";

    /** 流式响应的状态码：0 首包、1 中间包、2 尾包。 */
    private static final int STATUS_LAST = 2;

    /**
     * 总结过程的回调。
     *
     * <p>三个事件的顺序固定为：开始 → （成功 | 失败），不会同时出现成功与失败。</p>
     */
    public interface Callback {

        /** 请求已发出，调用方可以在此显示进度。 */
        void onSummaryStart();

        /**
         * 总结成功。
         *
         * @param summary 服务端返回的完整总结文本（已去除流式分片的边界）
         */
        void onSummarySuccess(String summary);

        /**
         * 总结失败，包括鉴权异常、网络异常、服务端返回非 0 错误码等。
         *
         * @param t 失败原因
         */
        void onSummaryError(Throwable t);
    }

    private final String mAppId;
    private final String mApiKey;
    private final String mApiSecret;
    private final String mHostUrl;
    private final String mDomain;

    /** 流式响应的拼接缓冲，每次 summarize 前清空。 */
    private final StringBuilder mResponseBuilder = new StringBuilder();

    /** 复用同一个 OkHttpClient，避免每次总结都新建连接池。 */
    private final OkHttpClient mHttpClient;

    private WebSocket mWebSocket;
    private Callback mCallback;

    /** 标记本次请求是否已经结束（成功、失败或被取消），用于丢弃迟到的回调。 */
    private volatile boolean mFinished;

    /**
     * @param appId     星火控制台的 APPID
     * @param apiKey    星火控制台的 APIKey
     * @param apiSecret 星火控制台的 APISecret
     * @param hostUrl   星火接口地址，例如 https://spark-api.xf-yun.com/v1/x1
     * @param domain    模型域，Lite 为 x1
     */
    public AiSummaryClient(String appId, String apiKey, String apiSecret, String hostUrl,
            String domain) {
        mAppId = appId;
        mApiKey = apiKey;
        mApiSecret = apiSecret;
        mHostUrl = hostUrl;
        mDomain = domain;
        mHttpClient = new OkHttpClient.Builder().build();
    }

    /**
     * 对给定文本发起一次总结。
     *
     * <p>重复调用会丢弃上一次未完成的请求，因此界面层无需自己保证“同一时刻只有一次总结”。</p>
     *
     * @param content  待总结的便签正文，不能为空
     * @param callback 结果回调，不能为空
     */
    public void summarize(final String content, Callback callback) {
        if (callback == null) {
            return;
        }

        // 丢弃上一次请求，避免两个请求的回调交叉
        cancel();

        mCallback = callback;
        mFinished = false;
        mResponseBuilder.setLength(0);

        callback.onSummaryStart();

        // 鉴权签名与建连都是阻塞操作，放到后台线程执行
        new Thread(new Runnable() {
            @Override
            public void run() {
                connect(content);
            }
        }, "ai-summary").start();
    }

    /**
     * 取消当前请求并释放连接。
     *
     * <p>界面销毁时调用，避免回调打到已销毁的 Activity 上。
     * 取消后不会再触发任何回调。</p>
     */
    public void cancel() {
        mFinished = true;
        mCallback = null;
        closeSocket();
    }

    /** 生成鉴权 URL 并建立 WebSocket 连接。 */
    private void connect(String userContent) {
        try {
            String authUrl = getAuthUrl(mHostUrl, mApiKey, mApiSecret);
            // 星火要求使用 wss 协议
            String wsUrl = authUrl.replace("http://", "ws://").replace("https://", "wss://");

            Request request = new Request.Builder().url(wsUrl).build();

            mWebSocket = mHttpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    super.onOpen(webSocket, response);
                    // 连接建立后再发业务报文
                    sendUserMessage(webSocket, userContent);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    super.onMessage(webSocket, text);
                    handleMessage(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    super.onFailure(webSocket, t, response);
                    notifyError(t);
                }
            });
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /** 组装并发送 chat 请求报文。 */
    private void sendUserMessage(WebSocket webSocket, String content) {
        try {
            webSocket.send(buildRequestJson(content).toString());
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /**
     * 构造星火 chat 请求报文。
     *
     * <p>报文分三段：header 放应用标识与 uid，parameter 放采样参数，
     * payload 放对话内容。uid 只用于服务端限流统计，这里随机生成。</p>
     */
    private JSONObject buildRequestJson(String content) throws Exception {
        JSONObject requestJson = new JSONObject();

        JSONObject header = new JSONObject();
        header.put("app_id", mAppId);
        header.put("uid", UUID.randomUUID().toString().substring(0, 10));
        requestJson.put("header", header);

        JSONObject parameter = new JSONObject();
        JSONObject chat = new JSONObject();
        chat.put("domain", mDomain);
        chat.put("temperature", TEMPERATURE);
        chat.put("max_tokens", MAX_TOKENS);
        parameter.put("chat", chat);
        requestJson.put("parameter", parameter);

        JSONObject payload = new JSONObject();
        JSONArray textArray = new JSONArray();
        JSONObject textObj = new JSONObject();
        textObj.put("role", ROLE_USER);
        textObj.put("content", PROMPT_PREFIX + content);
        textArray.put(textObj);
        JSONObject message = new JSONObject();
        message.put("text", textArray);
        payload.put("message", message);
        requestJson.put("payload", payload);

        return requestJson;
    }

    /**
     * 处理一帧响应：错误码非 0 视为失败；正常帧把文本追加到缓冲区；
     * 尾包（status == 2）触发成功回调。
     */
    private void handleMessage(String jsonText) {
        if (mFinished) {
            return;
        }
        try {
            JSONObject jsonObject = new JSONObject(jsonText);
            JSONObject header = jsonObject.optJSONObject("header");
            int code = header == null ? -1 : header.optInt("code", -1);
            if (code != 0) {
                Log.e(TAG, "Spark error, payload=" + jsonText);
                notifyError(new IllegalStateException("Spark error code: " + code));
                return;
            }

            JSONObject payload = jsonObject.optJSONObject("payload");
            JSONObject choices = payload == null ? null : payload.optJSONObject("choices");
            JSONArray textArray = choices == null ? null : choices.optJSONArray("text");

            if (textArray != null && textArray.length() > 0) {
                JSONObject contentObj = textArray.optJSONObject(0);
                if (contentObj != null) {
                    mResponseBuilder.append(contentObj.optString("content", ""));
                }
            }

            if (header.optInt("status", 0) == STATUS_LAST) {
                notifySuccess(mResponseBuilder.toString());
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    /** 成功回调，保证只触发一次。 */
    private void notifySuccess(String summary) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        Callback callback = mCallback;
        mCallback = null;
        closeSocket();
        if (callback != null) {
            callback.onSummarySuccess(summary);
        }
    }

    /** 失败回调，保证只触发一次。 */
    private void notifyError(Throwable t) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        Callback callback = mCallback;
        mCallback = null;
        closeSocket();
        Log.e(TAG, "AI summary failed", t);
        if (callback != null) {
            callback.onSummaryError(t);
        }
    }

    /** 安静地关闭 WebSocket，异常不影响主流程。 */
    private void closeSocket() {
        WebSocket webSocket = mWebSocket;
        mWebSocket = null;
        if (webSocket != null) {
            try {
                webSocket.close(1000, "client closed");
            } catch (Exception e) {
                Log.w(TAG, "close websocket failed", e);
            }
        }
    }

    /**
     * 生成星火鉴权 URL（HMAC-SHA256 签名）。
     *
     * <p>签名串固定为三行：host、date、request-line；签名结果再做一次 Base64
     * 得到 authorization，最后把 authorization/date/host 三个参数拼到 URL 上。</p>
     *
     * @param hostUrl   接口地址，例如 https://spark-api.xf-yun.com/v1/x1
     * @param apiKey    控制台的 APIKey
     * @param apiSecret 控制台的 APISecret
     * @return 带签名参数的完整 URL
     */
    public static String getAuthUrl(String hostUrl, String apiKey, String apiSecret)
            throws Exception {
        URL url = new URL(hostUrl);

        // RFC 1123 格式的 GMT 时间，服务端会校验与本地时间的偏差
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = sdf.format(new Date());

        String host = url.getHost();
        // 注意：v1/x1 这种带路径的地址，Request-Line 必须包含 path
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + url.getPath() + " HTTP/1.1";

        Mac mac = Mac.getInstance("hmacsha256");
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8),
                "hmacsha256");
        mac.init(spec);
        byte[] signature = mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8));

        // Android 的 Base64，NO_WRAP 避免签名里出现换行
        String sha = Base64.encodeToString(signature, Base64.NO_WRAP);

        String authorization = String.format(Locale.US,
                "api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"",
                apiKey, "hmac-sha256", "host date request-line", sha);

        String authBase64 = Base64.encodeToString(
                authorization.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        return String.format("%s?authorization=%s&date=%s&host=%s",
                hostUrl,
                URLEncoder.encode(authBase64, "UTF-8"),
                URLEncoder.encode(date, "UTF-8"),
                host);
    }
}
