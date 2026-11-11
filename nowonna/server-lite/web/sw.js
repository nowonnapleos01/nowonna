// 나온나 알림: 서버가 보낸 푸시를 휴대폰 알림창에 띄우고, 누르면 픽업 화면을 연다.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (e) => {
  let m = {};
  try { m = e.data ? e.data.json() : {}; } catch { m = { title: '나온나', body: e.data ? e.data.text() : '' }; }
  e.waitUntil(self.registration.showNotification(m.title || '나온나', {
    body: m.body || '',
    tag: m.tag || 'nowonna', // 같은 픽업 알림은 하나로 바꿔 끼운다
    renotify: true, // 바꿔 끼워도 다시 소리·진동
    requireInteraction: true, // 사용자가 볼 때까지 남겨 둔다
    vibrate: [180, 90, 180],
    data: { url: m.url || '/' },
  }));
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const url = new URL(e.notification.data && e.notification.data.url || '/', self.location.origin).href;
  e.waitUntil((async () => {
    const list = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const open = list.find((c) => c.url.split('#')[0] === url);
    if (open) return open.focus();
    return self.clients.openWindow(url);
  })());
});
