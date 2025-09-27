const functions = require("firebase-functions");
const admin = require("firebase-admin");

try { admin.initializeApp(); } catch (e) {}

const db = admin.firestore();
const fcm = admin.messaging();

// Haversine (rezultat u metrima)
function distanceMeters(aLat, aLng, bLat, bLng) {
  const toRad = (x) => x * Math.PI / 180;
  const R = 6371000;
  const dLat = toRad(bLat - aLat);
  const dLng = toRad(bLng - aLng);
  const sa = Math.sin(dLat/2) ** 2 +
    Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLng/2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(sa));
}

async function sendFCM({ token, title, body, data }) {
  if (!token) return;
  try {
    await fcm.send({
      token,
      notification: { title, body },
      data: Object.fromEntries(Object.entries(data || {}).map(([k, v]) => [k, String(v)])),
      android: { priority: "high" },
      apns: { headers: { "apns-priority": "10" } },
    });
  } catch (e) {
    console.error("FCM send error", e);
  }
}

/**
 * Trigger: svaka promena na users/{uid}
 * Očekuje polja: lat, lng, fcmToken (opciono)
 * Logika:
 *  - Nađe aktivne parkinge sa availableSlots > 0 u krugu ≤ 150 m
 *  - Cooldown po korisniku i parkingu: 2h (users/{uid}/nearby/{parkingId}.notifiedAt)
 */
exports.notifyNearbyParking = functions.firestore
  .document("users/{uid}")
  .onWrite(async (change, context) => {
    const uid = context.params.uid;
    const after = change.after.exists ? change.after.data() : null;
    if (!after) return null;

    const lat = after.lat, lng = after.lng;
    if (typeof lat !== "number" || typeof lng !== "number") return null;

    const token = after.fcmToken;
    if (!token) return null;

    // Učitaj do 200 parkinga koji su aktivni
    const ps = await db.collection("parkings")
      .where("isActive", "==", true)
      .limit(200)
      .get();

    const now = Date.now();
    const radiusM = 150;

    const candidates = [];

    for (const doc of ps.docs) {
      const p = doc.data() || {};
      const pLat = p.lat, pLng = p.lng;
      const avail = Number.isFinite(p.availableSlots) ? p.availableSlots : 0;
      if (!Number.isFinite(pLat) || !Number.isFinite(pLng)) continue;
      if (avail <= 0) continue;

      const dist = distanceMeters(lat, lng, pLat, pLng);
      if (dist > radiusM) continue;

      // cooldown
      const nrRef = db.collection("users").doc(uid).collection("nearby").doc(doc.id);
      const nr = await nrRef.get();
      const last = nr.exists ? (nr.get("notifiedAt") || 0) : 0;
      const cooldownMs = 2 * 60 * 60 * 1000; // 2h
      if (now - last < cooldownMs) continue;

      candidates.push({
        parkingId: doc.id,
        title: p.title || "Parking",
        capacity: Number.isFinite(p.capacity) ? p.capacity : 0,
        available: avail,
        dist,
        ref: nrRef,
      });
    }

    if (candidates.length === 0) return null;

    // najbliži
    candidates.sort((a, b) => a.dist - b.dist);
    const best = candidates[0];

    await sendFCM({
      token,
      title: `Blizu si parkinga: ${best.title}`,
      body: `Na ${Math.max(5, Math.round(best.dist))} m – slobodno ${best.available}/${best.capacity}.`,
      data: {
        type: "nearby_parking",
        parkingId: best.parkingId,
        distMeters: Math.round(best.dist),
      },
    });

    await best.ref.set({ notifiedAt: now }, { merge: true });
    return null;
  });

/**
 * Trigger: svaka promena na users/{uid}
 * Očekuje polja: lat, lng, fcmToken (na primaocu), displayName (opciono)
 * Logika:
 *  - Nađe druge korisnike u krugu ≤ 150 m
 *  - Cooldown po paru (A vidi B): users/{A}/nearbyUsers/{B}.notifiedAt = 2h
 *  - NOTIFIKACIJU ŠALJEMO KORISNIKU KOJI SE POMERIO (uid) o onima koji su blizu njega
 */
exports.notifyNearbyUser = functions.firestore
  .document("users/{uid}")
  .onWrite(async (change, context) => {
    const uid = context.params.uid;
    const after = change.after.exists ? change.after.data() : null;
    if (!after) return null;

    const lat = after.lat, lng = after.lng;
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;

    // primaoc mora imati token
    const receiverToken = after.fcmToken;
    if (!receiverToken) return null;

    // Učitaj do N drugih korisnika (filtar po postojanju fcmToken-a nije uvek moguć – fallback na limit)
    const qs = await db.collection("users").limit(300).get();

    const now = Date.now();
    const radiusM = 150;

    const candidates = [];
    for (const doc of qs.docs) {
      if (doc.id === uid) continue; // preskoči sebe
      const u = doc.data() || {};
      const uLat = u.lat, uLng = u.lng;
      if (!Number.isFinite(uLat) || !Number.isFinite(uLng)) continue;

      const dist = distanceMeters(lat, lng, uLat, uLng);
      if (dist > radiusM) continue;

      // cooldown: A (uid) vidi B (doc.id)
      const keyRef = db.collection("users").doc(uid)
        .collection("nearbyUsers").doc(doc.id);
      const snap = await keyRef.get();
      const last = snap.exists ? (snap.get("notifiedAt") || 0) : 0;
      const cooldownMs = 2 * 60 * 60 * 1000; // 2h
      if (now - last < cooldownMs) continue;

      candidates.push({
        otherUid: doc.id,
        otherName: u.displayName || u.name || "korisnik",
        dist,
        ref: keyRef,
      });
    }

    if (candidates.length === 0) return null;

    candidates.sort((a, b) => a.dist - b.dist);
    const best = candidates[0];

    await sendFCM({
      token: receiverToken,
      title: `Korisnik u blizini: ${best.otherName}`,
      body: `Na oko ${Math.max(5, Math.round(best.dist))} metara od tebe.`,
      data: {
        type: "nearby_user",
        userId: best.otherUid,
        distMeters: Math.round(best.dist),
      },
    });

    await best.ref.set({ notifiedAt: now }, { merge: true });
    return null;
  });
