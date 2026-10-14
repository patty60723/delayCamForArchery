'use strict';
const film=document.querySelector('#film'), status=document.querySelector('#status'), notice=document.querySelector('#notice');
const start=document.querySelector('#start'), welcome=document.querySelector('#welcome');
let running=false, state=null, current=null, epoch=null, generation=0, loading=false, mode=null, receivedAt=0, reconnect=false;
const say=(text)=>{if(status.textContent!==text)status.textContent=text;};
const message=(text)=>{notice.hidden=!text;if(notice.textContent!==text)notice.textContent=text;};
function hostNow(){return state.nowMs+performance.now()-receivedAt;}
function scalePicture(){
  const w=film.clientWidth,h=film.clientHeight,e=film.videoWidth/film.videoHeight;
  if(!(w>0&&h>0&&e>0)){film.style.setProperty('--scale',1);return;}
  const a=current?.aspect>0?current.aspect:e,fw=Math.min(w,h*e),pw=fw*Math.min(1,a/e),ph=fw/e*Math.min(1,e/a);
  const choice=mode||state?.pictureMode||'FIT';
  const zoom=choice==='FILL'?Math.max(w/pw,h/ph):Math.min(w/pw,h/ph);
  film.style.setProperty('--scale',Math.max(1,zoom));
  document.querySelector('#fit').setAttribute('aria-pressed',String(choice==='FIT'));
  document.querySelector('#fill').setAttribute('aria-pressed',String(choice==='FILL'));
}
function reset(){generation++;loading=false;current=null;film.pause();film.removeAttribute('src');film.load();}
async function load(segment,positionMs){
  if(loading)return;loading=true;current=segment;const version=++generation;
  try{
    film.src=`segment/${segment.id}.mp4`;film.load();
    await new Promise((resolve,reject)=>{
      const done=()=>{clearTimeout(timer);film.removeEventListener('loadedmetadata',ok);film.removeEventListener('error',fail);};
      const ok=()=>{done();resolve();},fail=()=>{done();reject(new Error('media'));};
      const timer=setTimeout(()=>{done();reject(new Error('timeout'));},8000);
      film.addEventListener('loadedmetadata',ok,{once:true});film.addEventListener('error',fail,{once:true});
    });
    if(version!==generation||!running)return;
    film.currentTime=Math.max(0,Math.min(positionMs/1000,Math.max(0,film.duration-.05)));
    scalePicture();await film.play();
    if(version!==generation)return;
    welcome.hidden=true;message('');say(`延遲觀看 · 設定 ${state.delaySec} 秒`);
  }catch(error){
    if(version!==generation)return;
    current=null;
    if(error.name==='NotAllowedError'){running=false;welcome.hidden=false;start.disabled=false;start.textContent='再次開始觀看';say('請點擊開始觀看');}
    else {reconnect=true;message('影片暫時無法播放；正在接回目前延遲。');}
  }finally{if(version===generation)loading=false;}
}
function advance(){
  if(!state||!running||loading||!state.active)return;
  const target=hostNow()-state.delaySec*1000;
  if(current&&!film.ended&&!reconnect)return;
  let segment,position=0;
  if(current&&!reconnect){
    segment=state.segments.find(s=>s.id>current.id&&s.startMs>=current.endMs-100);
    // A missing/expired segment must rejoin current source time instead of replaying stale history.
    if(segment&&segment.startMs-current.endMs>1500){reconnect=true;segment=null;}
    if(segment&&segment.startMs>target)return;
  }
  if(!segment&&current&&target-current.endMs>1500)reconnect=true;
  if(!segment&&(!current||reconnect)){
    segment=state.segments.find(s=>s.startMs<=target&&s.endMs>target);
    if(segment)position=(target-segment.startMs)/Math.max(1,segment.endMs-segment.startMs)*segment.durationMs;
  }
  if(!segment){
    const first=state.segments[0];
    const seconds=first?Math.max(0,Math.ceil((first.startMs+state.delaySec*1000-hostNow())/1000)):state.delaySec;
    message(seconds>0?`建立延遲畫面 · 約 ${seconds} 秒`:'等待下一個攝影片段');return;
  }
  reconnect=false;load(segment,position);
}
async function poll(){
  if(document.hidden)return;
  try{
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),4000);
    let response;try{response=await fetch('status',{cache:'no-store',signal:controller.signal});}finally{clearTimeout(timer);}
    if(!response.ok)throw new Error('connection');
    const next=await response.json();
    if(next.protocol!==1)throw new Error('protocol');
    if(epoch!==next.epoch||state&&state.delaySec!==next.delaySec){reset();reconnect=true;}
    state=next;epoch=next.epoch;receivedAt=performance.now();
    document.querySelector('#timing').textContent=`拍攝端延遲設定 ${state.delaySec} 秒；傳輸與播放可能增加等待。`;
    if(!state.active){reset();say('拍攝端已暫停');if(running)message('請將拍攝手機的 App 回到前景。');return;}
    if(running){scalePicture();advance();}else say('已連線 · 點擊開始觀看');
  }catch(_){
    if(running){reset();reconnect=true;message('連線中斷，正在重連。請確認拍攝端與網路。');}
    say('連線中斷 · 若拍攝端重新開啟分享，請使用新連結');
  }finally{setTimeout(poll,1000);}
}
start.addEventListener('click',()=>{running=true;start.disabled=true;reconnect=true;message('正在準備延遲畫面');advance();});
film.addEventListener('ended',advance);
film.addEventListener('playing',()=>{message('');welcome.hidden=true;});
film.addEventListener('error',()=>{if(!loading&&running){current=null;reconnect=true;message('片段已過期或暫時無法播放，正在接回。');}});
document.querySelector('#fit').addEventListener('click',()=>{mode='FIT';scalePicture();});
document.querySelector('#fill').addEventListener('click',()=>{mode='FILL';scalePicture();});
document.querySelector('#resume').addEventListener('click',()=>{reset();reconnect=true;advance();});
const toggle=document.querySelector('#toggle'),panel=document.querySelector('#panel');
toggle.addEventListener('click',()=>{panel.hidden=!panel.hidden;toggle.setAttribute('aria-expanded',String(!panel.hidden));toggle.textContent=panel.hidden?'操作':'收起';});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!panel.hidden){panel.hidden=true;toggle.setAttribute('aria-expanded','false');toggle.textContent='操作';toggle.focus();}});
document.addEventListener('visibilitychange',()=>{if(document.hidden){reset();reconnect=true;}else{advance();}});
window.addEventListener('resize',scalePicture);
poll();
