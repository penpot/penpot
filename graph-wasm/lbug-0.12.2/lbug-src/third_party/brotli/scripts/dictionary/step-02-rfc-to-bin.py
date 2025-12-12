# Step 02 - parse RFC.
#
# Static dictionary is described in "Appendix A" section in a hexadecimal form.
# This tool locates dictionary data in RFC and converts it to raw binary format.

import re

rfc_path = "rfc7932.txt"

with open(rfc_path, "r") as rfc:
  lines = rfc.readlines()

re_data_line = re.compile("^      [0-9a-f]{64}$")

appendix_a_found = False
dictionary = []
for line in lines:
  if appendix_a_found:
    if re_data_line.match(line) is not None:
      data = line.strip()
      for i in range(32):
        dictionary.append(int(data[2 * i:2 * i + 2], 16))
      if len(dictionary) == 122784:
        break
  else:
    if line.startswith("Appendix A."):
      appendix_a_found = True

bin_path = "dictionary.bin"

with open(bin_path, "wb") as output:
  output.write(bytearray(dictionary))

print("Parsed and saved " + str(len(dictionary)) + " bytes to " + bin_path)
