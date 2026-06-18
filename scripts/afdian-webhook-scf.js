/**
 * 爱发电 Webhook → WindyPurchase 中转脚本（腾讯云 SCF 版）
 * ==========================================================
 *
 * 部署方式：腾讯云云函数 (Serverless Cloud Function)
 *
 * 部署步骤：
 * 1. 登录 https://console.cloud.tencent.com/scf
 * 2. 新建函数 → 从头开始 → Node.js 运行时
 * 3. 提交方式：在线编辑，粘贴此脚本
 * 4. 修改下方 CONFIG 配置
 * 5. 触发器配置 → API 网关触发 → 请求方法 POST
 * 6. 记录 API 网关访问地址
 * 7. 到爱发电开发者设置 → Webhook URL 填入 API 网关地址
 *
 * 环境变量（推荐通过 SCF 环境变量配置，而非硬编码）：
 *   BACKEND_URL  — WindyPurchase HTTP 地址
 *   AUTH_TOKEN   — 鉴权 token
 */

// ==================== 配置（优先读环境变量） ====================
const CONFIG = {
  BACKEND_URL: process.env.BACKEND_URL || "http://your-server:9080/api/notify/afdian",
  AUTH_TOKEN: process.env.AUTH_TOKEN || "",
};

// ==================== SCF 入口 ====================
exports.main_handler = async (event, context) => {
  console.log("[Afdian SCF] Event:", JSON.stringify(event).substring(0, 1000));

  try {
    // 解析 body
    let body;
    if (event.body) {
      // API 网关触发时 body 在 event.body 中
      body = event.isBase64Encoded
        ? Buffer.from(event.body, "base64").toString("utf-8")
        : event.body;
    } else {
      body = typeof event === "string" ? event : JSON.stringify(event);
    }

    console.log("[Afdian SCF] Body:", body.substring(0, 500));

    // JSON 校验
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch (e) {
      return { statusCode: 400, body: "Invalid JSON" };
    }

    if (!parsed.sign || !parsed.data) {
      return { statusCode: 400, body: "Missing fields" };
    }

    // 转发到 WindyPurchase 后端
    const http = require("http");
    const url = new URL(CONFIG.BACKEND_URL);

    const headers = {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(body),
    };
    if (CONFIG.AUTH_TOKEN) {
      headers["X-Token"] = CONFIG.AUTH_TOKEN;
    }

    const result = await new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: url.hostname,
          port: url.port || 80,
          path: url.pathname,
          method: "POST",
          headers: headers,
          timeout: 10000,
        },
        (res) => {
          let data = "";
          res.on("data", (chunk) => (data += chunk));
          res.on("end", () =>
            resolve({ statusCode: res.statusCode, body: data })
          );
        }
      );
      req.on("error", (e) => reject(e));
      req.on("timeout", () => {
        req.destroy();
        reject(new Error("Timeout"));
      });
      req.write(body);
      req.end();
    });

    console.log("[Afdian SCF] Backend response:", result.statusCode, result.body);

    return {
      statusCode: 200,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: 200, message: "OK" }),
    };
  } catch (error) {
    console.error("[Afdian SCF] Error:", error);
    return { statusCode: 500, body: "Internal Error" };
  }
};
