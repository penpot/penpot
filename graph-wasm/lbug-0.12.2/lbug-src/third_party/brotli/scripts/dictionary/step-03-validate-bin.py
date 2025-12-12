# Step 03 - validate raw dictionary file.
#
# CRC32, MD5, SHA1 and SHA256 checksums for raw binary dictionary are checked.

import hashlib
import zlib

bin_path = "dictionary.bin"

with open(bin_path, "rb") as raw:
  data = raw.read()


def check_digest(name, expected, actual):
  if expected == actual:
    print("[OK] " + name)
  else:
    print("[ERROR] " + name + " | " + expected + " != " + actual)


check_digest(
    "CRC32",  # This is the only checksum provided in RFC.
    "0x5136cb04",
    hex(zlib.crc32(data)))

check_digest("MD5", "96cecd2ee7a666d5aa3627d74735b32a",
             hashlib.md5(data).hexdigest())

check_digest("SHA1", "72b41051cb61a9281ba3c4414c289da50d9a7640",
             hashlib.sha1(data).hexdigest())

check_digest(
    "SHA256",
    "20e42eb1b511c21806d4d227d07e5dd06877d8ce7b3a817f378f313653f35c70",
    hashlib.sha256(data).hexdigest())
