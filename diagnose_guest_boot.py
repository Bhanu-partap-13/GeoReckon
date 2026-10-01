import subprocess, os

adb = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk\platform-tools\adb.exe'

print('=== 3. ADB COMMANDS (ONCE) ===')
res_dev = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True)
print('adb devices -l:\n', res_dev.stdout.strip())

res_state = subprocess.run([adb, '-s', 'emulator-5554', 'get-state'], capture_output=True, text=True)
print('adb -s emulator-5554 get-state:\n STDOUT:', res_state.stdout.strip(), '\n STDERR:', res_state.stderr.strip())

res_serial = subprocess.run([adb, '-s', 'emulator-5554', 'get-serialno'], capture_output=True, text=True)
print('adb -s emulator-5554 get-serialno:\n STDOUT:', res_serial.stdout.strip(), '\n STDERR:', res_serial.stderr.strip())

print('\n=== 4. NETSTAT PORTS 5554 / 5555 ===')
res_net = subprocess.run(['netstat', '-ano'], capture_output=True, text=True)
lines_ports = [l.strip() for l in res_net.stdout.splitlines() if ':5554' in l or ':5555' in l]
print('\n'.join(lines_ports) if lines_ports else '(none)')

print('\n=== 5. TASKLIST PROCESSES ===')
res_task = subprocess.run(['tasklist'], capture_output=True, text=True)
lines_procs = [l.strip() for l in res_task.stdout.splitlines() if any(p in l.lower() for p in ['emulator', 'qemu'])]
print('\n'.join(lines_procs) if lines_procs else '(none)')

print('\n=== 1 & 2. EMULATOR LOG SEARCH ===')
log_candidates = [
    r'C:\Users\Animesh Tripathi\.android\avd\GeoReckon_Test_API35.avd\emulator.log',
    r'C:\Users\Animesh Tripathi\.android\GeoReckon_Test_API35\emulator.log'
]
keywords = ['adbd', 'boot_completed', 'sys.boot_completed', 'adb', 'transport', 'offline', 'failed', 'error', 'fatal']

for lp in log_candidates:
    print(f'\nChecking: {lp} (exists={os.path.exists(lp)})')
    if os.path.exists(lp):
        with open(lp, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
        print(f'Total lines: {len(lines)}')
        matches = [(i+1, l.strip()) for i, l in enumerate(lines) if any(k in l.lower() for k in keywords)]
        print(f'Matching lines ({len(matches)}):')
        for lineno, line in matches[-20:]:
            print(f'  L{lineno}: {line}')
