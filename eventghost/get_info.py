import psutil
import platform
import subprocess
import re


def get_disk_info():
    disks = []
    partitions = psutil.disk_partitions()
    for part in partitions:
        try:
            usage = psutil.disk_usage(part.mountpoint)
            disks.append({
                "device": part.device,
                "mountpoint": part.mountpoint,
                "fstype": part.fstype,
                "total_gb": round(usage.total / (1024 ** 3), 2),
                "free_gb": round(usage.free / (1024 ** 3), 2),
            })
        except PermissionError:
            continue
    return disks


def get_smart_temperatures():
    temps = {}
    if platform.system() == "Windows":
        # Try common Windows physical drive names
        for i in range(10):
            device = f'//./PhysicalDrive{i}'
            try:
                result = subprocess.run(
                    ['smartctl', '-A', device],
                    capture_output=True, text=True
                )
                if result.returncode != 0:
                    continue
                # Look for the temperature attribute (ID 194 or 190)
                match = re.search(r'^(194|190)\s+.+?\s+(\d+)\s*$',
                                  result.stdout, re.MULTILINE)
                if match:
                    temps[device] = int(match.group(2))
            except Exception as e:
                temps[device] = f"Error: {e}"
    else:
        # Linux example for /dev/sda
        try:
            result = subprocess.run(
                ["sudo", "hddtemp", "-n", "/dev/sda"],
                capture_output=True, text=True
            )
            temps["/dev/sda"] = float(result.stdout.strip())
        except Exception as e:
            temps["/dev/sda"] = f"Error: {e}"
    return temps


if __name__ == "__main__":
    print("Disk Info and Temperatures:")
    disk_info = get_disk_info()
    temps = get_smart_temperatures()
    for disk in disk_info:
        device = disk["device"]
        temp = None
        # Try to match device to PhysicalDrive if on Windows
        if platform.system() == "Windows":
            for phys_dev in temps:
                if device.lower().replace("\\", "").replace(":", "") in phys_dev.lower().replace("\\", ""):
                    temp = temps[phys_dev]
                    break
        else:
            temp = temps.get(device, None)
        print(f"Device: {device}")
        print(f"  Mountpoint: {disk['mountpoint']}")
        print(f"  Filesystem: {disk['fstype']}")
        print(f"  Total: {disk['total_gb']} GB")
        print(f"  Free: {disk['free_gb']} GB")
        print(f"  Temperature: {temp if temp is not None else 'N/A'}°C")
        print("-" * 40)
