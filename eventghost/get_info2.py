from pySMART import device_list
from pySMART import device
import pyuac
#import pywin32
import subprocess

def get_smart_data(drive):
	try:
		command = ["smartctl", "-x", drive]
		result = subprocess.run(command, capture_output=True, text=True, check=True)
		
		if result.returncode == 0:
			#print(result, type(result))
			#
			# 
			# input("Press Enter to continue...")
			#return result.stdout
			return result
		
		else:
			return f"Error: smartctl failed with error code {result.returncode}: {result.stderr}"

	except FileNotFoundError:
		return "Error: smartctl executable not found. Ensure it is installed and in the system PATH."
	except Exception as e:
		return f"An unexpected error occurred: {str(e)}"
"""def main():
	drive_to_check = "/dev/sda"  # Replace with your drive
	print(drive_to_check)
	smart_data = get_smart_data(drive_to_check)
	print(smart_data, type(smart_data))

if __name__ == "__main__":
	if not pyuac.isUserAdmin():
		print("re-launching as admin")
		pyuac.runAsAdmin()
	else:
		main()
"""

if not pyuac.isUserAdmin():
	print("re-launching as admin")
	pyuac.runAsAdmin()
else:
	drive_to_check = "/dev/sda"  # Replace with your drive
	print(drive_to_check)
	smart_data = get_smart_data(drive_to_check)
	print(smart_data, type(smart_data))
	input("Press Enter to continue...")  # Keep the console open
	
