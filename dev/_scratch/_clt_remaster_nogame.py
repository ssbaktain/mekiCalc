# 게임 OFF 없이 재빌드: 새 analyze 출력 + 기존 full.json의 name/desc 재활용.
# name/desc는 클뜯 원본 로컬라이징(불변) → dealTrigger 등 구조 변경만 반영 시 게임 에뮬 불필요.
# 사용: python _clt_remaster_nogame.py [CI]  (인자 없으면 전 14직업). analyze를 직접 호출해 ci{N}.json 갱신 후 name/desc 채움.
# -*- coding: utf-8 -*-
import io, json, os, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(os.path.dirname(HERE))   # 프로젝트 루트

def remaster(ci):
    # 1) 새 analyze --json → skill_master/ci{N}.json
    r = subprocess.run([sys.executable, 'dev/_clt_skill_analyze.py', str(ci), '--json'],
                       capture_output=True, encoding='utf-8')
    if r.returncode != 0 or not r.stdout.strip():
        raise RuntimeError(f"analyze 실패: {r.stderr[:300]}")
    new = json.loads(r.stdout)
    # 2) 기존 full.json의 name/desc를 index/msi로 매칭
    old = json.load(io.open(f'skill_master/ci{ci}_full.json', encoding='utf-8'))
    nm = {s['index']: (s.get('name'), s.get('desc')) for s in old['skills']}
    mm = {}
    for s in old['skills']:
        for m in s.get('masteries', []):
            key = str(m.get('msi') or m.get('open'))
            mm[key] = (m.get('name'), m.get('desc'))
    # 3) 새 구조에 name/desc 주입
    for s in new['skills']:
        s['name'], s['desc'] = nm.get(s['index'], (None, None))
        for m in s.get('masteries', []):
            key = str(m.get('msi') or m.get('open'))
            m['name'], m['desc'] = mm.get(key, (None, None))
    io.open(f'skill_master/ci{ci}.json', 'w', encoding='utf-8').write(json.dumps(new, ensure_ascii=False, indent=1))
    io.open(f'skill_master/ci{ci}_full.json', 'w', encoding='utf-8').write(json.dumps(new, ensure_ascii=False, indent=1))
    return len(new['skills'])

cis = [sys.argv[1]] if len(sys.argv) > 1 else [str(i) for i in range(1, 15)]
for ci in cis:
    try:
        n = remaster(ci)
        print(f"CI{ci} remaster 완료: {n}스킬", flush=True)
    except Exception as e:
        print(f"CI{ci} 실패: {e}", flush=True)
