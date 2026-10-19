"""Decode real H.264 segments in desktop Chromium; exercise viewing and recovery, never send controls."""
import argparse, json, math, os, struct, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--media', required=True)
parser.add_argument('--browser', default=os.environ.get('VIEWER_BROWSER', '/usr/bin/google-chrome'))
parser.add_argument('--output', default='browser-results')
a = parser.parse_args()
root = Path(__file__).resolve().parents[1]
assets = root/'android/app/src/main/assets/viewer'
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
media = Path(a.media).read_bytes()
def boxes(start, end):
    while start + 8 <= end:
        size, kind = struct.unpack_from('>I4s', media, start)
        header = 8
        if size == 1: size = struct.unpack_from('>Q', media, start+8)[0]; header = 16
        elif size == 0: size = end-start
        if size < header or start+size > end: raise ValueError('Invalid MP4 box')
        yield kind, start+header, start+size
        start += size
moov = next((begin,end) for kind,begin,end in boxes(0,len(media)) if kind == b'moov')
mvhd = next(begin for kind,begin,end in boxes(*moov) if kind == b'mvhd')
version = media[mvhd]
if version == 0: timescale,duration = struct.unpack_from('>II',media,mvhd+12)
elif version == 1: timescale,duration = struct.unpack_from('>IQ',media,mvhd+20)
else: raise ValueError('Unsupported MP4 movie header')
segment_ms = round(duration*1000/timescale)
if segment_ms <= 0: raise ValueError('Empty media duration')
configured_delay = max(2,math.ceil(segment_ms/1000)+1)
age_limit = configured_delay*1000+3500
print('BROWSER_MEDIA_TIMING',json.dumps(dict(segmentMs=segment_ms,delaySec=configured_delay)),flush=True)
control = dict(start=time.time()*1000, epoch='test-1', fail=False, expire=False, status_calls=0, ids=[], joins={}, latest_join=None)
base = '/v/' + 'b'*48 + '/'
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_POST(self):
        if not self.path.startswith('/join/') or len(self.path.removeprefix('/join/'))!=48:
            self.send_error(405);return
        nonce=self.path.removeprefix('/join/')
        control['joins'][nonce]='pending';control['latest_join']=nonce
        self.send_response(200);self.end_headers();self.wfile.write(b'{"state":"pending"}')
    def do_GET(self):
        if self.path in ['/', '/join.js', '/viewer.css']:
            name={'/':'join.html','/join.js':'join.js','/viewer.css':'viewer.css'}[self.path]
            self.send_response(200)
            self.send_header('Content-Type',{'join.html':'text/html','join.js':'text/javascript','viewer.css':'text/css'}[name]+'; charset=utf-8')
            self.end_headers();self.wfile.write((assets/name).read_bytes());return
        if self.path.startswith('/join/'):
            state=control['joins'].get(self.path.removeprefix('/join/'),'denied')
            data={'state':state}
            if state=='approved':data['path']=base
            self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
            self.wfile.write(json.dumps(data).encode());return
        route = self.path.removeprefix(base)
        if not self.path.startswith(base): self.send_error(404); return
        if route == 'status':
            control['status_calls'] += 1
            if control['fail']: self.send_error(503); return
            now = time.time()*1000
            total = int((now-control['start'])/segment_ms)
            data = dict(protocol=1, epoch=control['epoch'], nowMs=now, active=True, delaySec=configured_delay,
                        pictureMode='FIT', segments=[dict(id=i, startMs=control['start']+i*segment_ms,
                            endMs=control['start']+(i+1)*segment_ms, durationMs=segment_ms, aspect=4/3)
                            for i in range(max(0,total-45),total)])
            body=json.dumps(data).encode(); mime='application/json'
        elif route.startswith('segment/'):
            clip_id=int(route.split('/')[1].split('.')[0]); control['ids'].append(clip_id)
            if control['expire']: control['expire']=False; self.send_error(410); return
            body=media; mime='video/mp4'
            value=self.headers.get('Range')
            if value:
                left,right=value.removeprefix('bytes=').split('-')
                start=int(left) if left else max(0,len(body)-int(right))
                end=min(len(body)-1,int(right) if left and right else len(body)-1)
                if start>=len(body): self.send_error(416); return
                self.send_response(206); self.send_header('Content-Range',f'bytes {start}-{end}/{len(body)}')
                body=body[start:end+1]
            else: self.send_response(200)
            self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(body)))
            self.send_header('Accept-Ranges','bytes'); self.end_headers(); self.wfile.write(body); return
        else:
            name='index.html' if not route else route
            if name not in ['index.html','viewer.js','viewer.css']: self.send_error(404); return
            body=(assets/name).read_bytes(); mime={'index.html':'text/html','viewer.js':'text/javascript','viewer.css':'text/css'}[name]
        self.send_response(200); self.send_header('Content-Type',mime+'; charset=utf-8')
        self.send_header('Content-Length',str(len(body))); self.send_header('Cache-Control','no-store')
        self.end_headers(); self.wfile.write(body)
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
errors=[]; results={}
def check(name, condition):
    if not condition: raise AssertionError(name)
    results[name]=True
try:
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=a.browser,headless=True,args=['--no-sandbox'])
        page=browser.new_page(viewport=dict(width=1280,height=720))
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(f'http://127.0.0.1:{server.server_port}/')
        page.get_by_role('button',name='向手機要求觀看',exact=True).click()
        page.wait_for_function('document.querySelector("#join-status").textContent.includes("等待手機")',timeout=8000)
        check('no_media_before_phone_approval',not control['ids'])
        nonce=control['latest_join'];control['joins'][nonce]='denied'
        page.wait_for_function('!document.querySelector("#join").disabled',timeout=8000)
        check('join_denial_offers_retry','拒絕' in page.locator('#join-status').inner_text())
        page.get_by_role('button',name='向手機要求觀看',exact=True).click()
        deadline=time.monotonic()+8
        while control['latest_join']==nonce and time.monotonic()<deadline:page.wait_for_timeout(100)
        check('join_retry_uses_new_request_identity',control['latest_join']!=nonce)
        control['joins'][control['latest_join']]='approved'
        page.wait_for_url(f'**{base}',timeout=8000)
        check('approval_opens_private_viewer',True)
        control['start']=time.time()*1000;control['epoch']='fresh-after-approval'
        page.wait_for_function('epoch==="fresh-after-approval"',timeout=8000)
        page.get_by_role('button',name='開始觀看',exact=True).click()
        page.wait_for_function('!document.querySelector("#notice").hidden',timeout=5000)
        page.wait_for_function('document.querySelector("#film").videoWidth>0 && document.querySelector("#film").style.opacity==="1"',timeout=15000)
        check('h264_decodes_after_explicit_start',True)
        first=len(set(control['ids'])); watch_ms=max(8500,segment_ms*4+1500)
        page.wait_for_timeout(watch_ms)
        check('consecutive_segments_play',len(set(control['ids']))-first>=max(3,int(watch_ms/segment_ms)-1))
        check('delay_drift_bounded',page.evaluate('hostNow()-sourceTime()')<age_limit)
        page.screenshot(path=str(out/'viewer-desktop.png'))
        rect=page.locator('#stage').bounding_box()
        page.get_by_role('button',name='操作',exact=True).click()
        page.get_by_role('button',name='滿版',exact=True).click()
        check('fill_selected',page.locator('#fill').get_attribute('aria-pressed')=='true')
        check('panel_does_not_shrink_video',page.locator('#stage').bounding_box()==rect)
        page.get_by_role('button',name='完整畫面',exact=True).click()
        page.set_viewport_size(dict(width=800,height=360))
        page.get_by_role('button',name='接回目前延遲',exact=True).scroll_into_view_if_needed()
        page.screenshot(path=str(out/'viewer-short-landscape.png'))
        page.keyboard.press('Escape')
        check('escape_closes_panel_and_restores_focus',page.locator('#panel').is_hidden() and page.locator('#toggle').evaluate('(e)=>e===document.activeElement'))
        control['fail']=True
        page.wait_for_function('document.querySelector("#status").textContent.includes("連線中斷")',timeout=8000)
        check('disconnect_is_explicit',not page.locator('#notice').is_hidden())
        control['fail']=False
        page.wait_for_function('document.querySelector("#status").textContent.includes("延遲觀看") && document.querySelector("#film").style.opacity==="1"',timeout=15000)
        check('network_recovers_at_current_delay',page.evaluate('hostNow()-sourceTime()')<age_limit)
        control['expire']=True
        deadline=time.monotonic()+15
        while control['expire'] and time.monotonic()<deadline:
            page.wait_for_timeout(100)
        check('expired_response_was_exercised',not control['expire'])
        page.wait_for_function(f'!loading && current!==null && document.querySelector("#film").style.opacity==="1" && hostNow()-sourceTime()<{age_limit}',timeout=15000)
        check('expired_segment_recovers',True)
        control['epoch']='test-2'
        page.wait_for_function('epoch==="test-2" && !loading && document.querySelector("#film").style.opacity==="1"',timeout=15000)
        check('new_source_epoch_recovers',True)
        # Controlled visibility transition catches stopped polling independently of headless tab scheduling.
        page.evaluate('Object.defineProperty(document,"hidden",{configurable:true,get:()=>true});document.dispatchEvent(new Event("visibilitychange"))')
        page.wait_for_timeout(300); before=control['status_calls'];page.wait_for_timeout(1500)
        check('hidden_page_stops_polling',control['status_calls']==before)
        page.evaluate('Object.defineProperty(document,"hidden",{configurable:true,get:()=>false});document.dispatchEvent(new Event("visibilitychange"))')
        page.wait_for_function('!loading && document.querySelector("#film").style.opacity==="1"',timeout=15000)
        check('visible_page_restarts_polling',control['status_calls']>before)
        check('no_uncaught_browser_errors',not errors)
        browser.close()
finally:
    server.shutdown()
    (out/'results.json').write_text(json.dumps(dict(checks=results,browser_errors=errors),indent=2))
print('VIEWER_BROWSER_CHECKS',len(results),json.dumps(results))
