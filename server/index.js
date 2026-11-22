// Firebase Cloud Function: بيستقبل صورة Base64 من التطبيق ويرجّع النص.
// المفتاح مش موجود في التطبيق خالص - السيرفر بيستخدم صلاحيات حساب الخدمة بتاعه.
const { onRequest } = require("firebase-functions/v2/https");
const vision = require("@google-cloud/vision");

const client = new vision.ImageAnnotatorClient();
const APP_TOKEN = process.env.APP_TOKEN || ""; // نفس القيمة اللي في CloudVisionScanner.APP_TOKEN

exports.ocr = onRequest({ maxInstances: 3, memory: "512MiB", timeoutSeconds: 30 }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  if (APP_TOKEN && req.get("X-App-Token") !== APP_TOKEN) return res.status(401).json({ error: "unauthorized" });

  const image = req.body && req.body.image;
  if (typeof image !== "string" || image.length < 100) return res.status(400).json({ error: "image missing" });
  if (image.length > 8 * 1024 * 1024) return res.status(413).json({ error: "image too large" });

  try {
    const [result] = await client.documentTextDetection({
      image: { content: image },
      imageContext: { languageHints: ["ar", "en"] },
    });
    res.json({ text: (result.fullTextAnnotation && result.fullTextAnnotation.text) || "" });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "vision failed" });
  }
});
