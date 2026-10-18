import React, { useState, useEffect } from 'react';
import type { Task, Priority } from '../../types';
import { updateTask, completeTask, uncompleteTask, softDeleteTask, addSubtask, getSubtasks } from '../../entities/taskRepository';

function renderMarkdown(text: string): string {
  let html = text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  html = html.replace(/^### (.+)$/gm,'<h4>$1</h4>').replace(/^## (.+)$/gm,'<h3>$1</h3>');
  html = html.replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>').replace(/\*(.+?)\*/g,'<em>$1</em>');
  html = html.replace(/\[(.+?)\]\((.+?)\)/g,'<a href="$2" target="_blank">$1</a>');
  html = html.replace(/`(.+?)`/g,'<code>$1</code>').replace(/\n/g,'<br/>');
  return html;
}

interface SubtaskItem { id:string; name:string; completedAt?:string; }
interface TaskCardProps { task:Task; onClose:()=>void; onTaskUpdated?:()=>void; }

const TaskCard: React.FC<TaskCardProps> = ({ task, onClose, onTaskUpdated }) => {
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(task.name);
  const [editPriority, setEditPriority] = useState(task.priority);
  const [editDueDate, setEditDueDate] = useState(task.dueDate||'');
  const [editTags, setEditTags] = useState(task.tags.join(', '));
  const [editNotes, setEditNotes] = useState(task.notes||'');
  const [editEstimate, setEditEstimate] = useState(task.estimateMin?.toString()||'');
  const [subtasks, setSubtasks] = useState<SubtaskItem[]>([]);
  const [newSubtaskName, setNewSubtaskName] = useState('');
  const [showNotesPreview, setShowNotesPreview] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => { getSubtasks(task.id).then(sts=>{setSubtasks(sts.map(s=>({id:s.id,name:s.name,completedAt:s.completedAt})));}); }, [task.id]);

  const priorityLabels: Record<number,string> = {1:'Высокий',2:'Средний',3:'Низкий'};

  function startEditing() {
    setEditName(task.name); setEditPriority(task.priority); setEditDueDate(task.dueDate?task.dueDate.slice(0,16):'');
    setEditTags(task.tags.join(', ')); setEditNotes(task.notes||''); setEditEstimate(task.estimateMin?.toString()||'');
    setEditing(true); setMessage('');
  }

  async function saveChanges() {
    const changes: Partial<Task> = {};
    if (editName!==task.name) changes.name = editName;
    if (editPriority!==task.priority) changes.priority = editPriority;
    if (editDueDate!==(task.dueDate||'')) changes.dueDate = editDueDate||undefined;
    const newTags = editTags.split(',').map(t=>t.trim()).filter(Boolean);
    if (JSON.stringify(newTags)!==JSON.stringify(task.tags)) changes.tags = newTags;
    if (editNotes!==(task.notes||'')) changes.notes = editNotes||undefined;
    const estInt = parseInt(editEstimate,10);
    const newEst = isNaN(estInt)?undefined:estInt;
    if (newEst!==task.estimateMin) changes.estimateMin = newEst;
    if (Object.keys(changes).length>0) { await updateTask(task.id,changes); setMessage('Сохранено ✓'); if(onTaskUpdated) onTaskUpdated(); }
    else setMessage('Нет изменений');
    setEditing(false);
  }

  async function handleComplete() {
    if (task.completedAt) await uncompleteTask(task.id); else await completeTask(task.id);
    setMessage(task.completedAt?'Возвращено':'✅ Выполнено'); if(onTaskUpdated) onTaskUpdated();
  }

  async function handleDelete() { if(confirm('Удалить задачу?')){ await softDeleteTask(task.id); onClose(); if(onTaskUpdated) onTaskUpdated(); }}

  async function handleAddSubtask() {
    if(!newSubtaskName.trim()) return;
    const st=await addSubtask(task.id,newSubtaskName.trim());
    setNewSubtaskName(''); subtasks.push({id:st.id,name:st.name}); setSubtasks([...subtasks]);
  }

  return (<div className="task-card">
    <div className="task-card-header">
      {editing?<input type="text" className="task-card-title-input" value={editName} onChange={e=>setEditName(e.target.value)}/>
      :<h3 className={'task-card-title'+(task.completedAt?' task-card-title--done':'')}>{(task.completedAt?'✅ ':'')+task.name}</h3>}
      <button className="task-card-close" onClick={onClose}>&times;</button>
    </div>
    <div className="task-card-actions">
      <button className="task-card-action-btn task-card-action-btn--complete" onClick={handleComplete}>{task.completedAt?'↩️ Отменить':'✅ Выполнить'}</button>
      {!editing&&<button className="task-card-action-btn task-card-action-btn--edit" onClick={startEditing}>✏️ Редактировать</button>}
      {editing&&<><button className="task-card-action-btn task-card-action-btn--save" onClick={saveChanges}>💾 Сохранить</button><button onClick={()=>{setEditing(false);setMessage('');}}>Отмена</button></>}
      <button className="task-card-action-btn task-card-action-btn--delete" onClick={handleDelete}>🗑 Удалить</button>
    </div>
    {message&&<div className="task-card-message">{message}</div>}
    <div className="task-card-body">
      <div className="task-card-field"><span className="task-card-label">Приоритет</span>
        {editing?<div>{[1,2,3].map(p=>(<button key={p} className={'priority-btn'+(editPriority===p?' priority-btn--active':'')} onClick={()=>setEditPriority(editPriority===p?null:(p as Priority))}>!{p}</button>))}{editPriority===null&&<span>—</span>}</div>
        :<span className={'task-card-value priority-badge priority-'+(task.priority||'none')}>{task.priority?'!'+task.priority+' '+priorityLabels[task.priority]:'—'}</span>}
      </div>
      <div className="task-card-field"><span className="task-card-label">Срок</span>
        {editing?<input type="datetime-local" className="task-card-input" value={editDueDate} onChange={e=>setEditDueDate(e.target.value)}/>
        :<span className="task-card-value">{task.dueDate?new Date(task.dueDate).toLocaleString():'—'}</span>}
      </div>
      <div className="task-card-field"><span className="task-card-label">Список</span><span className="task-card-value">{task.listId.replace('sys-','')}</span></div>
      <div className="task-card-field"><span className="task-card-label">Теги</span>
        {editing?<input type="text" className="task-card-input" value={editTags} onChange={e=>setEditTags(e.target.value)} placeholder="тег1, тег2"/>
        :<div className="task-card-tags">{task.tags.length>0?task.tags.map(t=>(<span key={t} className="task-card-tag">#{t}</span>)):<span className="task-card-value">—</span>}</div>}
      </div>
      <div className="task-card-field"><span className="task-card-label">Повтор</span><span className="task-card-value">{task.repeatRule||'—'}</span></div>
      <div className="task-card-field"><span className="task-card-label">Оценка</span>
        {editing?<input type="number" className="task-card-input" value={editEstimate} onChange={e=>setEditEstimate(e.target.value)} placeholder="мин" min="0"/>
        :<span className="task-card-value">{task.estimateMin?task.estimateMin+' мин':'—'}</span>}
      </div>
      <div className="task-card-field"><span className="task-card-label">Подзадачи</span>
        <div className="task-card-subtasks">
          {subtasks.length===0&&<span className="task-card-value">Нет подзадач</span>}
          {subtasks.map(st=>(<div key={st.id} className={'task-card-subtask'+(st.completedAt?' task-card-subtask--done':'')}>
            <input type="checkbox" checked={!!st.completedAt}/><span>{st.name}</span>
          </div>))}
          <div><input type="text" className="task-card-input task-card-subtask-input" value={newSubtaskName} onChange={e=>setNewSubtaskName(e.target.value)} placeholder="Добавить подзадачу..." onKeyDown={e=>{if(e.key==='Enter')handleAddSubtask();}}/><button className="task-card-small-btn" onClick={handleAddSubtask}>+</button></div>
        </div>
      </div>
      <div className="task-card-field"><span className="task-card-label">Заметки</span>
        <div className="task-card-notes">
          {editing?<><button className="task-card-small-btn" onClick={()=>setShowNotesPreview(!showNotesPreview)}>{showNotesPreview?'✏️ Правка':'👁 Предпросмотр'}</button>
            {showNotesPreview?<div className="task-card-notes-preview" dangerouslySetInnerHTML={{__html:renderMarkdown(editNotes)}}/>
            :<textarea className="task-card-notes-textarea" value={editNotes} onChange={e=>setEditNotes(e.target.value)} placeholder="Markdown..." rows={4}/>}</>
          :<div className="task-card-notes-display">{task.notes?<div dangerouslySetInnerHTML={{__html:renderMarkdown(task.notes)}}/>:<span className="task-card-value">Нет заметок</span>}</div>}
        </div>
      </div>
      <div className="task-card-field"><span className="task-card-label">История</span>
        <div className="task-card-history">
          {[{l:'Создано',ts:task.createdAt},{l:'Обновлено',ts:task.updatedAt},{l:'Завершено',ts:task.completedAt}].filter(e=>e.ts).map(e=>(
            <div key={e.l} className="task-card-history-item">{e.l}: {new Date(e.ts!).toLocaleString()}</div>
          ))}
          <div className="task-card-history-item">Версия: {task.version}</div>
        </div>
      </div>
    </div>
  </div>);
};
export default TaskCard;
