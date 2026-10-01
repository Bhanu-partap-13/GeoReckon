import subprocess, os, time, datetime

adb = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk\platform-tools\adb.exe'
emulator = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk\emulator\emulator.exe'
avd_dir = r'C:\Users\Animesh Tripathi\.android\avd\GeoReckon_Test_API35.avd'

env = os.environ.copy()
env['ANDROID_HOME'] = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk'
env['ANDROID_SDK_ROOT'] = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk'

print('=== 1. VERIFY PROCESS & PORT STATE ===')
res_task = subprocess.run(['tasklist'], capture_output=True, text=True)
emu_procs = [l.strip() for l in res_task.stdout.splitlines() if any(p in l.lower() for p in ['emulator', 'qemu'])]
print('Running emulator/qemu procs:', emu_procs if emu_procs else '(none)')

res_net = subprocess.run(['netstat', '-ano'], capture_output=True, text=True)
ports = [l.strip() for l in res_net.stdout.splitlines() if ':5554' in l or ':5555' in l]
print('Ports 5554/5555:', ports if ports else '(none)')

for p_line in ports:
    tokens = p_line.split()
    if len(tokens) >= 5:
        pid = tokens[-1]
        res_pid = subprocess.run(['tasklist', '/FI', f'PID eq {pid}'], capture_output=True, text=True)
        print(f'Process owning PID {pid}:\n', res_pid.stdout.strip())

print('\n=== 2. INSPECT AVD LOCKS ===')
locks_found = []
if os.path.exists(avd_dir):
    for root, dirs, files in os.walk(avd_dir):
        for f in files + dirs:
            if 'lock' in f.lower():
                full = os.path.join(root, f)
                mtime = os.path.getmtime(full)
                ts = datetime.datetime.fromtimestamp(mtime).isoformat()
                locks_found.append((full, ts, os.path.isdir(full)))
                print(f'Found lock: {full} | Modified: {ts} | IsDir: {os.path.isdir(full)}')
if not locks_found:
    print('No lock files found in AVD directory.')

if not emu_procs and locks_found:
    for lock_path, _, is_dir in locks_found:
        try:
            if is_dir:
                os.rmdir(lock_path)
            else:
                os.remove(lock_path)
            print(f'Cleaned stale lock: {lock_path}')
        except Exception as e:
            print(f'Error cleaning lock {lock_path}: {e}')

print('\n=== 3 & 4. START AVD IN NORMAL GUI MODE & CAPTURE OUTPUT ===')
cmd = [emulator, '-avd', 'GeoReckon_Test_API35', '-gpu', 'swiftshader_indirect', '-verbose']
print('Executing:', cmd)

res_emu = subprocess.run(cmd, capture_output=True, text=True, timeout=25, env=env)
print(f'EMULATOR_EXIT_CODE={res_emu.returncode}')
print('STDOUT:\n', res_emu.stdout)
print('STDERR:\n', res_emu.stderr)

print('\n=== 5. CHECK ADB DEVICES ONCE ===')
res_dev = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True)
print('adb devices -l:\n', res_dev.stdout.strip())
