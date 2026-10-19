// Taskly Web — onboarding (WEB-F11, SPEC 4.4 scenario 5): three steps — language and time
// zone, first lists from templates, notification permission — and an interactive Smart Add
// cheat sheet (type a line, see how it is understood).
import React, { useMemo, useState } from 'react';
import { useStore } from 'zustand';
import store from '../../store/taskStore';
import { updatePrefs } from '../settings/prefs';
import { createList, findListByName } from '../../entities/taskRepository';
import { refreshData } from '../app/data';
import { enablePush, isPushSupported } from '../push/PushService';
import { parseSmartAdd } from '../smartadd/parser';
import { formatDateTime, msg, t } from '../../i18n/i18n';

const TEMPLATES = [msg('Работа'), msg('Дом'), msg('Покупки'), msg('Учёба'), msg('Здоровье')];

export const CHEAT_SHEET: { syntax: string; description: string; example: string }[] = [
  { syntax: '^', description: msg('Срок'), example: msg('^завтра 17:00, ^пт, ^25 дек') },
  { syntax: '~', description: msg('Дата начала'), example: msg('~понедельник') },
  { syntax: '!', description: msg('Приоритет 1–3'), example: '!1' },
  { syntax: '#', description: msg('Список или тег (список приоритетнее)'), example: msg('#Работа #звонки') },
  { syntax: '*', description: msg('Повтор'), example: msg('*каждую неделю, *after 2 weeks') },
  { syntax: '@', description: msg('Место'), example: msg('@офис') },
  { syntax: '=', description: msg('Оценка времени'), example: msg('=30 мин') },
  { syntax: '+', description: msg('Передать контакту'), example: '+anna' },
  { syntax: '//', description: msg('Заметка'), example: msg('// уточнить бюджет') },
];

const TOKEN_TEXT: Record<string, string> = {
  dueDate: msg('срок'),
  startDate: msg('начало'),
  priority: msg('приоритет'),
  list: msg('список'),
  tag: msg('тег'),
  repeat: msg('повтор'),
  location: msg('место'),
  estimate: msg('оценка'),
  contact: msg('контакт'),
  note: msg('заметка'),
};

/** Type a line and see what Smart Add makes of it. */
export const SmartAddCheatSheet: React.FC = () => {
  const lists = useStore(store, (s) => s.lists);
  const tagNames = useStore(store, (s) => s.tagNames);
  const [sample, setSample] = useState(t('Купить молоко ^завтра 18:00 #Покупки !2 *каждую неделю'));
  const parsed = useMemo(() => {
    try {
      return sample.trim() ? parseSmartAdd(sample, { listNames: lists.filter((l) => !l.isSystem).map((l) => l.name), tagNames }) : null;
    } catch {
      return null;
    }
  }, [sample, lists, tagNames]);

  return (
    <section className="cheatsheet" aria-label={t('Шпаргалка Smart Add')}>
      <h3>{t('Шпаргалка Smart Add')}</h3>
      <table className="cheatsheet-table">
        <tbody>
          {CHEAT_SHEET.map((c) => (
            <tr key={c.syntax}>
              <td><kbd>{c.syntax}</kbd></td>
              <td>{t(c.description)}</td>
              <td><code>{t(c.example)}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
      <label className="cheatsheet-try">
        <span>{t('Попробуйте:')}</span>
        <input className="settings-input" value={sample} onChange={(e) => setSample(e.target.value)} aria-label={t('Строка для разбора')} />
      </label>
      {parsed && (
        <dl className="cheatsheet-result" aria-live="polite">
          <dt>{t('Название')}</dt>
          <dd>{parsed.name || '—'}</dd>
          {parsed.tokens.map((tok, i) => (
            <React.Fragment key={i}>
              <dt>{t(TOKEN_TEXT[tok.type] ?? tok.type)}</dt>
              <dd>{tok.type === 'dueDate' && parsed.dueDate ? formatDateTime(new Date(parsed.dueDate)) : tok.value}</dd>
            </React.Fragment>
          ))}
        </dl>
      )}
    </section>
  );
};

const OnboardingFlow: React.FC = () => {
  const prefs = useStore(store, (s) => s.prefs);
  const session = useStore(store, (s) => s.session);
  const [step, setStep] = useState(prefs?.onboarded ? 3 : 0);
  const [templates, setTemplates] = useState<string[]>([TEMPLATES[0], TEMPLATES[1]]);
  const [pushResult, setPushResult] = useState<string | null>(null);
  if (!prefs) return null;

  async function finish(skip = false) {
    for (const name of skip ? [] : templates) {
      const local = t(name);
      if (!(await findListByName(local))) await createList(local);
    }
    await updatePrefs({ onboarded: true });
    await refreshData();
    store.getState().setCurrentView({ kind: 'system', view: 'inbox' });
  }

  const steps = [t('Язык и часовой пояс'), t('Первые списки'), t('Уведомления')];

  if (step === 3) {
    return (
      <div className="onboarding">
        <SmartAddCheatSheet />
        <button type="button" className="settings-btn settings-btn--ghost" onClick={() => setStep(0)}>
          {t('Пройти знакомство заново')}
        </button>
      </div>
    );
  }

  return (
    <div className="onboarding">
      <div className="onboarding-card" role="group" aria-label={t('Шаг {n} из 3: {title}', { n: step + 1, title: steps[step] })}>
        <ol className="onboarding-steps">
          {steps.map((s, i) => (
            <li key={s} className={i === step ? 'onboarding-step--active' : i < step ? 'onboarding-step--done' : ''} aria-current={i === step ? 'step' : undefined}>
              {s}
            </li>
          ))}
        </ol>

        {step === 0 && (
          <div className="onboarding-body">
            <h2>{t('Добро пожаловать в Taskly')}</h2>
            <label className="settings-row">
              <span className="settings-row-label">{t('Язык интерфейса')}</span>
              <select className="settings-select" value={prefs.locale} onChange={(e) => void updatePrefs({ locale: e.target.value as 'ru' | 'en' })}>
                <option value="ru">{LANGUAGE_NAMES.ru}</option>
                <option value="en">{LANGUAGE_NAMES.en}</option>
              </select>
            </label>
            <label className="settings-row">
              <span className="settings-row-label">{t('Часовой пояс')}</span>
              <input className="settings-input" value={prefs.timeZone} readOnly aria-describedby="tz-hint" />
            </label>
            <p id="tz-hint" className="settings-hint">{t('Определён по системе; изменить можно в настройках.')}</p>
          </div>
        )}

        {step === 1 && (
          <div className="onboarding-body">
            <h2>{t('С каких списков начать?')}</h2>
            {TEMPLATES.map((name) => (
              <label key={name} className="settings-row">
                <input
                  type="checkbox"
                  checked={templates.includes(name)}
                  onChange={(e) => setTemplates(e.target.checked ? [...templates, name] : templates.filter((x) => x !== name))}
                />
                <span>{t(name)}</span>
              </label>
            ))}
            <p className="settings-hint">{t('«Входящие» есть всегда — туда попадает всё без списка.')}</p>
          </div>
        )}

        {step === 2 && (
          <div className="onboarding-body">
            <h2>{t('Напоминания')}</h2>
            <p>{t('Taskly напомнит о задачах уведомлением в браузере и на телефоне.')}</p>
            {session && isPushSupported() ? (
              <button
                type="button"
                className="settings-btn"
                onClick={() =>
                  void enablePush().then(
                    (st) => setPushResult(st === 'on' ? t('Уведомления включены') : t('Уведомления не разрешены')),
                    () => setPushResult(t('Не удалось включить уведомления')),
                  )
                }
              >
                {t('Разрешить уведомления')}
              </button>
            ) : (
              <p className="settings-hint">{t('Войдите в аккаунт, чтобы получать напоминания; включить их можно позже в настройках.')}</p>
            )}
            {pushResult && <p role="status">{pushResult}</p>}
            <SmartAddCheatSheet />
          </div>
        )}

        <div className="onboarding-actions">
          {step > 0 && (
            <button type="button" className="settings-btn settings-btn--ghost" onClick={() => setStep(step - 1)}>
              {t('Назад')}
            </button>
          )}
          <button type="button" className="settings-btn settings-btn--ghost" onClick={() => void finish(true)}>
            {t('Пропустить')}
          </button>
          {step < 2 ? (
            <button type="button" className="settings-btn" onClick={() => setStep(step + 1)}>
              {t('Далее')}
            </button>
          ) : (
            <button type="button" className="settings-btn" onClick={() => void finish()}>
              {t('Начать работу')}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

/** Each language is named in itself. */
const LANGUAGE_NAMES = { ru: 'Русский', en: 'English' }; // i18n-ignore

export default OnboardingFlow;
