
const CACHE='daybook-static-v5';

const FILES=[
  './',
  './index.html',
  './style.css',
  './app.js',
  './manifest.json',
  './icon.svg'
];

self.addEventListener('install',e=>{
  e.waitUntil(
    caches.open(CACHE).then(c=>c.addAll(FILES))
  );
  self.skipWaiting();
});

self.addEventListener('activate',e=>{
  e.waitUntil(
    caches.keys().then(keys=>
      Promise.all(
        keys.filter(k=>k.startsWith('daybook-static-')&&k!==CACHE)
          .map(k=>caches.delete(k))
      )
    ).then(()=>self.clients.claim())
  );
});

self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET'||new URL(e.request.url).origin!==location.origin)return;

  e.respondWith(
    caches.match(e.request).then(c=>
      c||fetch(e.request).then(r=>{
        if(r.ok){
          const copy=r.clone();
          caches.open(CACHE).then(cache=>cache.put(e.request,copy));
        }
        return r;
      }).catch(()=>c)
    )
  );
});
