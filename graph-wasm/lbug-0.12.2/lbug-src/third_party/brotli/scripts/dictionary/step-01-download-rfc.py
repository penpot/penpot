# Step 01 - download RFC7932.
#
# RFC is the ultimate source for brotli format and constants, including
# static dictionary.

import urllib2

response = urllib2.urlopen("https://tools.ietf.org/rfc/rfc7932.txt")

text = response.read()
path = "rfc7932.txt"

with open(path, "w") as rfc:
  rfc.write(text)

print("Downloaded and saved " + str(len(text)) + " bytes to " + path)
