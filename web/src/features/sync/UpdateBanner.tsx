// Taskly Web — "new version available" prompt (WEB-S8).
import React from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import type { SwUpdateController } from './swUpdateStrategy';

function controller(): SwUpdateController | undefined {
  return (window as unknown as { __tasklyUpdates?: SwUpdateController }).__tasklyUpdates;
}

const UpdateBanner: React.FC = () => {
  const ready = useStore(store, (s) => s.updateReady);
  if (!ready) return null;
  return (
    <div className="update-banner" role="status" aria-live="polite">
      <span>Доступна новая версия Taskly.</span>
      <button className="update-banner-btn" onClick={() => void controller()?.apply()}>
        Обновить
      </button>
      <button className="update-banner-btn update-banner-btn--ghost" onClick={() => controller()?.dismiss()}>
        Позже
      </button>
    </div>
  );
};

export default UpdateBanner;
