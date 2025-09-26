const functions = require("firebase-functions");
const admin = require("firebase-admin");

try { admin.initializeApp(); } catch (e) {}

const db = admin.firestore();
const fcm = admin.messaging();

// Haversine (u metrima)
function distanceMeters(aLat, aLng, bLat, bLng) {
  const toRad = (x) => x * Math.PI / 180;
  const R = 6371000;
  const dLat = toRad(bLat - aLat);
  const dLng = toRad(bLng - aLng);
  const sa = Math.sin(dLat/2) ** 2 +
    Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLng/2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(sa));
}

/**
 * Trigger: svaka promena na users/{uid}
 * Očekuje polja: lat, lng, fcmToken (opciono), lastLocAt
 * Logika:
 *  - Nađe parkinge sa availableSlots > 0 u krugu ≤ 150 m
 *  - Ne šalje prečesto: per user/per parking cooldown 2h (users/{uid}/nearby/{parkingId}.notifiedAt)
 */
exports.notifyNearbyParking = functions.firestore
  .document("users/{uid}")
  .onWrite(async (change, context) => {
    const uid = context.params.uid;
    const after = change.after.exists ? change.after.data() : null;
    if (!after) return null;

    const lat = after.lat, lng = after.lng;
    if (typeof lat !== "number" || typeof lng !== "number") return null;

    // FCM token (mora postojati)
    const token = after.fcmToken;
    if (!token) return null;

    // Učitaj parkinge (limit 200 da ne preteramo)
    const ps = await db.collection("parkings")
      .where("isActive", "==", true)
      .limit(200)
      .get();

    const now = Date.now();
    const radiusM = 150;

    const batch = db.batch();
    const toNotify = [];

    for (const doc of ps.docs) {
      const p = doc.data() || {};
      const pLat = p.lat, pLng = p.lng;
      const avail = typeof p.availableSlots === "number" ? p.availableSlots : 0;
      if (typeof pLat !== "number" || typeof pLng !== "number") continue;
      if (avail <= 0) continue;

      const dist = distanceMeters(lat, lng, pLat, pLng);
      if (dist > radiusM) continue;

      // cooldown check
      const nr = await db.collection("users").doc(uid)
        .collection("nearby").doc(doc.id).get();
      const last = nr.exists ? (nr.get("notifiedAt") || 0) : 0;
      const cooldownMs = 2 * 60 * 60 * 1000; // 2h
      if (now - last < cooldownMs) continue;

      // pripremi notifikaciju
      toNotify.push({
        parkingId: doc.id,
        title: `Blizu si parkinga: ${p.title || "Parking"}`,
        body: `Na ${Math.max(5, Math.round(dist))} m – slobodno ${avail}/${p.capacity || "-"}.`,
      });

      // upiši novi timestamp (debounce)
      const ref = db.collection("users").doc(uid).collection("nearby").doc(doc.id);
      batch.set(ref, { notifiedAt: now }, { merge: true });
    }

    if (toNotify.length === 0) return null;

    // pošalji jednu notifikaciju (ili više, ali bolje jednu sa najbližim)
    const best = toNotify[0];
    await fcm.send({
      token,
      notification: { title: best.title, body: best.body },
      data: {
        parkingId: best.parkingId,
        title: best.title,
        body: best.body
      },
      android: { priority: "high" },
      apns: { headers: { "apns-priority": "10" } }
    });

    await batch.commit();
    return null;
  });
