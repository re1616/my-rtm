// Taskly Web — browser notifications (WEB-F8).
// The service worker is registered by the PWA plugin (main.tsx); here the user's browser is
// subscribed to Web Push with the server's VAPID key, and notification buttons are handled.
import { getVapidKey, subscribePush, testPush, unsubscribePush, type ApiDeps } from '../../api/client';
import { completeTask, getTaskById, updateTask } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import store from '../../store/taskStore';

export type PushStatus = 'unsupported' | 'denied' | 'off' | 'on';

export function isPushSupported(): boolean {
  return typeof window !== 'undefined' && 'PushManager' in window && 'serviceWorker' in navigator && 'Notification' in window;
}

function urlBase64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  const padded = (base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(padded);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

async function registration(): Promise<ServiceWorkerRegistration> {
  return navigator.serviceWorker.ready;
}

export async function pushStatus(): Promise<PushStatus> {
  if (!isPushSupported()) return 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  const sub = await (await registration()).pushManager.getSubscription();
  return sub && Notification.permission === 'granted' ? 'on' : 'off';
}

/** Asks for permission and subscribes this browser for the signed-in account. */
export async function enablePush(deps: ApiDeps = {}): Promise<PushStatus> {
  if (!isPushSupported()) return 'unsupported';
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return permission === 'denied' ? 'denied' : 'off';
  const { publicKey } = await getVapidKey(deps);
  const reg = await registration();
  const sub =
    (await reg.pushManager.getSubscription()) ??
    (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToBytes(publicKey) }));
  const json = sub.toJSON() as { endpoint: string; keys: { p256dh: string; auth: string } };
  await subscribePush({ endpoint: json.endpoint, keys: json.keys }, deps);
  return 'on';
}

export async function disablePush(deps: ApiDeps = {}): Promise<PushStatus> {
  if (!isPushSupported()) return 'unsupported';
  const sub = await (await registration()).pushManager.getSubscription();
  if (sub) {
    await unsubscribePush(sub.endpoint, deps).catch(() => undefined);
    await sub.unsubscribe();
  }
  return 'off';
}

export async function sendTestPush(deps: ApiDeps = {}): Promise<number> {
  return (await testPush(deps)).sent;
}

/**
 * Buttons of a reminder (SPEC FR-17): "Выполнить", "Отложить на 1 ч", or a click that opens
 * the task. Applied locally first and synced like any other change.
 */
export async function handleNotificationAction(taskId: string, action: string): Promise<void> {
  const task = await getTaskById(taskId);
  if (!task) return;
  if (action === 'complete') {
    await completeTask(taskId);
  } else if (action === 'snooze') {
    const base = Math.max(Date.now(), task.dueDate ? new Date(task.dueDate).getTime() : 0);
    await updateTask(taskId, {
      dueDate: new Date(base + 3_600_000).toISOString(),
      dueHasTime: true,
      postponedCount: (task.postponedCount ?? 0) + 1,
    });
  } else {
    store.getState().openTask(taskId);
  }
  await refreshData();
}

/** Notification clicks: a message from the service worker, or the start URL (?task=…&action=…). */
export function installNotificationHandlers(): () => void {
  const params = new URLSearchParams(window.location.search);
  const taskId = params.get('task');
  if (taskId) {
    void handleNotificationAction(taskId, params.get('action') ?? 'open');
    window.history.replaceState(null, '', window.location.pathname);
  }
  if (!('serviceWorker' in navigator)) return () => {};
  const onMessage = (e: MessageEvent) => {
    const d = e.data as { type?: string; taskId?: string; action?: string };
    if (d?.type === 'taskly-notification' && d.taskId) void handleNotificationAction(d.taskId, d.action ?? 'open');
  };
  navigator.serviceWorker.addEventListener('message', onMessage);
  return () => navigator.serviceWorker.removeEventListener('message', onMessage);
}
