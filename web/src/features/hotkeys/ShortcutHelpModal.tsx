// Taskly Web — Shortcut Help Modal
// Shows all keyboard shortcuts in a grid
import React from 'react';
import { getDefaultBindings, type HotkeyBinding } from '../hotkeys/Hotkeys';

interface ShortcutHelpModalProps {
  onClose: () => void;
}

function formatKey(b: HotkeyBinding): string {
  const parts: string[] = [];
  if (b.ctrl) parts.push('Ctrl');
  if (b.shift) parts.push('Shift');
  if (b.alt) parts.push('Alt');
  if (b.meta) parts.push('Meta');
  parts.push(b.key.charAt(0).toUpperCase() + b.key.slice(1));
  return parts.join(' + ');
}

const ShortcutHelpModal: React.FC<ShortcutHelpModalProps> = ({ onClose }) => {
  const bindings = getDefaultBindings();
  // Group by category
  const navigation = bindings.filter((b) => ['i', 't', 'c', 's', 'j', 'k', 'Escape'].includes(b.key) || b.description.includes('Входящие') || b.description.includes('Today') || b.description.includes('Completed') || b.description.includes('Smart') || b.description.includes('Следующая') || b.description.includes('Предыдущая'));
  const actions = bindings.filter((b) => ['1', '2', '3', '4', 'd', 'r', 'f', 'm', '/', 'Delete'].includes(b.key) || b.description.includes('Приоритет') || b.description.includes('Срок') || b.description.includes('Повтор') || b.description.includes('Фильтр') || b.description.includes('Переместить') || b.description.includes('Удалить') || b.description.includes('Поиск'));
  const general = bindings.filter((b) => !navigation.includes(b) && !actions.includes(b));

  return (
    <div className="shortcut-modal-overlay" onClick={onClose}>
      <div className="shortcut-modal" onClick={(e) => e.stopPropagation()}>
        <div className="shortcut-modal-header">
          <h2 className="shortcut-modal-title">⌨️ Горячие клавиши</h2>
          <button className="shortcut-modal-close" onClick={onClose}>&times;</button>
        </div>

        <div className="shortcut-modal-body">
          <div className="shortcut-section">
            <h3 className="shortcut-section-title">Навигация</h3>
            <div className="shortcut-grid">
              {bindings.filter((b) => ['i', 't', 'c', 's', 'j', 'k', 'Escape'].includes(b.key) || b.description.includes('Входящие') || b.description.includes('Today') || b.description.includes('Completed') || b.description.includes('Smart') || b.description.includes('Следующая') || b.description.includes('Предыдущая') || b.description.includes('Закрыть')).map((b) => (
                <div key={b.key + (b.ctrl ? '-ctrl' : '') + (b.shift ? '-shift' : '')} className="shortcut-row">
                  <kbd className="shortcut-key">{formatKey(b)}</kbd>
                  <span className="shortcut-desc">{b.description}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="shortcut-section">
            <h3 className="shortcut-section-title">Действия</h3>
            <div className="shortcut-grid">
              {bindings.filter((b) => ['1', '2', '3', '4', 'd', 'r', 'f', 'm', '/', 'Delete', 'n', 'a'].includes(b.key) || b.description.includes('Приоритет') || b.description.includes('Срок') || b.description.includes('Повтор') || b.description.includes('Фильтр') || b.description.includes('Переместить') || b.description.includes('Удалить') || b.description.includes('Поиск') || b.description.includes('Новая задача') || b.description.includes('Выбрать')).map((b) => (
                <div key={b.key + (b.ctrl ? '-ctrl' : '')} className="shortcut-row">
                  <kbd className="shortcut-key">{formatKey(b)}</kbd>
                  <span className="shortcut-desc">{b.description}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="shortcut-section">
            <h3 className="shortcut-section-title">Прочее</h3>
            <div className="shortcut-grid">
              {bindings.filter((b) => b.key === '?' || b.key === 'z').map((b) => (
                <div key={b.key + (b.ctrl ? '-ctrl' : '')} className="shortcut-row">
                  <kbd className="shortcut-key">{formatKey(b)}</kbd>
                  <span className="shortcut-desc">{b.description}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ShortcutHelpModal;