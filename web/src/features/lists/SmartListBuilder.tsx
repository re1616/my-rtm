import React, { useState, useEffect } from 'react';
import { createSmartList, getSmartLists, deleteSmartList, updateSmartList, getTasksByQuery } from '../../entities/taskRepository';
import type { TaskList, Task } from '../../types';

const QUICK_FILTERS = [
  { label: 'Приоритет 1', query: 'priority:1' }, { label: 'Приоритет 2', query: 'priority:2' },
  { label: 'Срок сегодня', query: 'due:today' }, { label: 'Просрочено', query: 'due:overdue' },
  { label: 'Повторяющиеся', query: 'isRepeating:yes' }, { label: 'С тегом', query: 'tag:' },
];

const SmartListBuilder: React.FC = () => {
  const [query, setQuery] = useState(''); const [listName, setListName] = useState('');
  const [savedLists, setSavedLists] = useState<TaskList[]>([]);
  const [previewTasks, setPreviewTasks] = useState<Task[]>([]);
  const [editingId, setEditingId] = useState<string|null>(null);
  const [queryError, setQueryError] = useState(''); const [msg, setMsg] = useState('');

  async function load() { setSavedLists(await getSmartLists()); }
  useEffect(() => { load(); }, []);

  useEffect(() => {
    if (!query.trim()) { setQueryError(''); setPreviewTasks([]); return; }
    const validFields = ['priority','due','tag','tags','list','completed','isrepeating','estimate'];
    const parts = query.split('&').map(p=>p.trim()).filter(Boolean);
    let error = '';
    for (const p of parts) {
      if (p.includes(':')) { const f = p.split(':')[0].trim().toLowerCase(); if (!validFields.includes(f)) { error = 'Неизвестное поле: '+f; break; } }
    }
    setQueryError(error);
    if (!error) getTasksByQuery(query).then(setPreviewTasks).catch(()=>{});
    else setPreviewTasks([]);
  }, [query]);

  function addChip(q: string) { setQuery(query.trim() ? query+' & '+q : q); }

  async function handleSave() {
    if (!query.trim()||!listName.trim()) { setMsg('Введите название и запрос'); return; }
    if (queryError) { setMsg('Исправьте ошибку'); return; }
    if (editingId) await updateSmartList(editingId,{name:listName,filterQuery:query});
    else await createSmartList(listName.trim(), query.trim());
    setListName(''); setQuery(''); setEditingId(null); setMsg('Сохранено ✓');
    await load();
  }

  function handleEdit(l: TaskList) { setListName(l.name); setQuery(l.filterQuery||''); setEditingId(l.id); }
  async function handleDelete(id: string) { await deleteSmartList(id); await load(); setMsg('Удалено'); }
  function handleNavigate(l: TaskList) { window.dispatchEvent(new CustomEvent('taskly-navigate',{detail:{kind:'smart',listId:l.id}})); }

  return (<div className="smart-list-builder">
    <div className="smart-list-builder-header">
      <h2 className="smart-list-builder-title">Умные списки</h2>
      <p className="smart-list-builder-desc">Динамические списки задач по фильтрам</p>
    </div>
    <div className="smart-list-builder-section">
      <h3>Быстрые фильтры</h3>
      <div className="smart-list-chips">{QUICK_FILTERS.map(item => (
        <button key={item.label} className="smart-list-chip" onClick={()=>addChip(item.query)}>{item.label}</button>
      ))}</div>
    </div>
    <div className="smart-list-builder-query-area">
      <label>Запрос фильтрации</label>
      <input type="text" className={'smart-list-builder-input'+(queryError?' smart-list-builder-input--error':'')}
        value={query} onChange={e=>setQuery(e.target.value)} placeholder='priority:1 & due:today' autoComplete="off" spellCheck={false} />
      {queryError && <div className="smart-list-builder-error">⚠️ {queryError}</div>}
      {query.trim()&&!queryError&&<div className="smart-list-builder-preview-count">Найдено: {previewTasks.length}</div>}
    </div>
    <div className="smart-list-builder-preview">
      <h3>Предпросмотр</h3>
      {previewTasks.length>0?<ul>{previewTasks.slice(0,10).map(t=>(
        <li key={t.id}>{(t.priority?'!'+t.priority+' ':'')+t.name+(t.dueDate?' — '+new Date(t.dueDate).toLocaleDateString():'')}</li>
      ))}{previewTasks.length>10&&<li>... и ещё {previewTasks.length-10}</li>}</ul>
      :<div className="smart-list-builder-empty">{query?'Нет задач':'Введите запрос'}</div>}
    </div>
    <div className="smart-list-builder-save-area">
      <input type="text" className="smart-list-builder-name-input" value={listName}
        onChange={e=>setListName(e.target.value)} placeholder="Название списка" />
      <button className="smart-list-builder-save-btn" onClick={handleSave}
        disabled={!query.trim()||!listName.trim()||!!queryError}>{editingId?'Обновить':'Сохранить'}</button>
      {editingId&&<button onClick={()=>{setEditingId(null);setListName('');setQuery('');}}>Отмена</button>}
      {msg&&<div className="smart-list-builder-message">{msg}</div>}
    </div>
    <div className="smart-list-builder-saved">
      <h3>Сохранённые списки</h3>
      {savedLists.length===0?<div className="smart-list-builder-empty">Нет сохранённых списков</div>
      :<ul>{savedLists.map(l=>(
        <li key={l.id}>
          <button className="smart-list-builder-saved-link" onClick={()=>handleNavigate(l)}>🔍 {l.name}</button>
          <span> {l.filterQuery}</span>
          <button onClick={()=>handleEdit(l)}>✏️</button>
          <button onClick={()=>handleDelete(l.id)}>🗑</button>
        </li>
      ))}</ul>}
    </div>
  </div>);
};
export default SmartListBuilder;
