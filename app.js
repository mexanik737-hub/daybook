
const STORAGE_KEY='daybook-v1';
const DRIVE_FILE='daybook-data.json';
const SYNC_BASE_KEY='daybook-sync-base-v1';
const GOOGLE_CLIENT_ID='731569027724-g32icn6rg67mun8vrvjti68metjv6tnm.apps.googleusercontent.com';

let state={tasks:[],notes:[]},view='home',taskFilter='all',noteFilter='all',tokenClient=null,accessToken=null,toastTimer=null;

const $=id=>document.getElementById(id);
const HOME_TEMPLATE=$('content').innerHTML;
const uid=()=>crypto.randomUUID?crypto.randomUUID():Date.now().toString(36)+Math.random().toString(36).slice(2);
const today=()=>new Date().toISOString().slice(0,10);

function load(){
  try{
    const v=JSON.parse(localStorage.getItem(STORAGE_KEY));
    if(v&&Array.isArray(v.tasks)&&Array.isArray(v.notes))state=v;
  }catch{}
}

function persist(){
  localStorage.setItem(STORAGE_KEY,JSON.stringify(state));
  render();
  $('footerSync').textContent=accessToken?'Синхронизация с Google Drive включена':'Данные хранятся локально';
}

function escapeHtml(s=''){
  return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function showToast(msg){
  const t=$('toast');
  t.textContent=msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>t.classList.remove('show'),2600);
}

function dateLabel(){
  const d=new Date();
  $('dateLabel').textContent=d.toLocaleDateString('ru-RU',{weekday:'long',day:'numeric',month:'long'}).toUpperCase();
}

function setView(v){
  if(!['home','tasks','notes'].includes(v)||v===view)return;
  view=v;
  render();
  document.querySelectorAll('[data-view]').forEach(b=>{
    const active=b.dataset.view===view;
    b.classList.toggle('active',active);
    if(b.matches('button'))b.setAttribute('aria-current',active?'page':'false');
  });
}

function taskRow(t){
  return `<div class="task-row"><button class="task-check ${t.done?'checked':''}" data-check="${t.id}" aria-label="Отметить выполненной">${t.done?'✓':''}</button><div class="task-title ${t.done?'done':''}" data-edit-task="${t.id}">${escapeHtml(t.title||'Без названия')}</div><div class="task-meta">${t.due?escapeHtml(t.due):''}</div><span class="priority ${t.priority||'normal'}"></span></div>`;
}

function noteCard(n){
  return `<button class="note-card" data-edit-note="${n.id}"><h3>${escapeHtml(n.title||'Без названия')}</h3><p>${escapeHtml(n.body||'Пустая заметка')}</p><span class="note-date">${new Date(n.updated||Date.now()).toLocaleDateString('ru-RU',{day:'numeric',month:'short'})}</span></button>`;
}

function empty(msg){
  return `<div class="empty-state">${msg}</div>`;
}

function renderHome(){
  const tasks=state.tasks.filter(t=>!t.done).sort((a,b)=>(a.due||'9999').localeCompare(b.due||'9999')).slice(0,4);
  $('homeTasks').innerHTML=tasks.length?tasks.map(taskRow).join(''):empty('Задач пока нет. Добавь первую — и она появится здесь.');

  const notes=[...state.notes].sort((a,b)=>(b.updated||0)-(a.updated||0)).slice(0,3);
  $('homeNotes').innerHTML=notes.length?notes.map(noteCard).join(''):empty('Заметок пока нет. Сохрани первую мысль.');
}

function renderTasks(){
  const q=$('searchInput').value.toLowerCase();
  let list=state.tasks.filter(t=>(t.title+' '+t.body).toLowerCase().includes(q));

  if(taskFilter==='active')list=list.filter(t=>!t.done);
  if(taskFilter==='done')list=list.filter(t=>t.done);

  list.sort((a,b)=>Number(a.done)-Number(b.done)||(a.due||'9999').localeCompare(b.due||'9999'));

  $('content').innerHTML=`<div class="eyebrow">ПЛАНИРОВАНИЕ</div><h1 class="view-title">Задачи</h1><div class="view-toolbar"><div class="filter-tabs"><button data-task-filter="all" class="${taskFilter==='all'?'active':''}">Все</button><button data-task-filter="active" class="${taskFilter==='active'?'active':''}">Активные</button><button data-task-filter="done" class="${taskFilter==='done'?'active':''}">Выполненные</button></div><button class="primary-button" id="addTaskPage">＋ Новая задача</button></div><div class="page-list">${list.length?list.map(taskRow).join(''):empty('Ничего не найдено')}</div>`;
}

function renderNotes(){
  const q=$('searchInput').value.toLowerCase();
  let list=[...state.notes].filter(n=>(n.title+' '+n.body).toLowerCase().includes(q)).sort((a,b)=>(b.updated||0)-(a.updated||0));

  $('content').innerHTML=`<div class="eyebrow">ТВОИ МЫСЛИ</div><h1 class="view-title">Заметки</h1><div class="view-toolbar"><span class="task-meta">${list.length} ${list.length===1?'заметка':'заметок'}</span><button class="primary-button" id="addNotePage">＋ Новая заметка</button></div><div class="page-notes">${list.length?list.map(noteCard).join(''):empty('Ничего не найдено')}</div>`;
}

function render(){
  if(view==='home'&&!$('homeTasks'))$('content').innerHTML=HOME_TEMPLATE;
  if(view==='home')renderHome();
  else if(view==='tasks')renderTasks();
  else renderNotes();

  $('taskCount').textContent=state.tasks.filter(t=>!t.done).length;

  document.querySelectorAll('[data-view]').forEach(b=>{
    const active=b.dataset.view===view;
    b.classList.toggle('active',active);
    if(b.matches('button'))b.setAttribute('aria-current',active?'page':'false');
  });
}

function openEditor(type,id=null){
  const item=id?(type==='task'?state.tasks:state.notes).find(x=>x.id===id):null;

  $('entryId').value=id||'';
  $('editorEyebrow').textContent=id?'РЕДАКТИРОВАНИЕ':type==='task'?'НОВАЯ ЗАДАЧА':'НОВАЯ ЗАМЕТКА';
  $('editorTitle').textContent=type==='task'?'Задача':'Заметка';
  $('entryTitle').value=item?.title||'';
  $('entryBody').value=item?.body||'';
  $('taskFields').classList.toggle('hidden',type!=='task');
  $('entryDue').value=item?.due||'';
  $('entryPriority').value=item?.priority||'normal';
  $('deleteEntry').classList.toggle('hidden',!item);
  $('editorDialog').dataset.type=type;
  $('editorDialog').showModal();
  $('entryTitle').focus();
}

function closeEditor(){
  $('editorDialog').close();
}

$('editorForm').addEventListener('submit',e=>{
  e.preventDefault();

  const type=$('editorDialog').dataset.type;
  const id=$('entryId').value;
  const title=$('entryTitle').value.trim();
  const body=$('entryBody').value.trim();

  if(!title&&!body){
    showToast('Добавь название или текст');
    return;
  }

  const arr=type==='task'?state.tasks:state.notes;
  let item=id?arr.find(x=>x.id===id):null;

  if(!item){
    item={id:uid(),created:Date.now()};
    arr.unshift(item);
  }

  item.title=title||'Без названия';
  item.body=body;
  item.updated=Date.now();

  if(type==='task'){
    item.due=$('entryDue').value;
    item.priority=$('entryPriority').value;
    item.done=item.done||false;
  }

  persist();
  closeEditor();
  showToast(type==='task'?'Задача сохранена':'Заметка сохранена');
});

$('deleteEntry').addEventListener('click',()=>{
  const type=$('editorDialog').dataset.type;
  const id=$('entryId').value;
  if(!id)return;

  const arr=type==='task'?state.tasks:state.notes;
  const i=arr.findIndex(x=>x.id===id);

  if(i>=0)arr.splice(i,1);

  persist();
  closeEditor();
  showToast('Удалено');
});

$('closeDialog').onclick=closeEditor;
$('cancelDialog').onclick=closeEditor;

document.addEventListener('click',e=>{
  const b=e.target.closest('[data-view]');
  if(b){
    setView(b.dataset.view);
    return;
  }

  const check=e.target.closest('[data-check]');
  if(check){
    const t=state.tasks.find(x=>x.id===check.dataset.check);
    if(t){
      t.done=!t.done;
      t.updated=Date.now();
      persist();
    }
    return;
  }

  const et=e.target.closest('[data-edit-task]');
  if(et){
    openEditor('task',et.dataset.editTask);
    return;
  }

  const en=e.target.closest('[data-edit-note]');
  if(en){
    openEditor('note',en.dataset.editNote);
    return;
  }

  const tf=e.target.closest('[data-task-filter]');
  if(tf){
    taskFilter=tf.dataset.taskFilter;
    renderTasks();
    return;
  }

  if(e.target.closest('#quickTask,#addTaskPage')){openEditor('task');return;}
  if(e.target.closest('#quickNote,#newNoteTop,#addNotePage')){openEditor('note');return;}
});

$('searchInput').addEventListener('input',()=>{
  if(view==='tasks')renderTasks();
  if(view==='notes')renderNotes();

  if(view==='home'){
    const q=$('searchInput').value.trim();
    if(q){
      view='notes';
      renderNotes();
    }
  }
});

function setSyncUI(connected){
  const status=connected?'<span class="status-dot online"></span><span>Google Drive подключён</span>':'<span class="status-dot"></span><span>Только на этом устройстве</span>';
  $('syncStatus').innerHTML=status;
  $('driveButton').innerHTML=connected?'<span class="nav-icon">☁</span>Синхронизировать':'<span class="nav-icon">☁</span>Подключить Google Drive';
  $('topSyncStatus').innerHTML=connected?'<span class="status-dot online"></span><span>Drive подключён</span>':'<span class="status-dot"></span><span>Локально</span>';
  $('driveButtonTop').innerHTML=connected?'☁ <span>Синхр.</span>':'☁ <span>Drive</span>';
}

function driveInit(){
  if(GOOGLE_CLIENT_ID.startsWith('YOUR_')){
    showToast('Для Drive нужно настроить Google OAuth Client ID');
    return;
  }

  if(!window.google?.accounts?.oauth2){
    showToast('Google API ещё загружается. Попробуй через секунду.');
    return;
  }

  tokenClient=google.accounts.oauth2.initTokenClient({
    client_id:GOOGLE_CLIENT_ID,
    scope:'https://www.googleapis.com/auth/drive.appdata',
    callback:async(resp)=>{
      if(resp.error){
        showToast('Не удалось подключить Google Drive');
        return;
      }

      accessToken=resp.access_token;
      setSyncUI(true);
      await syncDrive();
    }
  });

  tokenClient.requestAccessToken({prompt:'consent'});
}

async function driveFetch(path,opts={}){
  const r=await fetch('https://www.googleapis.com/drive/v3/'+path,{
    ...opts,
    headers:{
      Authorization:'Bearer '+accessToken,
      ...(opts.headers||{})
    }
  });

  if(!r.ok)throw new Error('Drive API: '+r.status);

  return r.status===204?null:r.json();
}

async function syncDrive(){
  if(!accessToken)return;

  try{
    const files=await driveFetch('files?spaces=appDataFolder&fields=files(id,name)&pageSize=100');
    const file=files.files.find(f=>f.name===DRIVE_FILE);
    const localJSON=JSON.stringify(state);
    const localEmpty=!state.tasks.length&&!state.notes.length;

    async function writeRemote(fileId,data){
      const boundary='daybook_'+uid();

      const metadata=fileId
        ?{name:DRIVE_FILE,mimeType:'application/json'}
        :{name:DRIVE_FILE,mimeType:'application/json',parents:['appDataFolder']};

      const body=
        '--'+boundary+'\r\n'+
        'Content-Type: application/json; charset=UTF-8\r\n\r\n'+
        JSON.stringify(metadata)+'\r\n'+
        '--'+boundary+'\r\n'+
        'Content-Type: application/json\r\n\r\n'+
        JSON.stringify(data)+'\r\n'+
        '--'+boundary+'--';

      const url=fileId
        ?'https://www.googleapis.com/upload/drive/v3/files/'+fileId+'?uploadType=multipart'
        :'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id';

      const response=await fetch(url,{
        method:fileId?'PATCH':'POST',
        headers:{
          Authorization:'Bearer '+accessToken,
          'Content-Type':'multipart/related; boundary='+boundary
        },
        body
      });

      if(!response.ok)throw new Error('Drive upload: '+response.status);
    }

    if(!file){
      await writeRemote(null,state);
      localStorage.setItem(SYNC_BASE_KEY,localJSON);
      showToast('Данные сохранены в Google Drive');
      $('footerSync').textContent='Синхронизация с Google Drive включена';
      return;
    }

    const remote=await driveFetch('files/'+file.id+'?alt=media');

    if(!remote||!Array.isArray(remote.tasks)||!Array.isArray(remote.notes)){
      throw new Error('Некорректный формат данных в Drive');
    }

    const remoteJSON=JSON.stringify(remote);
    const baseJSON=localStorage.getItem(SYNC_BASE_KEY);

    if(!baseJSON){
      if(localEmpty){
        state=remote;
        localStorage.setItem(STORAGE_KEY,remoteJSON);
        localStorage.setItem(SYNC_BASE_KEY,remoteJSON);
        render();
        showToast('Данные загружены из Google Drive');
        $('footerSync').textContent='Синхронизация с Google Drive включена';
        return;
      }

      if(localJSON===remoteJSON){
        localStorage.setItem(SYNC_BASE_KEY,localJSON);
        showToast('Данные уже синхронизированы');
        $('footerSync').textContent='Синхронизация с Google Drive включена';
        return;
      }

      showToast('Конфликт данных: автоматическая запись остановлена');
      return;
    }

    const localChanged=localJSON!==baseJSON;
    const remoteChanged=remoteJSON!==baseJSON;

    if(localChanged&&remoteChanged&&localJSON!==remoteJSON){
      showToast('Конфликт изменений. Данные не перезаписаны');
      return;
    }

    if(remoteChanged&&!localChanged){
      state=remote;
      localStorage.setItem(STORAGE_KEY,remoteJSON);
      localStorage.setItem(SYNC_BASE_KEY,remoteJSON);
      render();
      showToast('Получены изменения из Google Drive');
      $('footerSync').textContent='Синхронизация с Google Drive включена';
      return;
    }

    if(localChanged&&!remoteChanged){
      await writeRemote(file.id,state);
      localStorage.setItem(SYNC_BASE_KEY,localJSON);
      showToast('Изменения отправлены в Google Drive');
      $('footerSync').textContent='Синхронизация с Google Drive включена';
      return;
    }

    localStorage.setItem(SYNC_BASE_KEY,localJSON);
    showToast('Данные уже синхронизированы');
    $('footerSync').textContent='Синхронизация с Google Drive включена';

  }catch(err){
    console.error(err);
    showToast('Ошибка синхронизации. Локальные данные сохранены');
  }
}

function driveAction(){
  if(accessToken)syncDrive();
  else driveInit();
}
$('driveButton').addEventListener('click',driveAction);
$('driveButtonTop').addEventListener('click',driveAction);

window.addEventListener('online',()=>{
  if(accessToken)syncDrive();
});

load();
dateLabel();
render();
setSyncUI(false);

if('serviceWorker' in navigator&&location.protocol.startsWith('http')){
  navigator.serviceWorker.register('./sw.js').catch(()=>{});
}
