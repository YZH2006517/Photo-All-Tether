// The film's cartridge on a turntable, for the app's pages: transparent, turning slowly and
// floating a little, turned with a finger. The app drives it through window.stage:
//   look(json, swap)  another stock's label (swap: the cartridge turns its back and comes round anew)
//   advance()         a frame exposed: the leader steps out and the spool clicks round
//   rewind()          the roll is finished: the film winds back in, the cartridge spins, a new roll
//   pause(on)         stop drawing while the page is out of sight
//   intro()           the immersive view's opening: in from far off, spinning, the film drawn out
//   snapshot(json)    a still of a stock's cartridge as a PNG data URL (stops the loop)
// and tells it through window.CartBridge: ready(), tap(), open() (a double tap).
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  let look = {
    name: q.get('name') || 'Kodak Portra 400',
    maker: (q.get('maker') || 'KODAK').toUpperCase(),
    title: (q.get('title') || 'PORTRA').toUpperCase(),
    iso: q.get('iso') || '400',
    base: '#' + (q.get('base') || 'e9dfc6'),
    band: '#' + (q.get('band') || '5d4a8f'),
    ink: '#' + (q.get('ink') || '1c1a1a'),
  };
  const bridge = window.CartBridge;
  const scale = parseFloat(q.get('scale') || '1');

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
  const camera = new THREE.PerspectiveCamera(26, vw() / vh(), 1, 4000);
  function fit() {
    const half = Math.tan(THREE.MathUtils.degToRad(13));
    const aspect = vw() / vh();
    return Math.max(31 / half, 44 / (half * aspect)) / scale;
  }
  let dist = fit();

  // a soft studio for the metal and the varnish to reflect (as the phone app's viewer)
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

  const cart = CartridgeModel(THREE, renderer, look);
  const model = cart.group;
  const holder = new THREE.Group();
  holder.add(model);
  scene.add(holder);
  const L = cart.L, CAP = cart.CAP;

  // a soft contact shadow under it, for weight
  const shadowTex = (function () {
    const c = document.createElement('canvas'); c.width = c.height = 128;
    const g = c.getContext('2d');
    const gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    gr.addColorStop(0, 'rgba(0,0,0,0.55)'); gr.addColorStop(0.5, 'rgba(0,0,0,0.22)'); gr.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
    return new THREE.CanvasTexture(c);
  })();
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(44, 44), new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2;
  shadow.position.y = -L / 2 - CAP - 3.2;
  scene.add(shadow);

  // ------------------------------------------------------------------ motion state
  let yaw = -0.5, vyaw = 0, pitch = 0.12;
  let leader = 18, leaderTarget = 18;
  let lift = 0, liftV = 0;          // the hop of a swap or a landing (spring)
  let spin = 0;                     // extra spin of a swap or a rewind (rad, eased out)
  let spinFrom = 0, spinTo = 0, spinT0 = 0, spinMs = 0, onMid = null, midDone = true;
  const IDLE = 0.0035;              // the turntable's own speed, rad per frame
  let dragging = false, lx = 0, lt = 0, downX = 0, downY = 0, downT = 0, lastTap = 0;
  let paused = false;
  const t0 = performance.now();
  cart.setLeader(leader);

  function ease(t) { return 1 - Math.pow(1 - t, 3); }
  function easeInOut(t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }

  function startSpin(turns, ms, mid) {
    spinFrom = spin; spinTo = spin + turns * Math.PI * 2; spinT0 = performance.now(); spinMs = ms;
    onMid = mid || null; midDone = !mid;
  }

  window.stage = {
    look: function (json, swap) {
      try {
        const o = typeof json === 'string' ? JSON.parse(json) : json;
        const next = {
          name: o.name, maker: (o.maker || '').toUpperCase(), title: (o.title || '').toUpperCase(), iso: o.iso || '',
          base: '#' + o.base, band: '#' + o.band, ink: '#' + o.ink,
        };
        if (!swap) { look = next; cart.setLook(look); return; }
        // turn its back to us, change the label out of sight, come round with a little hop
        startSpin(1, 1100, function () { look = next; cart.setLook(look); });
        liftV = 1.6;
      } catch (e) { /* a bad look keeps the old label */ }
    },
    advance: function () {
      leaderTarget = Math.min(34, leaderTarget + 2.6);
      liftV = 0.55;
      vyaw += 0.035;
      setTimeout(function () { leaderTarget = Math.max(18, leaderTarget - 1.4); }, 420);
    },
    rewind: function () {
      // the film goes back in, the cartridge spins with it, lands, and a new roll comes out
      leaderTarget = 0;
      startSpin(3, 1800, null);
      setTimeout(function () { liftV = 2.4; }, 1500);
      setTimeout(function () { leaderTarget = 18; }, 2200);
    },
    pause: function (on) { paused = !!on; if (!paused) requestAnimationFrame(frame); },
    // the opening of the immersive view: the cartridge comes from far off spinning, the
    // camera closes on it, and the film is drawn out of its lips
    intro: function () {
      zoomFrom = 3.4; zoomT0 = performance.now(); zoomMs = 1500;
      leader = 0; leaderTarget = 0; cart.setLeader(0);
      startSpin(2, 1700, null);
      setTimeout(function () { leaderTarget = 34; }, 950);
      setTimeout(function () { liftV = 2.0; }, 1500);
    },
    // a still of [json]'s cartridge, label to the front, as a PNG data URL (the film cards)
    snapshot: function (json) {
      try {
        const o = typeof json === 'string' ? JSON.parse(json) : json;
        look = {
          name: o.name, maker: (o.maker || '').toUpperCase(), title: (o.title || '').toUpperCase(), iso: o.iso || '',
          base: '#' + o.base, band: '#' + o.band, ink: '#' + o.ink,
        };
        cart.setLook(look);
      } catch (e) { return ''; }
      paused = true;
      // the first call can come before the page has its size: take it now
      if (vw() < 8 || vh() < 8) return '';
      if (renderer.domElement.width < 8 || Math.abs(camera.aspect - vw() / vh()) > 0.01) {
        camera.aspect = vw() / vh(); camera.updateProjectionMatrix();
        dist = fit(); renderer.setSize(vw(), vh());
      }
      cart.setLeader(16);
      holder.position.y = 0;
      model.rotation.set(pitch, -0.62, 0);
      shadow.visible = false;           // the card trims to the model; its floor is the card's
      camera.position.set(0, 7, dist);
      camera.lookAt(0, 1, 0);
      renderer.render(scene, camera);
      return renderer.domElement.toDataURL('image/png');
    },
  };
  let zoomFrom = 1, zoomT0 = 0, zoomMs = 0;

  // ------------------------------------------------------------------ touch: turn it; tap, double tap
  const el = renderer.domElement;
  el.addEventListener('touchstart', function (e) {
    const t = e.touches[0];
    dragging = true; lx = t.clientX; lt = performance.now(); downX = t.clientX; downY = t.clientY; downT = lt; vyaw = 0;
  }, { passive: true });
  el.addEventListener('touchmove', function (e) {
    if (!dragging) return;
    const x = e.touches[0].clientX, t = performance.now();
    const d = (x - lx) / el.clientWidth * Math.PI * 2;
    yaw += d;
    vyaw = d / Math.max(8, t - lt) * 16;
    lx = x; lt = t;
  }, { passive: true });
  el.addEventListener('touchend', function (e) {
    dragging = false;
    const t = e.changedTouches[0];
    const moved = Math.hypot(t.clientX - downX, t.clientY - downY);
    const now = performance.now();
    if (moved < 12 && now - downT < 300) {
      if (now - lastTap < 320) { lastTap = 0; if (bridge) bridge.open(); }
      else { lastTap = now; setTimeout(function () { if (lastTap && performance.now() - lastTap >= 320) { if (bridge) bridge.tap(); lastTap = 0; } }, 330); }
    }
  }, { passive: true });

  // ------------------------------------------------------------------ the loop
  function frame() {
    if (paused) return;
    const now = performance.now();
    const time = (now - t0) / 1000;
    if (!dragging) {
      vyaw *= 0.95;
      yaw += IDLE + vyaw;
    }
    // a swap's or a rewind's spin, eased; the label changes when its back is to us
    if (spinMs > 0) {
      const k = Math.min(1, (now - spinT0) / spinMs);
      spin = spinFrom + (spinTo - spinFrom) * (spinMs > 1500 ? easeInOut(k) : ease(k));
      if (!midDone && k > 0.35) { midDone = true; if (onMid) onMid(); }
      if (k >= 1) spinMs = 0;
    }
    // the hop: a damped spring back to rest
    liftV -= lift * 0.12; liftV *= 0.86; lift += liftV;
    if (Math.abs(leaderTarget - leader) > 0.05) { leader += (leaderTarget - leader) * 0.12; cart.setLeader(leader); }
    const float = Math.sin(time * 1.3) * 0.6;
    holder.position.y = float + lift;
    model.rotation.set(pitch, yaw + spin, 0);
    shadow.material.opacity = 1 - Math.min(0.5, (lift + float + 0.6) * 0.04);
    shadow.scale.setScalar(1 - Math.min(0.25, (lift + float) * 0.012));
    // the intro's dolly: from far off to the usual distance, slowing as it arrives
    let zoom = 1;
    if (zoomMs > 0) {
      const k = Math.min(1, (now - zoomT0) / zoomMs);
      zoom = 1 + (zoomFrom - 1) * (1 - ease(k));
      if (k >= 1) zoomMs = 0;
    }
    camera.position.set(0, 7 * zoom, dist * zoom);
    camera.lookAt(0, 1, 0);
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }

  window.addEventListener('resize', function () {
    camera.aspect = vw() / vh(); camera.updateProjectionMatrix();
    dist = fit(); renderer.setSize(vw(), vh());
  });
  if (!window.innerWidth || !window.innerHeight) {
    const poll = function () { if (window.innerWidth && window.innerHeight) window.dispatchEvent(new Event('resize')); else requestAnimationFrame(poll); };
    requestAnimationFrame(poll);
  }
  renderer.domElement.addEventListener('webglcontextlost', function (e) { e.preventDefault(); });
  renderer.domElement.addEventListener('webglcontextrestored', function () { location.reload(); });
  // arrive with a hop and a quick turn, to show it is a solid
  liftV = 2.0; vyaw = 0.09;
  requestAnimationFrame(frame);
  if (bridge) bridge.ready();
})();
