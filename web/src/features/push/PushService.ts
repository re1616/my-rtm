// Taskly Web — Push Service (scaffold)
// Web Push notification — Service Worker registration + push handling scaffold
// In R2 this will integrate with the server's Web Push API (VAPID)

export interface PushServiceState {
  supported: boolean;
  subscribed: boolean;
  registration: ServiceWorkerRegistration | null;
  subscription: PushSubscription | null;
}

let state: PushServiceState = {
  supported: false,
  subscribed: false,
  registration: null,
  subscription: null,
};

/**
 * Check if Push API is available in this browser.
 */
export function isPushSupported(): boolean {
  return 'PushManager' in window && 'serviceWorker' in navigator;
}

/**
 * Get current push service state without initializing.
 */
export function getPushState(): PushServiceState {
  return { ...state };
}

/**
 * Register the service worker and set up push notifications.
 * Returns true if registration succeeded (or was already registered).
 */
export async function registerPush(): Promise<boolean> {
  if (!isPushSupported()) {
    state.supported = false;
    return false;
  }

  state.supported = true;

  try {
    const registration = await navigator.serviceWorker.register('/sw.js', {
      scope: '/',
    });
    state.registration = registration;

    // Check if already subscribed
    const existingSubscription = await registration.pushManager.getSubscription();
    if (existingSubscription) {
      state.subscribed = true;
      state.subscription = existingSubscription;
    }

    return true;
  } catch (err) {
    console.error('Push registration failed:', err);
    return false;
  }
}

/**
 * Subscribe to push notifications.
 * In production, the subscription endpoint + keys would come from the server (VAPID).
 */
export async function subscribePush(
  publicKey?: string,
): Promise<PushSubscription | null> {
  if (!state.registration) {
    const ok = await registerPush();
    if (!ok) return null;
  }

  try {
    const subscription = await state.registration!.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: publicKey
        ? (urlBase64ToUint8Array(publicKey) as unknown as string)
        : undefined,
    });
    state.subscribed = true;
    state.subscription = subscription;

    // In production: send subscription to server
    // await fetch('/api/push/subscribe', { method: 'POST', body: JSON.stringify(subscription) });

    return subscription;
  } catch (err) {
    console.error('Push subscribe failed:', err);
    return null;
  }
}

/**
 * Unsubscribe from push notifications.
 */
export async function unsubscribePush(): Promise<boolean> {
  if (!state.subscription) return false;

  try {
    await state.subscription.unsubscribe();
    state.subscribed = false;
    state.subscription = null;

    // In production: notify server
    // await fetch('/api/push/unsubscribe', { method: 'POST' });

    return true;
  } catch (err) {
    console.error('Push unsubscribe failed:', err);
    return false;
  }
}

/**
 * Handle an incoming push event (called from service worker message handler).
 */
export function handlePushEvent(data: unknown): void {
  // In production: parse payload, show notification, update UI
  console.log('Push event received:', data);
}

/**
 * Utility: convert base64-encoded VAPID public key to Uint8Array.
 */
function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding)
    .replace(/-/g, '+')
    .replace(/_/g, '/');
  const rawData = window.atob(base64);
  return Uint8Array.from(rawData.split('').map((ch) => ch.charCodeAt(0)));
}

export default { registerPush, subscribePush, unsubscribePush, getPushState, isPushSupported };