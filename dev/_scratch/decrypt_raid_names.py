# -*- coding: utf-8 -*-
import importlib.util, os
spec = importlib.util.spec_from_file_location("d", r"C:\Users\MhHs\Documents\project\mekiCalc\dev\_clt_decrypt.py")
d = importlib.util.module_from_spec(spec); spec.loader.exec_module(d)

keys = [
 # 재화/아이템
 'ITEM_NAME_MAGIC_SCROLL','ITEM_NAME_STONE_WEAPON','ITEM_NAME_GEAR_GACHA_POINT',
 'ITEM_NAME_TICKET_TIMECUT','ITEM_NAME_ITEM_EXP','STAMINA_NAME_TICKET_BOSS_RAID',
 'ITEM_NAME_GOLD','CURRENCY_NAME_Gold','NAME_CURRENCY_GOLD',
 # 레이드 장비 주문서
 'NAME_SCROLL_RAID_ZAKUM_1','NAME_SCROLL_RAID_ZAKUM_2','NAME_SCROLL_RAID_HORNTAIL_1','NAME_SCROLL_RAID_HORNTAIL_2',
 # 컨텐츠명
 'UI_CONTENT_NAME_BOSSRAID_ZAKUM','DUNGEON_TYPE_NAME_BOSSRAID_ZAKUM',
 # 장비 부위/이름 후보
 'GEAR_NAME_CAP_SUMMARY','GEAR_NAME_EYEACCESSORY_SUMMARY','GEAR_NAME_EARACCESSORY_SUMMARY','GEAR_NAME_PENDANT_SUMMARY',
 'GEAR_NAME_CAP_DESC',
]
for k in keys:
    ct = d.kv.get(k)
    if ct is None:
        print(f'{k} = (키없음)')
    else:
        print(f'{k} = {d.decrypt(k, ct)!r}')
