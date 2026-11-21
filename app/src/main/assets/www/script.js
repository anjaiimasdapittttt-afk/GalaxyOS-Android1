(()=>{
'use strict';
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const p2=n=>String(n).padStart(2,'0');
const B=window.AndroidBridge||null; /* null saat dibuka di browser biasa */
const nat=(f,...a)=>{try{return JSON.parse(B[f](...a))}catch(e){return null}};
const NA={}; /* tombol tidak didukung / status tidak terbaca (ditampilkan redup, bukan status palsu) */
const WALLS=[
 'radial-gradient(circle at 20% 15%,#00e5c3aa,#0000 45%),radial-gradient(circle at 85% 75%,#1e6bffcc,#0000 50%),#02040a',
 'radial-gradient(circle at 80% 10%,#7c5cffbb,#0000 45%),radial-gradient(circle at 15% 80%,#00c2ffaa,#0000 50%),#050514',
 'radial-gradient(circle at 50% 120%,#19e6c1cc,#0000 60%),radial-gradient(circle at 10% 10%,#0b4a8f,#0000 50%),#020a0f',
 'linear-gradient(160deg,#001f3f,#02040a 45%,#0a3d3a)'];
const ACC=[['#19e6c1','#2b7bff'],['#5aa2ff','#8a6bff'],['#3ddc84','#00b3a4']];
const store={get(k,d){try{const v=JSON.parse(localStorage.getItem(k));return v==null?d:v}catch(e){return d}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}};
let S=Object.assign({wifi:true,bt:false,air:false,dark:true,wall:0,acc:0,bri:100,sort:'def',torch:false,loc:false},store.get('gos_s',{}));

/* ---------- Pengaturan sistem ---------- */
function apply(){
 $('#wall').style.background=WALLS[S.wall];
 document.body.classList.toggle('light',!S.dark);
 const r=document.documentElement.style;r.setProperty('--ac',ACC[S.acc][0]);r.setProperty('--ac2',ACC[S.acc][1]);
 $('#dim').style.opacity=B?0:(100-S.bri)/100*.75;$('#bri').value=S.bri;
 $('#iAir').hidden=!S.air;$('#iBt').hidden=!S.bt;$('#iWifi').style.display=S.wifi?'':'none';$('#sig').classList.toggle('off',S.air);
 $$('.tile').forEach(t=>{const k=t.dataset.k;t.classList.toggle('na',!!NA[k]);t.classList.toggle('on',!!S[k]&&!NA[k])});
 store.set('gos_s',S);
}
function toggle(k){
 const lbl={wifi:'Wi-Fi',bt:'Bluetooth',dark:'Mode gelap',air:'Mode pesawat',torch:'Senter',loc:'Lokasi'}[k];
 if(B&&k!=='dark'){
  if(NA[k]==='unsupported'){toast(lbl+' tidak tersedia di perangkat ini');return}
  const SYS={
   wifi:['openWifiPanel','Android tidak mengizinkan aplikasi mengubah Wi-Fi langsung. Membuka panel Wi-Fi…'],
   air:['openAirplaneModeSettings','Mode pesawat hanya bisa diubah dari pengaturan sistem. Membuka pengaturan…'],
   loc:['openLocationSettings','Lokasi hanya bisa diubah dari pengaturan sistem. Membuka pengaturan…']};
  if(SYS[k]){nat(SYS[k][0]);toast(SYS[k][1]);return}
  if(k==='bt'){
   if(S.bt){nat('openBluetoothSettings');toast('Android tidak mengizinkan aplikasi mematikan Bluetooth. Membuka pengaturan Bluetooth…')}
   else{const r=nat('requestBluetoothEnable');if(!r||!r.ok)toast('Permintaan Bluetooth ditolak oleh aplikasi')}
   return}
  if(k==='torch'){ /* status tombol mengikuti kondisi senter yang sebenarnya (callback Android) */
   const E={unsupported:'Senter tidak tersedia di perangkat ini',unavailable:'Senter sedang dipakai aplikasi lain',permission:'Perangkat ini menolak akses senter tanpa izin kamera',untrusted:'Akses ditolak'};
   const r=nat('toggleFlashlight');if(!r||!r.ok)toast(E[r&&r.error]||'Senter tidak dapat diubah');
   return}
 }
 S[k]=!S[k];
 if(!B){if(k==='air'&&S.air){S.wifi=false;S.bt=false}if((k==='wifi'||k==='bt')&&S[k]&&S.air)S.air=false}
 apply();toast(lbl+(S[k]?' aktif':' mati'));
}

/* ---------- Jam, baterai ---------- */
function tick(){
 const d=new Date(),t=p2(d.getHours())+':'+p2(d.getMinutes());
 ['#sTime','#lTime','#wTime'].forEach(s=>$(s).textContent=t);
 const ds=d.toLocaleDateString('id-ID',{weekday:'long',day:'numeric',month:'long'});
 $('#lDate').textContent=ds;$('#wDate').textContent=ds;
}
function setBat(v,ch){$('#bat em').style.width=v+'%';$('#bat').classList.toggle('low',v<=20&&!ch);$('#batN').textContent=Math.round(v)+(ch?'⚡':'')}
let level=87;setBat(level,false);
if(B){}else if(navigator.getBattery)navigator.getBattery().then(b=>{const u=()=>setBat(b.level*100,b.charging);u();b.onlevelchange=b.onchargingchange=u}).catch(()=>{});
else setInterval(()=>setBat(level=Math.max(5,level-1),false),60000);

/* ---------- Toast & notifikasi ---------- */
let tt;
function toast(m){const t=$('#toast');t.textContent=m;t.classList.add('on');clearTimeout(tt);tt=setTimeout(()=>t.classList.remove('on'),Math.min(5000,1800+m.length*45))}
const chkEmpty=()=>{const l=$('#nlist');if(l.children.length)delete l.dataset.e;else l.dataset.e='1'};
function notify(app,msg,quiet){
 const n=document.createElement('div'),d=new Date();n.className='n';n.innerHTML='<b></b><span></span>';
 n.firstChild.textContent=app+' • '+p2(d.getHours())+':'+p2(d.getMinutes());n.lastChild.textContent=msg;
 n.onclick=()=>{n.remove();chkEmpty()};$('#nlist').prepend(n);chkEmpty();if(!quiet)toast(app+': '+msg);
}
$('#clear').onclick=()=>{$('#nlist').innerHTML='';chkEmpty();toast('Notifikasi dihapus')};

/* ---------- Quick Settings & panel tarik ---------- */
$('#tiles').innerHTML=[['wifi','Wi-Fi','📶'],['bt','Bluetooth','ᛒ'],['dark','Mode Gelap','🌙'],['air','Pesawat','✈️'],['torch','Senter','🔦'],['loc','Lokasi','📍']]
 .map(([k,l,i])=>`<button class="tile" data-k="${k}"><b>${i}</b>${l}</button>`).join('');
$('#tiles').onclick=e=>{const t=e.target.closest('.tile');if(t)toggle(t.dataset.k)};
let briNote=false;
$('#bri').oninput=e=>{S.bri=+e.target.value;if(B){nat('setBrightness',S.bri);if(!briNote){briNote=true;toast('Kecerahan hanya berlaku untuk layar aplikasi ini, bukan seluruh sistem')}}apply()};
$('#sysBri').onclick=()=>{if(B){nat('openBrightnessSettings');toast('Membuka pengaturan layar sistem…')}else toast('Pengaturan sistem hanya tersedia di aplikasi Android')};
const P=$('#panel');let open=false;
function setP(o){open=o;P.style.transition='';P.style.transform=o?'translateY(0)':'translateY(-105%)';$('#scrim').classList.toggle('on',o)}
function dragP(el){
 let y0=0,moved=0,on=false;
 el.addEventListener('pointerdown',e=>{y0=e.clientY;moved=0;on=true;el.setPointerCapture(e.pointerId);P.style.transition='none'});
 el.addEventListener('pointermove',e=>{if(!on)return;const dy=e.clientY-y0,H=P.offsetHeight*1.05;moved=Math.max(moved,Math.abs(dy));
  P.style.transform=`translateY(${Math.min(0,Math.max(-H,(open?0:-H)+dy))}px)`});
 el.addEventListener('pointerup',e=>{if(!on)return;on=false;const dy=e.clientY-y0;if(moved<8)setP(!open);else setP(open?dy>-80:dy>80)});
}
dragP($('#grab'));dragP($('#phandle'));
$('#scrim').onclick=()=>setP(false);

/* ---------- Lock screen ---------- */
const L=$('#lock');let ly=null;
const unlock=()=>L.classList.add('gone');
function lock(){setP(false);goHome();L.classList.remove('gone')}
L.addEventListener('pointerdown',e=>{ly=e.clientY;L.style.transition='none';L.setPointerCapture(e.pointerId)});
L.addEventListener('pointermove',e=>{if(ly===null)return;const dy=Math.min(0,e.clientY-ly);L.style.transform=`translateY(${dy}px)`;L.style.opacity=1+dy/500});
L.addEventListener('pointerup',e=>{if(ly===null)return;const dy=e.clientY-ly;ly=null;L.style.transition='';L.style.transform='';L.style.opacity='';if(dy<-110||Math.abs(dy)<8)unlock()});
$('#lockBtn').onclick=lock;

/* ---------- Aplikasi ---------- */
const APPS=[
 {id:'calc',n:'Kalkulator',i:'🧮',c:'linear-gradient(135deg,#19e6c1,#0a8f9c)'},
 {id:'notes',n:'Catatan',i:'📝',c:'linear-gradient(135deg,#ffcf4a,#ff8a3d)'},
 {id:'set',n:'Pengaturan',i:'⚙️',c:'linear-gradient(135deg,#5aa2ff,#2b4fd8)'}];
const appBtn=a=>`<button class="app" data-id="${a.id}"><i style="background:${a.c}">${a.i}</i>${a.n}</button>`;
$('#grid').innerHTML=APPS.map(appBtn).join('');
const W=$('#win');let cur=null;const VIEWS={};
const bar=(t,x='')=>`<div class="bar"><span>${t}</span>${x}</div>`;
function openApp(id,el){
 const r=el.getBoundingClientRect(),pr=$('#phone').getBoundingClientRect();
 W.style.transformOrigin=`${r.left+r.width/2-pr.left}px ${r.top+r.height/2-pr.top}px`;
 setD(false);W.onclick=null;W.innerHTML='';cur=id;VIEWS[id](W);W.hidden=false;W.className='open';$('#home').classList.add('back');
}
function goHome(){
 setD(false);if(!cur)return;cur=null;W.className='close';$('#home').classList.remove('back');
 setTimeout(()=>{if(!cur){W.hidden=true;W.className='';W.innerHTML=''}},290);
}
$('#grid').onclick=e=>{const b=e.target.closest('.app');if(b)openApp(b.dataset.id,b.querySelector('i'))};
$('#nav').onclick=()=>{setP(false);goHome()};

/* App Drawer */
const SORTS=['def','az','za'],SL={def:'Urutan awal',az:'A–Z',za:'Z–A'};
const D=$('#drawer');let dOpen=false;
function drawDrawer(){
 if(!SORTS.includes(S.sort))S.sort='def';
 const q=$('#dq').value.trim().toLowerCase();
 const l=APPS.filter(a=>a.n.toLowerCase().includes(q));
 if(S.sort!=='def')l.sort((a,b)=>a.n.localeCompare(b.n,'id')*(S.sort==='za'?-1:1));
 $('#dgrid').innerHTML=l.map(appBtn).join('')||'<p class="empty">Aplikasi tidak ditemukan</p>';
 $('#dsort').textContent='⇅ '+SL[S.sort];
}
function setD(o){
 if(o===dOpen)return;dOpen=o;D.classList.toggle('on',o);$('#home').classList.toggle('back',o||!!cur);
 if(o){$('#dq').value='';drawDrawer()}else $('#dq').blur();
}
$('#dBtn').onclick=()=>setD(true);
$('#dq').oninput=drawDrawer;
$('#dsort').onclick=()=>{S.sort=SORTS[(SORTS.indexOf(S.sort)+1)%3];apply();drawDrawer();toast('Urutan: '+SL[S.sort])};
$('#dgrid').onclick=e=>{const b=e.target.closest('.app');if(b)openApp(b.dataset.id,b.querySelector('i'))};
let hy=null;
$('#home').addEventListener('pointerdown',e=>{hy=e.clientY});
$('#home').addEventListener('pointerup',e=>{if(hy!==null&&e.clientY-hy<-60)setD(true);hy=null});
let dy0=null;const DH=$('#dhandle');
DH.addEventListener('pointerdown',e=>{dy0=e.clientY;DH.setPointerCapture(e.pointerId)});
DH.addEventListener('pointerup',e=>{if(dy0===null)return;const d=e.clientY-dy0;dy0=null;if(d>40||Math.abs(d)<8)setD(false)});

/* Kalkulator */
VIEWS.calc=w=>{
 const K=[['C','fn'],['⌫','fn'],['%','fn'],['÷','op'],['7'],['8'],['9'],['×','op'],['4'],['5'],['6'],['−','op'],['1'],['2'],['3'],['+','op'],['±','fn'],['0'],['.'],['=','op']];
 w.innerHTML=bar('Kalkulator')+`<div class="calc"><div class="cexp"></div><div class="cres">0</div><div class="keys">${K.map(([k,c])=>`<button data-k="${k}" class="${c||''}">${k}</button>`).join('')}</div></div>`;
 let cur='0',prev=null,op=null,fresh=false,expr='';
 const R=w.querySelector('.cres'),X=w.querySelector('.cexp');
 const fix=n=>isFinite(n)?String(+n.toPrecision(12)):'Error';
 const run=(a,b,o)=>o==='+'?a+b:o==='−'?a-b:o==='×'?a*b:b===0?NaN:a/b;
 const show=()=>{R.textContent=cur;X.textContent=expr;R.style.fontSize=cur.length>9?'40px':cur.length>6?'52px':''};
 w.querySelector('.keys').onclick=e=>{
  const b=e.target.closest('button');if(!b)return;const k=b.dataset.k;
  if(cur==='Error'&&k!=='C')return;
  if(/^\d$/.test(k)){if(fresh){cur='0';fresh=false}if(cur.replace(/[-.]/g,'').length<12)cur=cur==='0'?k:cur+k}
  else if(k==='.'){if(fresh){cur='0';fresh=false}if(!cur.includes('.'))cur+='.'}
  else if(k==='C'){cur='0';prev=op=null;expr='';fresh=false}
  else if(k==='±'){if(cur!=='0')cur=cur[0]==='-'?cur.slice(1):'-'+cur}
  else if(k==='%'){cur=fix(parseFloat(cur)/100)}
  else if(k==='⌫'){if(fresh)return;cur=cur.length>1&&!(cur.length===2&&cur[0]==='-')?cur.slice(0,-1):'0'}
  else if(k==='='){if(op){const r=fix(run(prev,parseFloat(cur),op));expr=prev+' '+op+' '+cur+' =';cur=r;op=prev=null;fresh=true}}
  else{
   if(op&&!fresh){const r=fix(run(prev,parseFloat(cur),op));if(r==='Error'){cur=r;op=prev=null;expr='';show();return}cur=r}
   prev=parseFloat(cur);op=k;expr=cur+' '+k;fresh=true;
  }
  show();
 };
};

/* Catatan (localStorage) */
VIEWS.notes=w=>{
 let arr=store.get('gos_notes',[]);const sv=()=>store.set('gos_notes',arr);
 const list=()=>{
  w.innerHTML=bar('Catatan','<button class="ib" id="add">＋</button>')+`<div class="body">${arr.length?arr.map(n=>`<div class="note" data-id="${n.id}"><b></b><p></p><small>${n.d}</small></div>`).join(''):'<p class="empty">Belum ada catatan.<br>Ketuk ＋ untuk membuat catatan baru.</p>'}</div>`;
  w.querySelectorAll('.note').forEach(el=>{const n=arr.find(x=>String(x.id)===el.dataset.id);el.querySelector('b').textContent=n.t||'Tanpa judul';el.querySelector('p').textContent=n.b;el.onclick=()=>edit(n)});
  w.querySelector('#add').onclick=()=>{const n={id:Date.now(),t:'',b:'',d:''};arr.unshift(n);edit(n)};
 };
 const edit=n=>{
  w.innerHTML='<div class="bar"><button class="ib" id="bk">←</button><span></span><button class="ib" id="del">🗑</button></div><div class="body"><input class="inp" id="nt" placeholder="Judul" maxlength="80"><textarea class="inp" id="nb" placeholder="Tulis catatan..."></textarea></div>';
  const t=w.querySelector('#nt'),b=w.querySelector('#nb');t.value=n.t;b.value=n.b;
  const up=()=>{n.t=t.value;n.b=b.value;n.d=new Date().toLocaleString('id-ID',{day:'numeric',month:'short',hour:'2-digit',minute:'2-digit'});sv()};
  t.oninput=b.oninput=up;
  w.querySelector('#bk').onclick=()=>{if(!n.t.trim()&&!n.b.trim())arr=arr.filter(x=>x!==n);sv();list();toast('Catatan tersimpan')};
  w.querySelector('#del').onclick=()=>{arr=arr.filter(x=>x!==n);sv();list();toast('Catatan dihapus')};
 };
 list();
};

/* Pengaturan */
VIEWS.set=w=>{
 const draw=()=>{w.innerHTML=bar('Pengaturan')+`<div class="body"><div class="sec">Wallpaper</div><div class="walls">${WALLS.map((x,i)=>`<button data-w="${i}" class="${i===S.wall?'sel':''}" style="background:${x}"></button>`).join('')}</div>
 <div class="sec">Warna aksen</div><div class="dots">${ACC.map((a,i)=>`<button data-a="${i}" class="${i===S.acc?'sel':''}" style="background:linear-gradient(135deg,${a[0]},${a[1]})"></button>`).join('')}</div>
 <div class="sec">Tampilan</div><div class="row">Mode gelap<button class="sw ${S.dark?'on':''}" data-k="dark"></button></div><div class="row">Wi-Fi<button class="sw ${S.wifi?'on':''}" data-k="wifi"></button></div>
 <div class="sec">Sistem</div><div class="row"><span>Setel ulang semua data</span><button class="ib" data-r="1">↺</button></div><div class="row"><span>Galaxy OS Simulator v2.0</span></div></div>`};
 draw();
 w.onclick=e=>{
  const b=e.target.closest('button');if(!b)return;const d=b.dataset;
  if(d.w){S.wall=+d.w;apply()}else if(d.a){S.acc=+d.a;apply()}else if(d.k)toggle(d.k);
  else if(d.r){if(confirm('Hapus semua catatan dan pengaturan?')){try{localStorage.clear()}catch(_){}location.reload()}return}
  else return;
  draw();
 };
};

/* ---------- Mulai ---------- */
/* ---------- Jembatan Android (AndroidBridge) ---------- */
let briInit=false,warned=false;
function refresh(){
 if(!B)return;const st=nat('getStatus');
 const unk=k=>{NA[k]='unknown';S[k]=false};
 if(!st){['wifi','bt','air','loc','torch'].forEach(unk);apply();return}
 const dev=(k,o,on)=>{if(o&&o.supported===false){NA[k]='unsupported';S[k]=false}else if(!o||o.error){unk(k)}else{delete NA[k];S[k]=!!on}};
 dev('wifi',st.wifi,st.wifi&&st.wifi.enabled);dev('bt',st.bt,st.bt&&st.bt.enabled);dev('torch',st.torch,st.torch&&st.torch.on);
 ['air','loc'].forEach(k=>{if(typeof st[k]==='boolean'){delete NA[k];S[k]=st[k]}else unk(k)});
 if(st.battery&&st.battery.error==null&&typeof st.battery.level==='number')setBat(st.battery.level,st.battery.charging);
 if(!briInit&&typeof st.brightness==='number'){S.bri=Math.max(20,Math.min(100,st.brightness));briInit=true}
 const bad=Object.keys(NA).find(k=>NA[k]==='unknown');
 if(bad&&!warned){warned=true;toast('Status '+bad+' tidak dapat dibaca, jadi tombolnya diredupkan')}
 apply();
}
window.gosRefresh=refresh;
window.gosNotice=m=>toast(String(m));
window.gosBack=()=>{if(open){setP(false);return true}if(dOpen){setD(false);return true}if(cur){goHome();return true}return false};
apply();tick();setInterval(tick,1000);refresh();
notify('Galaxy OS','Selamat datang di Galaxy OS Simulator',true);
notify('Pengaturan','Ganti wallpaper dan warna aksen di Pengaturan',true);
})();
