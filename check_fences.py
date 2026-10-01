content = open('georeckon/server/PROTOCOL.md', encoding='utf-8').read()
lines = content.split('\n')
in_fence = False
opens = 0
closes = 0
for i, l in enumerate(lines):
    stripped = l.strip()
    if stripped.startswith('```'):
        lang = stripped[3:].strip()
        raw_preview = repr(l[:60])
        if not in_fence:
            in_fence = True
            opens += 1
            print(f'  OPEN  L{str(i+1).rjust(3)}: lang={repr(lang).ljust(12)} | raw={raw_preview}')
        else:
            in_fence = False
            closes += 1
            print(f'  CLOSE L{str(i+1).rjust(3)}: raw={raw_preview}')
print()
print(f'Opens={opens}, Closes={closes}, Balanced={opens==closes}, final in_fence={in_fence}')
