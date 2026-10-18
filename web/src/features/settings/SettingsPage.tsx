// Taskly Web — Settings Page (shell)
// Account, timezone, language, theme, 2FA, export, delete account
import React from 'react';
import SecuritySettings from './SecuritySettings';

const SettingsPage: React.FC = () => {
  return (
    <div className="settings-page">
      <div className="settings-page-header">
        <h2 className="settings-page-title">Настройки</h2>
      </div>

      <div className="settings-sections">
        {/* Account */}
        <div className="settings-section">
          <h3 className="settings-section-title">Аккаунт</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-row-label">Email</span>
              <span className="settings-row-value settings-stub">user@example.com</span>
            </div>
            <div className="settings-row">
              <span className="settings-row-label">Имя</span>
              <span className="settings-row-value settings-stub">Пользователь</span>
            </div>
          </div>
        </div>

        {/* Timezone */}
        <div className="settings-section">
          <h3 className="settings-section-title">Часовой пояс</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-row-label">Часовой пояс</span>
              <span className="settings-row-value settings-stub">Europe/Moscow (MSK)</span>
            </div>
          </div>
        </div>

        {/* Language */}
        <div className="settings-section">
          <h3 className="settings-section-title">Язык</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-row-label">Язык интерфейса</span>
              <span className="settings-row-value settings-stub">Русский</span>
            </div>
          </div>
        </div>

        {/* Theme */}
        <div className="settings-section">
          <h3 className="settings-section-title">Тема оформления</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-row-label">Тема</span>
              <span className="settings-row-value settings-stub">Светлая</span>
            </div>
            <div className="settings-row settings-stub-row">
              <span className="settings-stub-hint">Тёмная тема будет доступна в R2</span>
            </div>
          </div>
        </div>

        {/* 2FA */}
        <div className="settings-section">
          <h3 className="settings-section-title">Двухфакторная аутентификация</h3>
          <div className="settings-section-body">
            <SecuritySettings />
          </div>
        </div>

        {/* Export */}
        <div className="settings-section">
          <h3 className="settings-section-title">Экспорт данных</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-stub-hint">
                Экспорт задач и списков в CSV/JSON будет доступен в R2
              </span>
            </div>
          </div>
        </div>

        {/* Delete account */}
        <div className="settings-section settings-section--danger">
          <h3 className="settings-section-title">Удаление аккаунта</h3>
          <div className="settings-section-body">
            <div className="settings-row">
              <span className="settings-stub-hint">
                Удаление аккаунта и всех данных будет доступно в R2
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SettingsPage;