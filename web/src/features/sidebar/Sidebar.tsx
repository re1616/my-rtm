import React from 'react';
import type { AppView } from '../../types';
import store from '../../store/taskStore';
import AuthPanel from '../sync/AuthPanel';

const systemViews: { id:string; label:string; icon:string; view:AppView; }[] = [
  { id:'inbox', label:'Входящие', icon:'📥', view:{kind:'system',view:'inbox'} },
  { id:'today', label:'Сегодня', icon:'📅', view:{kind:'system',view:'today'} },
  { id:'overdue', label:'Просрочено', icon:'⚠️', view:{kind:'system',view:'overdue'} },
  { id:'week', label:'Неделя', icon:'📆', view:{kind:'system',view:'week'} },
  { id:'all', label:'Все задачи', icon:'📋', view:{kind:'system',view:'all'} },
  { id:'completed', label:'Выполненные', icon:'✅', view:{kind:'system',view:'completed'} },
];

const Sidebar: React.FC<{currentView:AppView}> = ({currentView}) => {
  const s = store.getState(); const tasks = s.tasks; const lists = s.lists;
  function countForView(view: AppView): number {
    if (view.kind==='system') return view.view==='completed'?tasks.filter(t=>!!t.completedAt).length:tasks.filter(t=>!t.completedAt).length;
    if (view.kind==='list'||view.kind==='smart') return tasks.filter(t=>t.listId===view.listId&&!t.completedAt).length;
    return 0;
  }
  function isActive(view: AppView): boolean {
    if (currentView.kind!==view.kind) return false;
    if (view.kind==='system') return currentView.kind==='system'&&currentView.view===view.view;
    if (view.kind==='list') return currentView.kind==='list'&&currentView.listId===view.listId;
    if (view.kind==='smart') return currentView.kind==='smart'&&currentView.listId===view.listId;
    return false;
  }
  function navigateTo(view: AppView) { s.setCurrentView(view); window.dispatchEvent(new CustomEvent('taskly-navigate',{detail:view})); }
  function isPageActive(kind: string): boolean { return currentView.kind===kind; }

  const allTags: string[] = [...new Set(tasks.flatMap(t=>t.tags))].sort();
  const locations: string[] = tasks.filter(t=>t.notes&&t.notes.includes('@loc:')).map(t=>{const m=t.notes!.match(/@loc:(\S+)/);return m?m[1]:''}).filter(Boolean).filter((v,i,a)=>a.indexOf(v)===i).sort() as string[];
  const smartLists = lists.filter(l=>l.isSmart&&!l.archived);
  const userLists = lists.filter(l=>!l.isSystem&&!l.isSmart&&!l.archived);

  return (<nav className="sidebar">
    <div className="sidebar-header"><h1 className="sidebar-logo">Taskly</h1></div>
    <div className="sidebar-section"><div className="sidebar-section-title">Представления</div>
      {systemViews.map(sv=>(<button key={sv.id} className={'sidebar-item'+(isActive(sv.view)?' sidebar-item--active':'')}
        onClick={()=>navigateTo(sv.view)}><span className="sidebar-item-icon">{sv.icon}</span><span className="sidebar-item-label">{sv.label}</span>
        <span className="sidebar-item-count">{countForView(sv.view)}</span></button>))}
    </div>
    {userLists.length>0&&<div className="sidebar-section"><div className="sidebar-section-title">Списки</div>
      {userLists.map(list=>{const lv:AppView={kind:'list',listId:list.id};return(
        <button key={list.id} className={'sidebar-item'+(isActive(lv)?' sidebar-item--active':'')}
          onClick={()=>navigateTo(lv)}><span className="sidebar-item-icon">{list.color?'●':'○'}</span>
          <span className="sidebar-item-label">{list.name}</span><span className="sidebar-item-count">{countForView(lv)}</span></button>
      );})}
    </div>}
    {smartLists.length>0&&<div className="sidebar-section"><div className="sidebar-section-title">🔍 Умные списки</div>
      {smartLists.map(list=>{const lv:AppView={kind:'smart',listId:list.id};return(
        <button key={list.id} className={'sidebar-item'+(isActive(lv)?' sidebar-item--active':'')}
          onClick={()=>navigateTo(lv)}><span className="sidebar-item-icon">🔍</span><span className="sidebar-item-label">{list.name}</span>
          <span className="sidebar-item-count">{countForView(lv)}</span></button>
      );})}
    </div>}
    {allTags.length>0&&<div className="sidebar-section"><div className="sidebar-section-title"># Теги</div>
      {allTags.map(tag=>(<button key={tag} className="sidebar-item sidebar-item--tag" onClick={()=>navigateTo({kind:'system',view:'all'})}>
        <span className="sidebar-item-icon">#</span><span className="sidebar-item-label">{tag}</span>
        <span className="sidebar-item-count">{tasks.filter(t=>t.tags.includes(tag)&&!t.completedAt).length}</span></button>))}
    </div>}
    {locations.length>0&&<div className="sidebar-section"><div className="sidebar-section-title">@ Локации</div>
      {locations.map(loc=>(<button key={loc} className="sidebar-item sidebar-item--location" onClick={()=>navigateTo({kind:'system',view:'all'})}>
        <span className="sidebar-item-icon">@</span><span className="sidebar-item-label">{loc}</span></button>))}
    </div>}
    <div className="sidebar-footer">
      <AuthPanel />
      <div className="sidebar-sync-status">{s.isOnline?'🔗 В сети':'📢 Офлайн'}</div>
      <div className="sidebar-nav-links">
        <button className={'sidebar-nav-link'+(isPageActive('smart-lists')?' sidebar-item--active':'')} onClick={()=>navigateTo({kind:'smart-lists'})}>🔍 Умные списки</button>
        <button className={'sidebar-nav-link'+(isPageActive('settings')?' sidebar-item--active':'')} onClick={()=>navigateTo({kind:'settings'})}>⚙️ Настройки</button>
        <button className={'sidebar-nav-link'+(isPageActive('onboarding')?' sidebar-item--active':'')} onClick={()=>navigateTo({kind:'onboarding'})}>🎓 Обучение</button>
        <button className={'sidebar-nav-link'+(isPageActive('help')?' sidebar-item--active':'')} onClick={()=>navigateTo({kind:'help'})}>? Шпаргалка</button>
      </div>
    </div>
  </nav>);
};
export default Sidebar;
