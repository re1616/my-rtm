// Taskly — Web Push handling inside the service worker (WEB-F8, SPEC FR-17).
// Imported by the generated service worker (vite.config.ts → workbox.importScripts).
const LABELS = {
  ru: { complete: 'Выполнить', snooze: 'Отложить на 1 ч', due: 'Срок' },
  en: { complete: 'Complete', snooze: 'Snooze 1 h', due: 'Due' },
};

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { title: event.data ? event.data.text() : 'Taskly' };
  }
  const l = LABELS[data.locale] || LABELS.ru;
  let body = data.body || '';
  if (!body && data.dueAt) {
    const due = new Date(data.dueAt);
    const locale = data.locale === 'en' ? 'en-GB' : 'ru-RU';
    body = `${l.due}: ${data.dueHasTime ? due.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' }) : due.toLocaleDateString(locale)}`;
  }
  const options = {
    body,
    tag: data.taskId || 'taskly',
    renotify: true,
    data: { taskId: data.taskId || null },
    actions: data.taskId
      ? [
          { action: 'complete', title: l.complete },
          { action: 'snooze', title: l.snooze },
        ]
      : [],
  };
  event.waitUntil(self.registration.showNotification(data.title || 'Taskly', options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const taskId = event.notification.data && event.notification.data.taskId;
  const action = event.action || 'open';
  const url = taskId ? `/?task=${encodeURIComponent(taskId)}&action=${encodeURIComponent(action)}` : '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      const client = clients[0];
      if (client) {
        client.postMessage({ type: 'taskly-notification', taskId, action });
        return action === 'open' ? client.focus() : undefined;
      }
      // The app applies the action itself on start (local-first, synced afterwards)
      return self.clients.openWindow(url);
    }),
  );
});
