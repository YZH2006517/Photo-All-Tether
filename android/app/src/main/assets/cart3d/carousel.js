// A row of films (?kind=film: the cartridges) or papers (?kind=paper: their boxes and cans) to
// swipe through. The one in the middle stands full size and sways a little; its neighbours stand
// back, smaller, turned toward it. A swipe carries on with the finger's speed and settles on the
// nearest one with a spring; while moving, the models lean and turn with the speed.
// The app drives it through window.carousel:
//   init(json, index)   the items (looks) and the one in the middle
//   go(index, animate)  bring that one to the middle (the app chose it elsewhere)
//   pause(on)           stop drawing while the page is out of sight
// and hears through window.CarouselBridge: ready(), center(i) (passing the middle: a tick),
// select(i) (settled on a new one), open(i) (a tap on the one in the middle).
// ?bottom=0.86 stands the middle one's foot that far down the view (NDC), to line it up with the
// page; otherwise it is centred.
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  const kind = q.get('kind') === 'paper' ? 'paper' : 'film';
  // ?foot=N: the foot stands N px above the view's bottom edge (the view runs on below it, so
  // a turning model's corners and its shadow are not cut)
  const footPx = q.has('foot') ? parseFloat(q.get('foot')) : null;
  let bottom = q.has('bottom') ? parseFloat(q.get('bottom')) : null;
  const bridge = window.CarouselBridge;

  function vw() { return window.innerWidth || document.documentElement.clientWidth || 1; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight || 1; }
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
  renderer.setClearColor(0x000000, 0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(vw(), vh());
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.08;
  document.body.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(26, vw() / vh(), 1, 6000);
  // the same soft studio as the cartridge's own stage
  (function () {
    const room = new THREE.Scene();
    room.add(new THREE.Mesh(new THREE.BoxGeometry(12, 8, 12), new THREE.MeshBasicMaterial({ color: 0x2a2826, side: THREE.BackSide })));
    function panel(w, h, x, y, z, ry, rx, k, color) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(k) }));
      m.position.set(x, y, z); m.rotation.set(rx || 0, ry || 0, 0); room.add(m);
    }
    panel(5, 2.5, -3.5, 2.5, 3, Math.PI * 0.75, -0.3, 6, 0xfff3e2);
    panel(3, 5, 5.9, 0.5, 0, -Math.PI / 2, 0, 2.2, 0xdde8ff);
    panel(10, 2, 0, 3.9, 0, 0, Math.PI / 2, 1.5, 0xffffff);
    panel(4, 1.2, 0, -1, 5.9, Math.PI, 0, 0.9, 0xffe6c8);
    const pm = new THREE.PMREMGenerator(renderer);
    scene.environment = pm.fromScene(room, 0.03).texture;
    pm.dispose();
  })();
  const key = new THREE.DirectionalLight(0xfff2e0, 2.1);
  key.position.set(-60, 90, 70);
  scene.add(key);
  scene.add(new THREE.HemisphereLight(0xfff6ea, 0x3a332c, 0.35));

  // ------------------------------------------------------------------ what each kind is like
  // spacing: between neighbours (scene units); size: the item's width the views fit to;
  // floor: where an item stands; elev: the camera's angle above the horizon
  const K = kind === 'film'
    ? { spacing: 46, size: 40, high: 54, floor: -24.5, front: 12, elev: 0.05, restYaw: -0.5, pitch: 0.12, sway: 0.3, turn: 0.55 }
    : { spacing: 60, size: 56, high: 34, floor: 0, front: 22, elev: 0.72, restYaw: -0.22, pitch: 0, sway: 0.16, turn: 0.3 };

  const shadowTex = (function () {
    const c = document.createElement('canvas'); c.width = c.height = 128;
    const g = c.getContext('2d');
    const gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    gr.addColorStop(0, 'rgba(0,0,0,0.5)'); gr.addColorStop(0.5, 'rgba(0,0,0,0.2)'); gr.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
    return new THREE.CanvasTexture(c);
  })();

  // ------------------------------------------------------------------ the slots: five models, reused
  // as the row moves (item k always in slot k mod 5), so only those near the middle exist
  const POOL = 5;
  const slots = [];
  for (let i = 0; i < POOL; i++) {
    const holder = new THREE.Group();     // where it stands, how large
    const tilt = new THREE.Group();       // its pitch toward us (the cartridge's), the lean of a swipe
    const spin = new THREE.Group();       // its turn
    holder.add(tilt); tilt.add(spin);
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(K.size * 1.25, K.size * (kind === 'film' ? 1.25 : 1.05)),
      new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, depthWrite: false }));
    shadow.rotation.x = -Math.PI / 2;
    holder.add(shadow);
    holder.visible = false;
    scene.add(holder);
    slots.push({ holder, tilt, spin, shadow, model: null, item: -1, hop: 0, hopV: 0 });
  }

  let items = [];
  function filmLook(o) {
    return { name: o.name, maker: (o.maker || '').toUpperCase(), title: (o.title || '').toUpperCase(), iso: o.iso || '',
      base: '#' + o.base, band: '#' + o.band, ink: '#' + o.ink };
  }
  function show(slot, k) {
    const look = kind === 'film' ? filmLook(items[k]) : items[k];
    if (!slot.model) {
      slot.model = kind === 'film' ? CartridgeModel(THREE, renderer, look) : PaperModel(THREE, renderer, look);
      if (kind === 'film') slot.model.setLeader(14);
      slot.spin.add(slot.model.group);
    } else slot.model.setLook(look);
    // each to the same width in the row
    const w = kind === 'film' ? K.size : (slot.model.width || 268);
    const k0 = kind === 'film' ? 1 : K.size / w;
    slot.model.group.scale.setScalar(k0);
    if (kind === 'paper') slot.model.group.position.set(0, 0, 0);
    slot.shadow.position.y = K.floor + 0.2;
    slot.item = k;
  }

  // ------------------------------------------------------------------ the camera
  let dist = 200, lookY = 0, itemPx = 100;
  const tmp = new THREE.Vector3();
  function frameCamera() {
    camera.aspect = vw() / vh();
    camera.updateProjectionMatrix();
    const t = Math.tan(THREE.MathUtils.degToRad(13));
    const A = camera.aspect;
    // the middle one fills most of the height; one neighbour each side shows in full
    const needH = K.high / 0.64;
    const needW = K.size * 1.15 + 2 * K.spacing * 0.95;
    dist = Math.max(needH / (2 * t), needW / (2 * t * A));
    lookY = kind === 'film' ? 1 : K.high * 0.35;
    if (footPx != null) bottom = 1 - 2 * footPx / vh();
    place();
    if (bottom != null) {
      // stand the middle one's foot at that line: nudge the camera up or down until it is
      for (let i = 0; i < 6; i++) {
        tmp.set(0, K.floor, K.front).project(camera);
        const shift = (tmp.y + bottom) * dist * t;
        lookY += shift;
        place();
      }
    }
    // the screen distance between the middle and its neighbour, for the finger
    tmp.set(K.spacing, 0, 0).project(camera);
    const o = new THREE.Vector3(0, 0, 0).project(camera);
    itemPx = Math.max(40, Math.abs(tmp.x - o.x) / 2 * vw());
    renderer.setSize(vw(), vh());
  }
  function place() {
    const e = K.elev;
    camera.position.set(0, lookY + Math.sin(e) * dist + (kind === 'film' ? 6 : 0), Math.cos(e) * dist);
    camera.lookAt(0, lookY, 0);
    camera.updateMatrixWorld();
  }

  // ------------------------------------------------------------------ motion
  let s = 0, v = 0, target = 0, committed = 0, lastCentre = -1;
  let dragging = false, x0 = 0, s0 = 0, lastX = 0, lastT = 0, vel = 0, downX = 0, downY = 0, downT = 0;
  let paused = false;
  const t0 = performance.now();
  function clamp(i) { return Math.max(0, Math.min(items.length - 1, i)); }

  window.carousel = {
    init: function (json, index) {
      try { items = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { items = []; }
      s = target = committed = clamp(index | 0);
      lastCentre = Math.round(s);
      slots.forEach((sl) => { sl.item = -1; });
    },
    go: function (index, animate) {
      const i = clamp(index | 0);
      committed = i; target = i;
      if (!animate) { s = i; v = 0; }
    },
    pause: function (on) { paused = !!on; if (!paused) requestAnimationFrame(frame); },
  };

  const el = renderer.domElement;
  el.addEventListener('touchstart', function (e) {
    const p = e.touches[0];
    dragging = true; x0 = lastX = downX = p.clientX; downY = p.clientY; s0 = s; lastT = downT = performance.now(); vel = 0; v = 0;
  }, { passive: true });
  el.addEventListener('touchmove', function (e) {
    if (!dragging) return;
    const p = e.touches[0], now = performance.now();
    let ns = s0 - (p.clientX - x0) / itemPx;
    // past either end it gives, but less and less
    if (ns < 0) ns = -Math.pow(-ns, 0.7) * 0.5;
    if (ns > items.length - 1) ns = items.length - 1 + Math.pow(ns - items.length + 1, 0.7) * 0.5;
    const dt = Math.max(8, now - lastT);
    vel = 0.7 * vel + 0.3 * (-(p.clientX - lastX) / itemPx / dt);   // items per ms
    s = ns; lastX = p.clientX; lastT = now;
  }, { passive: true });
  el.addEventListener('touchend', function (e) {
    if (!dragging) return;
    dragging = false;
    const p = e.changedTouches[0], now = performance.now();
    const moved = Math.hypot(p.clientX - downX, p.clientY - downY);
    if (moved < 10 && now - downT < 320) {
      const dx = p.clientX - vw() / 2;
      const c = clamp(Math.round(s));
      if (Math.abs(dx) < itemPx * 0.55) { hop(c, 1.4); if (bridge) bridge.open(c); }
      else target = clamp(c + Math.sign(dx) * (Math.abs(dx) > itemPx * 1.55 ? 2 : 1));
      return;
    }
    // the swipe carries on with its speed, then the nearest one is the one
    if (now - lastT > 90) vel = 0;
    target = clamp(Math.round(s + vel * 260));
  }, { passive: true });

  // the page took the gesture (an upright scroll) or the system did: settle where it was going
  el.addEventListener('touchcancel', function () {
    if (!dragging) return;
    dragging = false;
    target = clamp(Math.round(s));
  }, { passive: true });

  function hop(k, strength) { slots.forEach((sl) => { if (sl.item === k) sl.hopV = strength; }); }

  // ------------------------------------------------------------------ the loop
  function frame() {
    if (paused) return;
    const now = performance.now();
    const time = (now - t0) / 1000;
    if (!dragging) {
      // a spring to the chosen one: quick, a whisper of overshoot
      v += (target - s) * 0.085;
      v *= 0.74;
      s += v;
      if (Math.abs(target - s) < 0.0015 && Math.abs(v) < 0.0015) {
        s = target; v = 0;
        if (target !== committed) {
          committed = target;
          hop(target, 1.1);
          if (bridge) bridge.select(target);
        }
      }
    }
    const speed = dragging ? vel * 16 : v;            // items per frame
    const c = Math.round(s);
    if (c !== lastCentre && c >= 0 && c < items.length) { lastCentre = c; if (bridge) bridge.center(c); }

    // who is near the middle, in which slot
    for (let k = c - 2; k <= c + 2; k++) {
      if (k < 0 || k >= items.length) continue;
      const sl = slots[((k % POOL) + POOL) % POOL];
      if (sl.item !== k) show(sl, k);
    }
    slots.forEach((sl) => {
      const k = sl.item;
      if (k < 0 || Math.abs(k - c) > 2) { sl.holder.visible = false; return; }
      sl.holder.visible = true;
      const d = k - s, ad = Math.abs(d), sg = Math.sign(d);
      // the row: neighbours close in a little, the far ones closer still, all stepping back
      const x = sg * (K.spacing * Math.min(ad, 1) + K.spacing * 0.78 * Math.max(0, ad - 1));
      const z = -Math.min(ad, 2.2) * K.size * 0.55;
      const sc = 1 - 0.26 * Math.min(ad, 1) - 0.1 * Math.max(0, Math.min(ad - 1, 1.2));
      // the hop of a choice: a damped spring
      sl.hopV -= sl.hop * 0.12; sl.hopV *= 0.86; sl.hop += sl.hopV;
      sl.holder.position.set(x, sl.hop * (kind === 'film' ? 1 : 0.6), z);
      sl.holder.scale.setScalar(sc);
      const near = Math.max(0, 1 - ad);
      // turned toward the middle; the middle one sways; all lean and turn with the swipe
      const lean = Math.max(-0.5, Math.min(0.5, speed * 5));
      sl.spin.rotation.y = K.restYaw - d * K.turn + near * Math.sin(time * 0.8 + k) * K.sway + lean * 1.2;
      sl.tilt.rotation.set(K.pitch, 0, -lean * 0.25);
      sl.shadow.material.opacity = 0.9 * sc - Math.max(0, sl.hop) * 0.03;
    });
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }

  window.addEventListener('resize', frameCamera);
  if (!window.innerWidth || !window.innerHeight) {
    const poll = function () { if (window.innerWidth && window.innerHeight) frameCamera(); else requestAnimationFrame(poll); };
    requestAnimationFrame(poll);
  }
  renderer.domElement.addEventListener('webglcontextlost', function (e) { e.preventDefault(); });
  renderer.domElement.addEventListener('webglcontextrestored', function () { location.reload(); });
  frameCamera();
  requestAnimationFrame(frame);
  if (bridge) bridge.ready();
})();
