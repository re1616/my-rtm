// Taskly Web — Onboarding Flow (scaffold)
// 3-step onboarding + Smart Add cheat sheet
import React, { useState } from 'react';

const STEPS = [
  {
    title: 'Добро пожаловать в Taskly!',
    content: 'Taskly — это мощный планировщик задач с поддержкой Smart Add, умных списков и синхронизации между устройствами.',
    icon: '👋',
  },
  {
    title: 'Smart Add — быстрый ввод',
    content: 'Используйте символы для быстрого ввода: ^дата #список !приоритет *повтор @место =оценка //заметка',
    icon: '⚡',
  },
  {
    title: 'Готово к работе!',
    content: 'Начните с добавления первой задачи. Используйте поле ввода сверху или нажмите + в боковой панели.',
    icon: '🚀',
  },
];

const CHEAT_SHEET = [
  { syntax: '^завтра / ^today', description: 'Срок выполнения' },
  { syntax: '!1 / !2 / !3', description: 'Приоритет (высокий/средний/низкий)' },
  { syntax: '#Работа / #важно', description: 'Список или тег' },
  { syntax: '*каждый день / *every week', description: 'Повторение' },
  { syntax: '@офис / @home', description: 'Место / локация' },
  { syntax: '=30 мин / =2ч', description: 'Оценка времени' },
  { syntax: '~пн / ~monday', description: 'Дата начала' },
  { syntax: '// заметка', description: 'Заметка к задаче' },
  { syntax: '+имя', description: 'Контакт / исполнитель' },
];

const OnboardingFlow: React.FC = () => {
  const [step, setStep] = useState(0);
  const [showCheatSheet, setShowCheatSheet] = useState(false);

  function handleNext() {
    if (step < STEPS.length - 1) {
      setStep(step + 1);
    }
  }

  function handlePrev() {
    if (step > 0) {
      setStep(step - 1);
    }
  }

  function handleFinish() {
    // In production: mark onboarding as completed, navigate to inbox
    window.dispatchEvent(
      new CustomEvent('taskly-navigate', {
        detail: { kind: 'system', view: 'inbox' },
      }),
    );
  }

  if (showCheatSheet) {
    return (
      <div className="onboarding">
        <div className="onboarding-cheat-sheet">
          <div className="onboarding-header">
            <h2 className="onboarding-title">Шпаргалка Smart Add</h2>
            <button
              className="onboarding-back-btn"
              onClick={() => setShowCheatSheet(false)}
            >
              ← Назад
            </button>
          </div>
          <div className="onboarding-cheat-grid">
            {CHEAT_SHEET.map((item, i) => (
              <div key={i} className="onboarding-cheat-item">
                <code className="onboarding-cheat-syntax">{item.syntax}</code>
                <span className="onboarding-cheat-desc">{item.description}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="onboarding">
      <div className="onboarding-card">
        <div className="onboarding-step-indicator">
          {STEPS.map((_, i) => (
            <span
              key={i}
              className={`onboarding-dot ${i === step ? 'onboarding-dot--active' : ''} ${i < step ? 'onboarding-dot--done' : ''}`}
            />
          ))}
        </div>

        <div className="onboarding-step-content">
          <div className="onboarding-step-icon">{STEPS[step].icon}</div>
          <h2 className="onboarding-step-title">{STEPS[step].title}</h2>
          <p className="onboarding-step-text">{STEPS[step].content}</p>
        </div>

        <div className="onboarding-actions">
          {step < STEPS.length - 1 ? (
            <>
              <button className="onboarding-skip-btn" onClick={handleFinish}>
                Пропустить
              </button>
              <button className="onboarding-next-btn" onClick={handleNext}>
                Далее →
              </button>
            </>
          ) : (
            <button className="onboarding-finish-btn" onClick={handleFinish}>
              Начать работу!
            </button>
          )}
        </div>

        <button
          className="onboarding-cheat-link"
          onClick={() => setShowCheatSheet(true)}
        >
          📖 Шпаргалка по Smart Add
        </button>
      </div>
    </div>
  );
};

export default OnboardingFlow;