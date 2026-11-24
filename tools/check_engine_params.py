"""Checks that the app's recipe (front end) and the film engine (back end) agree on every parameter.

    python tools/check_engine_params.py

Reads engine/src/film_params.{h,cpp} (the names set_param / set_stage take, their defaults and
clamps) and the app's FilmParamsCatalog.kt / Recipe.kt (the names the recipe sends, its sliders'
ranges and defaults), and reports:
  - a name the app sends that the engine does not know (the JNI would throw on it),
  - an engine parameter the app offers nowhere (unless listed in NOT_OFFERED with a reason),
  - a default that differs from the engine's (unless the catalog says engineDefault = ...),
  - a slider range reaching outside what the engine clamps to.
Exit code 1 on any problem.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def read(path):
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")  # the engine's copies are CRLF


H = read(ROOT / "engine/src/film_params.h")
CPP = read(ROOT / "engine/src/film_params.cpp")
KT = ROOT / "android/app/src/main/java/org/photoall/tether/engine"
CATALOG = read(KT / "FilmParamsCatalog.kt")
RECIPE = read(KT / "Recipe.kt")

# Engine parameters the app deliberately does not offer, and why.
NOT_OFFERED = {
    "stage baseline": "DNG BaselineExposure: the app decodes camera RAWs with LibRaw, which leaves it unset",
    "stage display": "set by the app itself: a camera JPEG with the film off stays as the camera rendered it",
}
# Set by Recipe.params() from the recipe's own fields (main controls), not the catalog.
MAIN = {
    "film", "paper", "grain", "size", "grain amt", "halation", "hal amt", "couplers", "stage diffusion",
    "stage diff amt", "stage film", "stage print", "filter c", "filter m", "filter y",
}
# Main controls' defaults and slider ranges, as Recipe / RecipePage set them (app default, min, max).
MAIN_DEFAULTS = {
    "size": (0.5, 0.1, 2.5), "grain amt": (0.5, 0.0, 3.0), "hal amt": (1.0, 0.0, 4.0), "stage diff amt": (0.5, 0.0, 1.0),
}

problems = []


def num(s):
    return float(s.rstrip("f"))


# ---- the engine --------------------------------------------------------------------------------
struct = re.search(r"struct (?:alignas\(\d+\) )?FilmParams\s*\{(.*?)\n\};", H, re.S).group(1)
member_default = {m: num(v) for m, v in re.findall(r"(?:float|int32_t)\s+(\w+)\s*=\s*(-?[\d.]+f?)\s*;", struct)}
fields = dict(re.findall(r'(?:FLOAT|INT)\("([^"]+)",\s*(\w+)\)', CPP))
engine = {}  # name -> (default, lo, hi)
for name, member in fields.items():
    engine[name] = (member_default.get(member), None, None)
for c in "rgb":
    engine[f"strength {c}"] = (1.0, None, None)
engine["frame mm"] = (0.0, 10.0, 250.0)  # 0 = filmsim's own (35 mm); clamped 10..250 otherwise

stages = re.search(r"struct Stages\s*\{(.*?)\n\};", H, re.S).group(1)
stage_member = {}
for typ, m, v in re.findall(r"(bool|float|int)\s+(\w+)\s*=\s*([\w.\-]+f?)\s*;", stages):
    stage_member[m] = 1.0 if v == "true" else 0.0 if v == "false" else num(v)
for name, member, rest in re.findall(r'name == "(stage [^"]+)"\)\s*stages\.(\w+)\s*=\s*([^;]+);', CPP):
    clamp = re.search(r"std::clamp\([^,]+,\s*(-?[\d.]+)f?,\s*(-?[\d.]+)f?\)", rest)
    lo, hi = (float(clamp.group(1)), float(clamp.group(2))) if clamp else (None, None)
    if "!= 0.0f" in rest:
        lo, hi = 0.0, 1.0
    engine[name] = (stage_member.get(member), lo, hi)

# ---- the app -----------------------------------------------------------------------------------
app = {}  # name -> (default, min, max, engineDefault)
for m in re.finditer(r'Param\("([^"]+)",\s*"[^"]*",\s*([-\d.]+)f,\s*([-\d.]+)f,\s*([-\d.]+)f([^\n]*)', CATALOG):
    key, lo, hi, default, rest = m.group(1), float(m.group(2)), float(m.group(3)), float(m.group(4)), m.group(5)
    ed = re.search(r"engineDefault\s*=\s*([-\d.]+)f", rest)
    if key in app:
        problems.append(f"catalog: {key!r} listed twice")
    app[key] = (default, lo, hi, float(ed.group(1)) if ed else None)
sent_main = set(re.findall(r'"([a-z][a-z ]+[a-z])" to ', RECIPE.split("fun params(")[1].split("fun changed(")[0]))
sent_main |= set(re.findall(r'p\["([^"]+)"\]', RECIPE))

# ---- checks ------------------------------------------------------------------------------------
for key in sorted(set(app) | sent_main):
    if key not in engine:
        problems.append(f"app sends {key!r}: the engine does not know it (JNI would throw)")
for key in sorted(engine):
    if key not in app and key not in sent_main and key not in NOT_OFFERED:
        problems.append(f"engine parameter {key!r} is offered nowhere in the app")
for key, (default, lo, hi, engine_default) in sorted(app.items()):
    if key not in engine:
        continue
    e_default, e_lo, e_hi = engine[key]
    want = engine_default if engine_default is not None else default
    # the enlarger's manual sliders: Recipe.params() always sends filter c (-1 / -2 in the fitted
    # modes, the slider in manual), so the engine's own default is never used
    if key in ("filter c", "filter m", "filter y"):
        continue
    if e_default is not None and abs(want - e_default) > 1e-4:
        problems.append(f"{key!r}: app default {default} but engine default {e_default}"
                        + ("" if engine_default is None else f" (catalog says engineDefault {engine_default})"))
    if engine_default is not None and e_default is not None and abs(engine_default - e_default) > 1e-4:
        problems.append(f"{key!r}: engineDefault {engine_default} is not the engine's {e_default}")
    if key == "frame mm":
        continue  # a choice of sizes; 0 = default
    if e_lo is not None and (lo < e_lo - 1e-6 or hi > e_hi + 1e-6):
        problems.append(f"{key!r}: slider {lo}..{hi} reaches outside the engine's clamp {e_lo}..{e_hi}")
for key, (default, lo, hi) in MAIN_DEFAULTS.items():
    e_default, e_lo, e_hi = engine[key]
    if e_lo is not None and (lo < e_lo or hi > e_hi):
        problems.append(f"main control {key!r}: slider {lo}..{hi} outside the engine's clamp {e_lo}..{e_hi}")

offered = (set(app) | sent_main) & set(engine)
print(f"engine: {len(engine)} parameters ({len(fields) + 4} film, {len(engine) - len(fields) - 4} stages)")
print(f"app: {len(app)} in the catalog, {len(sent_main & set(engine))} set by the recipe's main controls")
print(f"offered: {len(offered)} / {len(engine)}; not offered on purpose: {', '.join(sorted(NOT_OFFERED))}")
app_defaults = sorted(k for k, v in app.items() if v[3] is not None)
if app_defaults:
    print("app defaults that differ from the engine's (always sent): " + ", ".join(
        f"{k} {app[k][0]} (engine {app[k][3]})" for k in app_defaults))
for k, (d, lo, hi) in MAIN_DEFAULTS.items():
    if engine[k][0] is not None and abs(engine[k][0] - d) > 1e-4:
        print(f"main control {k!r}: app default {d}, engine {engine[k][0]} (always sent by Recipe.params)")
if problems:
    print("\nPROBLEMS:")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("OK: every parameter the app sends is known to the engine, ranges and defaults agree")
