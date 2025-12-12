# Step 04 - generate Java literals.
#
# Java byte-code has severe restrictions. There is no such thing as
# "array literal" - those are implemented as series of data[x] = y;
# as a consequence N-byte array will use 7N bytes in class, plus N bytes
# in instantiated variable. Also no literal could be longer than 64KiB.
#
# To keep dictionary data compact both in source code and in compiled format
# we use the following tricks:
#  * use String as a data container
#  * store only lowest 7 bits; i.e. all characters fit ASCII table; this allows
#    efficient conversion to byte array; also ASCII characters use only 1 byte
#.   of memory (UTF-8 encoding)
#  * RLE-compress sequence of 8-th bits
#
# This script generates literals used in Java code.

try:
  unichr  # Python 2
except NameError:
  unichr = chr  # Python 3

bin_path = "dictionary.bin"

with open(bin_path, "rb") as raw:
  data = raw.read()

low = []
hi = []
is_skip = True
skip_flip_offset = 36
cntr = skip_flip_offset
for b in data:
  value = ord(b)
  low.append(chr(value & 0x7F))
  if is_skip:
    if value < 0x80:
      cntr += 1
    else:
      is_skip = False
      hi.append(unichr(cntr))
      cntr = skip_flip_offset + 1
  else:
    if value >= 0x80:
      cntr += 1
    else:
      is_skip = True
      hi.append(unichr(cntr))
      cntr = skip_flip_offset + 1
hi.append(unichr(cntr))

low0 = low[0:len(low) // 2]
low1 = low[len(low) // 2:len(low)]


def escape(chars):
  result = []
  for c in chars:
    if "\r" == c:
      result.append("\\r")
    elif "\n" == c:
      result.append("\\n")
    elif "\t" == c:
      result.append("\\t")
    elif "\"" == c:
      result.append("\\\"")
    elif "\\" == c:
      result.append("\\\\")
    elif ord(c) < 32 or ord(c) >= 127:
      result.append("\\u%04X" % ord(c))
    else:
      result.append(c)
  return result


source_code = [
    "  private static final String DATA0 = \"", "".join(escape(low0)), "\";\n",
    "  private static final String DATA1 = \"", "".join(escape(low1)), "\";\n",
    "  private static final String SKIP_FLIP = \"", "".join(escape(hi)), "\";\n"
]

src_path = "DictionaryData.inc.java"

with open(src_path, "w") as source:
  source.write("".join(source_code))
