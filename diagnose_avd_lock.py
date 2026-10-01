import subprocess, os, time, datetime

adb = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk\platform-tools\adb.exe'
emulator = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk\emulator\emulator.exe'
avd_dir = r'C:\Users\Animesh Tripathi\.android\avd\GeoReckon_Test_API35.avd'

env = os.environ.copy()
env['ANDROID_HOME'] = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk'
env['ANDROID_SDK_ROOT'] = r'C:\Users\Animesh Tripathi\AppData\Local\Android\Sdk'

print('=== 1. CHECK PROCESSES & PORTS ===')
res_task = subprocess.run(['tasklist'], capture_output=True, text=True)
emu_procs = [l.strip() for l in res_task.stdout.splitlines() if 'emulator' in l.lower() or 'qemu' in l.lower()]
print('Emulator/Qemu procs:', emu_procs if emu_procs else '(none)')

res_net = subprocess.run(['netstat', '-ano'], capture_output=True, text=True)
ports = [l.strip() for l in res_net.stdout.splitlines() if ':5554' in l or ':5555' in l]
print('Ports 5554/5555:', ports if ports else '(none)')

print('\n=== 2. INSPECT AVD DIRECTORY FOR LOCKS ===')
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

print('\n=== 3. STALE LOCK RESOLUTION ===')
if not emu_procs and locks_found:
    for lock_path, _, is_dir in locks_found:
        try:
            if is_dir:
                os.rmdir(lock_path)
            else:
                os.remove(lock_path)
            print(f'Removed stale lock: {lock_path}')
        except Exception as e:
            print(f'Error removing lock {lock_path}: {e}')

print('\n=== 4. START AVD ONCE ===')
DETACHED_PROCESS = 0x00000008
CREATE_NEW_PROCESS_GROUP = 0x00000200
cmd_emu = [emulator, '-avd', 'GeoReckon_Test_API35', '-no-snapshot', '-no-window', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect']
proc = subprocess.Popen(cmd_emu, creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP, close_fds=True, env=env)
print(f'Launched emulator PID: {proc.pid}')

time.sleep(20)

print('\n=== 6. CHECK ADB DEVICES ONCE ===')
res_dev = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True)
print('adb devices -l:\n', res_dev.stdout.strip())

res_task_after = subprocess.run(['tasklist'], capture_output=True, text=True)
emu_procs_after = [l.strip() for l in res_task_after.stdout.splitlines() if 'emulator' in l.lower() or 'qemu' in l.lower()]
print('tasklist after 20s:', emu_procs_after if emu_procs_after else '(none)')

log_path = r'C:\Users\Animesh Tripathi\.android\GeoReckon_Test_API35\emulator.log'
if os.path.exists(log_path):
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = f.readlines()
    print('\nLast 10 lines of emulator.log:')
    for l in lines[-10:]:
        print('  ', l.strip())
