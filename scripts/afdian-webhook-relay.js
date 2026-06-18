/**
 * 爱发电 Webhook → WindyPurchase 中转脚本
 * =============================================
 *
 * 部署方式：Cloudflare Worker（免费，无需服务器）
 *
 * 部署步骤：
 * 1. 登录 https://dash.cloudflare.com
 * 2. 左侧菜单 → Workers & Pages → Create Worker
 * 3. 粘贴此脚本，修改下方 CONFIG 配置
 * 4. Deploy，记录 Worker URL（如 https://afdian-relay.your-name.workers.dev）
 * 5. 到爱发电开发者设置 → Webhook URL 填入 Worker URL
 *
 * 工作原理：
 *   爱发电 POST → Cloudflare Worker → 转发到你的 WindyPurchase HTTP 服务端
 *
 * 如果你的 WindyPurchase 在内网：
 *   - 用 Cloudflare Tunnel (cloudflared) 把本地端口暴露出去
 *   - 或者用 frp/ngrok 等内网穿透工具
 *   - 把穿透后的地址填入 CONFIG.BACKEND_URL
 */

// ==================== 配置 ====================
const CONFIG = {
  // 你的 WindyPurchase HTTP 服务端地址（需要公网可访问）
  // 如果用 Cloudflare Tunnel: http://localhost:9080 会由 tunnel 自动映射
  BACKEND_URL: "http://your-server:9080/api/notify/afdian",

  // 可选：简单的鉴权 token（防止他人伪造请求）
  // 需要与 WindyPurchase config.yml 中的 http.token 一致
  AUTH_TOKEN: "",

  // 是否转发到后端（false = 仅日志，用于调试）
  FORWARD: true,
};

// ==================== Worker 主逻辑 ====================
export default {
  async fetch(request, env, ctx) {
    // 只接受 POST
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405 });
    }

    try {
      // 读取爱发电发来的 body
      const body = await request.text();
      console.log("[Afdian Relay] Received webhook:", body.substring(0, 500));

      // 验证 body 是合法 JSON
      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch (e) {
        console.error("[Afdian Relay] Invalid JSON:", e.message);
        return new Response("Invalid JSON", { status: 400 });
      }

      // 基本校验：必须有 sign 和 data 字段
      if (!parsed.sign || !parsed.data) {
        console.warn("[Afdian Relay] Missing sign or data field");
        return new Response("Missing fields", { status: 400 });
      }

      if (!CONFIG.FORWARD) {
        console.log("[Afdian Relay] Forward disabled, logging only");
        return new Response(JSON.stringify({ code: 200, message: "OK (log only)" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }

      // 转发到 WindyPurchase 后端
      const headers = {
        "Content-Type": "application/json",
      };
      if (CONFIG.AUTH_TOKEN) {
        headers["X-Token"] = CONFIG.AUTH_TOKEN;
      }

      const response = await fetch(CONFIG.BACKEND_URL, {
        method: "POST",
        headers: headers,
        body: body,
      });

      const responseBody = await response.text();
      console.log("[Afdian Relay] Backend response:", response.status, responseBody);

      // 返回爱发电期望的响应格式
      return new Response(
        JSON.stringify({
          code: 200,
          message: "Webhook received and forwarded",
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }
      );
    } catch (error) {
      console.error("[Afdian Relay] Error:", error);
      return new Response("Internal Error", { status: 500 });
    }
  },
};
