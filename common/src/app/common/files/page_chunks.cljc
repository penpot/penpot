;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.page-chunks
  "The layout of a compact binfile page split into chunks.

  A compact page embeds every shape of the page in the entry of the page,
  so a large page grows past the per-entry limit of the importer. The
  writer splits it: the entry of the page keeps its metadata and gains
  `:chunks n`, and chunk `i` holds part of the page's objects at
  `(chunk-path file-id page-id i)` as a stream of `[id, value]` lines, one
  json value per line; the compressed layout writes the chunk at
  `(compressed-chunk-path file-id page-id i)`, whose `.json.zstd` suffix
  says the payload is a zstd frame. The reader of a package accepts both
  layouts, so a page written whole keeps opening."
  #?(:clj
     (:import
      (java.nio.charset StandardCharsets))))

#?(:cljs
   (def ^:private text-encoder
     ;; Resolved on first use: a runtime without `TextEncoder` must only break
     ;; the chunked export, not the namespace load.
     (delay (js/TextEncoder.))))

(def default-chunk-size
  "Uncompressed size of a chunk entry, in bytes, newlines included. A chunk is
  one zip entry, and the importer caps each text entry at 20 MiB
  (`default-max-text-entry-size` in the backend), so the budget stays at a
  quarter of it. A shape whose own JSON passes the cap cannot be fixed by
  chunking: it travels alone, in a chunk that is then over the budget and that
  the import still refuses."
  (* 5 1024 1024))

(def compressed-chunk-size
  "Uncompressed size of a chunk entry of the compressed variant, in bytes,
  newlines included. A chunk is one zip entry whose payload is compressed, so
  the importer cap bounds the decompressed json; 18 MiB leaves 2 MiB under the
  20 MiB cap (`default-max-text-entry-size` in the backend) while the
  compressed entry itself is about 2.5 MiB. A shape whose own JSON passes the
  cap cannot be fixed by chunking, as with `default-chunk-size`."
  (* 18 1024 1024))

(defn chunk-full?
  "True when a chunk that already holds `size` bytes of lines cannot take an
  entry of `entry-size` more bytes without passing `budget`. `entry-size` is
  the serialized line including its newline, so the budget bounds the chunk
  entry exactly. The caller only asks it for a chunk that already has a line:
  the first line of a chunk is always taken, because the writer cannot split a
  shape."
  [size entry-size budget]
  (> (+ size entry-size) budget))

(defn utf8-byte-size
  "The size in bytes of `s` encoded as UTF-8.

  A string with no code unit above U+007F — the usual content of a shape's
  json — weighs its length, so the common case allocates nothing. Any other
  string goes through the platform encoder, because a character can weigh up
  to four bytes and a surrogate pair counts as two code units."
  [s]
  #?(:clj
     (let [^String s s
           n         (.length s)]
       (loop [i 0]
         (if (< i n)
           (if (> (int (.charAt s i)) 0x7f)
             (alength (.getBytes s StandardCharsets/UTF_8))
             (recur (inc i)))
           n)))
     :cljs
     (let [^string s s
           n         (.-length s)]
       (loop [i 0]
         (if (< i n)
           (if (> (.charCodeAt s i) 0x7f)
             (.-length (.encode ^js @text-encoder s))
             (recur (inc i)))
           n)))))

(defn codec-chunk-path
  "The path of the chunk `index` of a page written with `codec`. The
  `.json.<codec>` suffix says the payload is a frame of that codec, not
  text."
  [file-id page-id index codec]
  (str "files/" file-id "/pages/" page-id "/chunks/" index ".json"
       (when codec (str "." (name codec)))))

(defn chunk-path
  "The path of the chunk `index` of a page of the plain layout."
  [file-id page-id index]
  (codec-chunk-path file-id page-id index nil))

(defn compressed-chunk-path
  "The path of the chunk `index` of a page of the compressed layout. The
  `.json.zstd` suffix keeps a human from opening the entry as json: the
  payload is a zstd frame, not text."
  [file-id page-id index]
  (codec-chunk-path file-id page-id index :zstd))

(defn- chunk-lines*
  "Walks `entries` accumulating serialized lines until the next one would pass
  `budget`, then answers that chunk and defers the rest. `pending` carries the
  line that did not fit the previous chunk, already serialized, with its size;
  it is the first line of this chunk, so a line is serialized once even across
  a boundary."
  [entries budget serialize pending]
  (lazy-seq
   (when (or (seq entries) (some? pending))
     (let [[line psize] pending]
       (loop [entries entries
              lines   (if pending [line] [])
              size    (if pending psize 0)]
         (if-let [[id value] (first entries)]
           (let [line (serialize id value)
                 n    (inc (utf8-byte-size line))]
             (if (and (seq lines) (chunk-full? size n budget))
               (cons lines (chunk-lines* (next entries) budget serialize [line n]))
               (recur (next entries) (conj lines line) (+ size n))))
           (list lines)))))))

(defn chunk-lines
  "Lazily splits `objects` into chunks of a bounded serialized size and answers
  a lazy sequence of chunks, each a vector of serialized lines without their
  newline.

  `serialize` answers the json line of one `[id value]` pair, newline excluded.
  A line counts its bytes plus one for the newline, so the caller writes it as
  `(str (str/join \"\\n\" lines) \"\\n\")`. Lines are taken in map order while
  the chunk stays under `budget`, and a chunk starts when the next line would
  pass it; a line that alone passes the budget forms a chunk of its own,
  because the writer cannot split a value. An empty map answers no chunks, and
  the entry that names them then carries `:chunks 0`.

  Only the chunk being consumed is realized: writing the first chunk serializes
  its lines and the first line of the next one, nothing more. Each line is
  serialized once, including the one that starts the next chunk."
  [objects budget serialize]
  (chunk-lines* (seq objects) budget serialize nil))
