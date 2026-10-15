'use strict';
const button=document.querySelector('#join'),status=document.querySelector('#join-status');
let timer=null,id=null;
async function check(){
  try{
    const response=await fetch(`/join/${id}`,{cache:'no-store',signal:AbortSignal.timeout(4000)});
    if(!response.ok)throw new Error('connection');
    const result=await response.json();
    if(result.state==='approved'&&/^\/v\/[0-9a-f]{48}\/$/.test(result.path)){location.replace(result.path);return;}
    if(result.state==='denied'){status.textContent='手機已拒絕觀看。需要時可重新提出要求。';button.disabled=false;return;}
    status.textContent='等待手機允許，請核對兩邊的代碼。';timer=setTimeout(check,1000);
  }catch(_){status.textContent='連線中斷或要求已過期。請確認手機仍開啟觀看連線，再重試。';button.disabled=false;}
}
button.addEventListener('click',async()=>{
  clearTimeout(timer);button.disabled=true;
  id=Array.from(crypto.getRandomValues(new Uint8Array(24)),n=>n.toString(16).padStart(2,'0')).join('');
  document.querySelector('#code').textContent=`核對代碼：${id.slice(0,6).toUpperCase()}`;
  try{
    const response=await fetch(`/join/${id}`,{method:'POST',signal:AbortSignal.timeout(4000)});
    if(!response.ok)throw new Error('connection');
    check();
  }catch(_){status.textContent='無法提出要求，請確認手機仍開啟觀看連線，或稍後重試。';button.disabled=false;}
});
window.addEventListener('pagehide',()=>clearTimeout(timer));
