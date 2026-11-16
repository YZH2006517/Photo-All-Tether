// The paper's own packaging, modelled to measure (mm), as the cartridge is:
//   kind 'box'  an 8 x 10 in box of photographic paper: a telescoping lid (laminated, printed
//               all over) on a black base that shows a few millimetres under it
//   kind 'can'  a 35 mm print film's can (2383, 2393, 2302): pressed steel, ribbed lid, a printed
//               label on it, the seam sealed with printed tape
// PaperModel(THREE, renderer, look) returns { group, setLook, dispose, width, height }; the group
// lies flat with its base at y = 0, centred on x / z.
window.PaperModel = function (THREE, renderer, look) {
  'use strict';
  function rand(n) { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }
  function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }
  function lum(hex) { const c = parseInt(hex.slice(1), 16); return ((c >> 16 & 255) * 0.2126 + (c >> 8 & 255) * 0.7152 + (c & 255) * 0.0722) / 255; }
  function fit(g, text, max) { const w = g.measureText(text).width; if (w > max) g.font = g.font.replace(/\d+px/, (m) => Math.floor(parseInt(m) * max / w) + 'px'); }
  function rr(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
  const COND = '"Roboto Condensed", sans-serif-condensed, sans-serif';

  const group = new THREE.Group();
  const owned = [];   // textures and geometries to free
  function own(x) { owned.push(x); return x; }

  function canvasTexture(w, h, draw, srgb) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    draw(c.getContext('2d'), w, h);
    const t = new THREE.CanvasTexture(c);
    t.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    if (srgb) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  // ------------------------------------------------------------------ surfaces shared by both
  // laminated board: mostly even, fibre in it, rubbed along the edges, a few handling marks
  const boardRough = own(canvasTexture(512, 512, (g, w, h) => {
    g.fillStyle = '#7a7a7a'; g.fillRect(0, 0, w, h);
    for (let k = 0; k < 900; k++) { const v = 100 + rand(k) * 60; g.fillStyle = 'rgba(' + v + ',' + v + ',' + v + ',0.35)'; g.fillRect(rand(k + 1) * w, rand(k + 2) * h, 1 + rand(k + 3) * 5, 1); }
    g.strokeStyle = 'rgba(170,170,170,0.6)';
    for (let k = 0; k < 50; k++) { g.lineWidth = 0.5 + rand(k + 9) * 1.2; const x = rand(k + 4) * w, y = rand(k + 5) * h; g.beginPath(); g.moveTo(x, y); g.lineTo(x + (rand(k + 6) - 0.5) * 90, y + (rand(k + 7) - 0.5) * 20); g.stroke(); }
    for (let k = 0; k < 6; k++) {
      const x = rand(k + 40) * w, y = rand(k + 50) * h, gr = g.createRadialGradient(x, y, 0, x, y, 50);
      gr.addColorStop(0, 'rgba(160,160,160,0.5)'); gr.addColorStop(1, 'rgba(160,160,160,0)'); g.fillStyle = gr; g.fillRect(0, 0, w, h);
    }
  }, false));
  // pressed steel: fine rings round the centre, a few scratches
  const steelRough = own(canvasTexture(512, 512, (g, w, h) => {
    g.fillStyle = '#6a6a6a'; g.fillRect(0, 0, w, h);
    for (let r = 2; r < w * 0.71; r += 1.5) { const v = 90 + 45 * rand(r); g.strokeStyle = 'rgba(' + v + ',' + v + ',' + v + ',0.6)'; g.lineWidth = 1; g.beginPath(); g.arc(w / 2, h / 2, r, 0, Math.PI * 2); g.stroke(); }
    g.strokeStyle = 'rgba(190,190,190,0.7)';
    for (let k = 0; k < 30; k++) { g.lineWidth = 0.6; const x = rand(k + 70) * w, y = rand(k + 80) * h; g.beginPath(); g.moveTo(x, y); g.lineTo(x + 40 * (rand(k) - 0.5), y + 40 * (rand(k + 1) - 0.5)); g.stroke(); }
  }, false));

  // ================================================================== the box
  const BW = 268, BD = 216;           // the lid, 8 x 10 in paper with room round it
  const LID_H = 31, BASE_H = 35;      // the lid over the base; the base shows 4 mm under it
  const box = new THREE.Group();
  group.add(box);

  function printed(map, gloss) {
    return new THREE.MeshPhysicalMaterial({
      map: map, roughness: 0.48, roughnessMap: boardRough, metalness: 0,
      clearcoat: gloss, clearcoatRoughness: 0.28, envMapIntensity: 0.85,
      sheen: 0.15, sheenColor: new THREE.Color(0xffffff), sheenRoughness: 0.6,
    });
  }

  // the lid's top: the paper's face
  function topArt(l) { return canvasTexture(1536, 1238, (g, w, h) => {
    const base = l.base, band = l.band, accent = l.accent, ink = l.ink;
    g.fillStyle = base; g.fillRect(0, 0, w, h);
    // a fine printed grain, so it is not a flat colour
    for (let k = 0; k < 1800; k++) { g.fillStyle = 'rgba(0,0,0,' + (0.02 + rand(k) * 0.03) + ')'; g.fillRect(rand(k + 1) * w, rand(k + 2) * h, 2, 2); }
    // the maker's band across the top, a hairline under it
    const bh = h * 0.2;
    g.fillStyle = band; g.fillRect(0, 0, w, bh);
    g.fillStyle = l.bandInk; g.font = 'bold ' + Math.round(bh * 0.42) + 'px ' + COND;
    g.textAlign = 'left'; g.textBaseline = 'middle';
    fit(g, l.maker, w * 0.6); g.fillText(l.maker, w * 0.07, bh * 0.52);
    g.textAlign = 'right'; g.font = 'bold ' + Math.round(bh * 0.2) + 'px sans-serif';
    g.fillText(l.line, w * 0.94, bh * 0.52);
    g.fillStyle = l.rule; g.fillRect(0, bh, w, h * 0.012);
    // the accent: a stripe down the left, the paper's own colour
    g.fillStyle = accent; g.fillRect(0, bh + h * 0.012, w * 0.045, h);
    // the name, large; under it what it is, and its surface in a box
    g.fillStyle = ink; g.textAlign = 'left';
    g.font = 'bold ' + Math.round(h * 0.15) + 'px ' + COND;
    fit(g, l.product, w * 0.84); g.fillText(l.product, w * 0.09, h * 0.37);
    g.font = Math.round(h * 0.055) + 'px sans-serif';
    g.fillText(l.sub, w * 0.093, h * 0.5);
    g.font = 'bold ' + Math.round(h * 0.05) + 'px sans-serif';
    const sw = g.measureText(l.surface).width + w * 0.05;
    g.lineWidth = h * 0.006; g.strokeStyle = ink; rr(g, w * 0.09, h * 0.565, sw, h * 0.085, h * 0.015); g.stroke();
    g.fillText(l.surface, w * 0.09 + w * 0.025, h * 0.61);
    // size and count
    g.font = Math.round(h * 0.042) + 'px sans-serif';
    g.fillText('20.3 cm × 25.4 cm   8 × 10 in', w * 0.093, h * 0.73);
    g.fillText('100 SHEETS · FEUILLES · BLATT · HOJAS', w * 0.093, h * 0.785);
    // the warning, as on every box of paper
    g.fillStyle = '#c8102e'; rr(g, w * 0.09, h * 0.84, w * 0.5, h * 0.09, h * 0.012); g.fill();
    g.fillStyle = '#ffffff'; g.font = 'bold ' + Math.round(h * 0.04) + 'px sans-serif';
    fit(g, 'OPEN ONLY IN TOTAL DARKNESS', w * 0.46);
    g.fillText('OPEN ONLY IN TOTAL DARKNESS', w * 0.11, h * 0.886);
    // the bar code, bottom right
    const code = hash(l.product);
    g.fillStyle = '#ffffff'; g.fillRect(w * 0.7, h * 0.79, w * 0.24, h * 0.16);
    g.fillStyle = '#111111';
    let x = w * 0.715, i = 0;
    while (x < w * 0.925) { const bw = 3 + ((code >> (i % 13)) & 3) * 3; g.fillRect(x, h * 0.805, bw, h * 0.1); x += bw + 4; i++; }
    g.font = Math.round(h * 0.022) + 'px monospace'; g.textAlign = 'center';
    g.fillText(String(code % 1000000000000).padStart(12, '0'), w * 0.82, h * 0.935);
    // wear: the board shows through where the lid is handled, scuffs across it
    g.strokeStyle = 'rgba(255,255,255,0.28)'; g.lineWidth = 6; g.strokeRect(3, 3, w - 6, h - 6);
    g.strokeStyle = 'rgba(255,255,255,0.18)'; g.lineWidth = 1.4;
    for (let k = 0; k < 30; k++) { const sx = rand(k + 11) * w, sy = rand(k + 12) * h; g.beginPath(); g.moveTo(sx, sy); g.lineTo(sx + 30 + rand(k + 13) * 80, sy + (rand(k + 14) - 0.5) * 10); g.stroke(); }
  }, true); }

  // the lid's sides: the maker and the name round it, the emulsion stamped on one
  function sideArt(l, long, stamp) { return canvasTexture(long ? 1536 : 1238, 180, (g, w, h) => {
    g.fillStyle = l.base; g.fillRect(0, 0, w, h);
    g.fillStyle = l.band; g.fillRect(0, 0, w, h * 0.32);
    g.fillStyle = l.bandInk; g.font = 'bold ' + Math.round(h * 0.22) + 'px ' + COND;
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(l.maker, w / 2, h * 0.17);
    g.fillStyle = l.ink; g.font = 'bold ' + Math.round(h * 0.3) + 'px ' + COND;
    fit(g, l.product + '  ' + l.surface, w * 0.8);
    g.fillText(l.product + '  ' + l.surface, w / 2, h * 0.62);
    if (stamp) { // ink-jetted emulsion and date, slightly off the print, as they are
      const code = hash(l.product);
      g.fillStyle = 'rgba(20,20,30,0.85)'; g.font = Math.round(h * 0.12) + 'px monospace'; g.textAlign = 'left';
      g.fillText('EMULSION ' + (1000 + code % 9000) + '  CAT ' + (100 + code % 900) + ' ' + (1000 + (code >> 4) % 9000), w * 0.05, h * 0.88);
      g.textAlign = 'right'; g.fillText('EXP ' + (1 + code % 12).toString().padStart(2, '0') + '/202' + (7 + code % 3), w * 0.95, h * 0.88);
    }
    g.strokeStyle = 'rgba(255,255,255,0.3)'; g.lineWidth = 4; g.beginPath(); g.moveTo(0, 2); g.lineTo(w, 2); g.stroke();
  }, true); }

  const boxMats = { top: null, front: null, back: null, left: null, right: null };
  const lidGeo = own(new THREE.BoxGeometry(BW, LID_H, BD));
  const lid = new THREE.Mesh(lidGeo, []);
  lid.position.y = BASE_H - LID_H / 2;
  lid.castShadow = true; lid.receiveShadow = true;
  box.add(lid);
  // the base under it: black board, a hair smaller, showing as a band below the lid
  const baseMat = new THREE.MeshPhysicalMaterial({ color: 0x151414, roughness: 0.7, roughnessMap: boardRough, sheen: 0.4, sheenColor: new THREE.Color(0x444444) });
  const base = new THREE.Mesh(own(new THREE.BoxGeometry(BW - 2.4, BASE_H - 1, BD - 2.4)), baseMat);
  base.position.y = (BASE_H - 1) / 2;
  base.castShadow = true;
  box.add(base);
  // the lid's inside edge seen in the gap: a dark rim
  const rim = new THREE.Mesh(own(new THREE.BoxGeometry(BW - 0.6, 0.8, BD - 0.6)), new THREE.MeshStandardMaterial({ color: 0x0a0a0a, roughness: 0.9 }));
  rim.position.y = BASE_H - LID_H - 0.3;
  box.add(rim);

  function setBox(l) {
    const old = Object.values(boxMats).filter(Boolean);
    old.forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); });
    const gloss = l.gloss != null ? l.gloss : 0.35;
    boxMats.top = printed(topArt(l), gloss);
    boxMats.front = printed(sideArt(l, true, true), gloss);
    boxMats.back = printed(sideArt(l, true, false), gloss);
    boxMats.right = printed(sideArt(l, false, false), gloss);
    boxMats.left = printed(sideArt(l, false, false), gloss);
    const under = baseMat;
    // BoxGeometry's faces: +x, -x, +y, -y, +z, -z
    lid.material = [boxMats.right, boxMats.left, boxMats.top, under, boxMats.front, boxMats.back];
  }

  // ================================================================== the can
  const CR = 150, CH = 40;            // a 35 mm print film's can: 300 mm across, 40 mm deep
  const can = new THREE.Group();
  group.add(can);
  const steel = new THREE.MeshPhysicalMaterial({
    color: 0xc4c3bf, metalness: 0.9, roughness: 0.36, roughnessMap: steelRough, envMapIntensity: 1.5, side: THREE.DoubleSide,
  });
  // the body: a shallow drum, rolled at its foot
  const body = new THREE.Mesh(own(new THREE.LatheGeometry([
    [0, 0.6], [CR - 3, 0.6], [CR - 1, 0], [CR, 1.2], [CR, CH - 9],
  ].map(([r, y]) => new THREE.Vector2(r, y)), 200)), steel);
  body.castShadow = true; body.receiveShadow = true;
  can.add(body);
  // the lid: pressed rings for strength, the skirt over the body's top
  const T = CH;
  const lidPts = [[0, T + 1.6], [34, T + 1.6], [36, T + 3.2], [44, T + 3.2], [46, T + 1.6], [92, T + 1.6], [94, T + 0.4], [100, T + 0.4],
    [102, T + 1.6], [128, T + 1.6], [130, T + 2.6], [CR - 4, T + 2.6], [CR - 1, T + 2.0], [CR + 1.4, T + 0.4], [CR + 1.6, T - 10], [CR + 0.6, T - 10.6]];
  const canLid = new THREE.Mesh(own(new THREE.LatheGeometry(lidPts.map(([r, y]) => new THREE.Vector2(r, y)), 200)), steel);
  canLid.castShadow = true; canLid.receiveShadow = true;
  can.add(canLid);
  // the label on the lid's middle
  const labelGeo = own(new THREE.CircleGeometry(33.5, 96));
  const labelMesh = new THREE.Mesh(labelGeo, null);
  labelMesh.rotation.x = -Math.PI / 2; labelMesh.position.y = T + 3.35;
  can.add(labelMesh);
  // a bigger paper label in the outer ring, as the film's makers stick on
  const bigGeo = own(new THREE.RingGeometry(48, 90, 160, 1));
  const bigLabel = new THREE.Mesh(bigGeo, null);
  bigLabel.rotation.x = -Math.PI / 2; bigLabel.position.y = T + 1.75;
  can.add(bigLabel);
  // the tape round the seam
  const tapeGeo = own(new THREE.CylinderGeometry(CR + 1.9, CR + 1.9, 16, 220, 1, true));
  const tape = new THREE.Mesh(tapeGeo, null);
  tape.position.y = T - 6;
  can.add(tape);

  function canLabelArt(l) { return canvasTexture(1024, 1024, (g, w, h) => {
    g.fillStyle = l.labelBase; g.beginPath(); g.arc(w / 2, h / 2, w / 2, 0, Math.PI * 2); g.fill();
    g.fillStyle = l.labelInk; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.font = 'bold ' + Math.round(h * 0.15) + 'px ' + COND; g.fillText(l.maker, w / 2, h * 0.3);
    g.font = 'bold ' + Math.round(h * 0.3) + 'px ' + COND; g.fillText(l.code, w / 2, h * 0.56);
    g.font = Math.round(h * 0.06) + 'px sans-serif'; g.fillText('35 mm', w / 2, h * 0.78);
    g.strokeStyle = l.labelInk; g.lineWidth = 8; g.beginPath(); g.arc(w / 2, h / 2, w / 2 - 22, 0, Math.PI * 2); g.stroke();
  }, true); }

  // the outer label: a ring, printed in polar form (u round, v across) with the film's name
  function ringLabelArt(l) { return canvasTexture(1024, 1024, (g, w, h) => {
    g.fillStyle = l.labelBase; g.fillRect(0, 0, w, h);
    const cx = w / 2, cy = h / 2, R0 = w / 2;
    g.fillStyle = l.labelInk; g.textBaseline = 'middle';
    // text along the ring: each character placed on the circle
    function arcText(text, radius, size, start, bold) {
      g.font = (bold ? 'bold ' : '') + size + 'px ' + COND;
      let a = start;
      for (const ch of text) {
        const cw = g.measureText(ch).width;
        a += cw / 2 / radius;
        g.save(); g.translate(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius); g.rotate(a + Math.PI / 2);
        g.textAlign = 'center'; g.fillText(ch, 0, 0); g.restore();
        a += cw / 2 / radius;
      }
    }
    arcText(l.line, R0 * 0.83, Math.round(h * 0.05), -Math.PI * 0.95, true);
    arcText('35 mm × 305 m (1000 ft) · ' + l.code + ' · EMULSION ' + (100 + hash(l.code) % 900), R0 * 0.66, Math.round(h * 0.034), Math.PI * 0.12, false);
    // two hairlines bounding the print
    g.strokeStyle = l.labelInk; g.lineWidth = 4;
    [0.93, 0.58].forEach((k) => { g.beginPath(); g.arc(cx, cy, R0 * k, 0, Math.PI * 2); g.stroke(); });
  }, true); }

  function tapeArt(l) { return canvasTexture(2048, 128, (g, w, h) => {
    g.fillStyle = l.tape; g.fillRect(0, 0, w, h);
    g.fillStyle = l.tapeInk; g.font = 'bold ' + Math.round(h * 0.42) + 'px ' + COND;
    g.textAlign = 'center'; g.textBaseline = 'middle';
    for (let k = 0; k < 8; k++) g.fillText(l.maker + '  ' + l.code, (k + 0.5) * w / 8, h * 0.52);
    g.fillStyle = 'rgba(255,255,255,0.18)'; g.fillRect(0, h * 0.08, w, 3);
  }, true); }

  const canMats = [];
  function setCan(l) {
    canMats.forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); });
    canMats.length = 0;
    const paper = (map) => { const m = new THREE.MeshPhysicalMaterial({ map: map, roughness: 0.55, clearcoat: 0.2, clearcoatRoughness: 0.4 }); canMats.push(m); return m; };
    labelMesh.material = paper(canLabelArt(l));
    // the ring's uv: put its polar print on it (RingGeometry's uv is planar, which is what we drew)
    bigLabel.material = paper(ringLabelArt(l));
    const t = new THREE.MeshPhysicalMaterial({ map: tapeArt(l), roughness: 0.32, clearcoat: 0.6, clearcoatRoughness: 0.15 });
    t.map.wrapS = THREE.RepeatWrapping;
    canMats.push(t);
    tape.material = t;
  }

  // ================================================================== look and size
  let width = BW, height = BASE_H;
  function setLook(l) {
    look = l;
    const isCan = l.kind === 'can';
    box.visible = !isCan;
    can.visible = isCan;
    if (isCan) { setCan(l); width = CR * 2 + 4; height = CH + 3.4; }
    else { setBox(l); width = BW; height = BASE_H; }
    api.width = width; api.height = height;
  }

  const api = {
    group: group, width: width, height: height,
    setLook: setLook,
    dispose: function () {
      owned.forEach((x) => x.dispose());
      Object.values(boxMats).filter(Boolean).forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); });
      canMats.forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); });
      baseMat.dispose(); steel.dispose();
    },
  };
  setLook(look);
  return api;
};
