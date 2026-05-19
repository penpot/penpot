;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.nio
  "NIO helpers for working with files and byte arrays.

   These are thin wrappers around java.nio that provide a
   Clojure-idiomatic API. Candidates for porting to datoteka."
  (:import
   java.nio.ByteBuffer
   java.nio.channels.FileChannel
   java.nio.file.Files
   java.nio.file.OpenOption
   java.nio.file.Path
   java.nio.file.StandardOpenOption))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------
;; File operations (via java.nio.file.Files)
;; ----------------------------------------------------------------

(defn read-bytes
  "Read all bytes from a file at `path`. Returns a byte array."
  ^bytes [^Path path]
  (Files/readAllBytes path))

(defn write-bytes
  "Write `data` (byte array) to a file at `path`, replacing existing
   content. Returns `path`."
  [^Path path ^bytes data]
  (Files/write path data ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption []))
  path)

(defn append-bytes
  "Append `data` (byte array) to the end of the file at `path`.
   Creates the file if it does not exist. Returns `path`."
  [^Path path ^bytes data]
  (Files/write path data
               ^"[Ljava.nio.file.OpenOption;"
               (into-array OpenOption
                           [StandardOpenOption/CREATE
                            StandardOpenOption/APPEND]))
  path)

;; ----------------------------------------------------------------
;; FileChannel operations (internal API)
;; ----------------------------------------------------------------

(def ^:private read-write-opts
  (into-array OpenOption
              [StandardOpenOption/READ StandardOpenOption/WRITE]))

(defn open-channel
  "Open a FileChannel for read/write on the given path."
  ^FileChannel [^Path path]
  (FileChannel/open path read-write-opts))

(defn channel-size
  "Return the size of the file backed by the channel."
  ^long [^FileChannel channel]
  (.size channel))

(defn read-at
  "Read `length` bytes from `channel` starting at `position` into a
   new byte array. Returns the byte array.
   Loops until the ByteBuffer is fully populated to guard against OS
   partial reads, which would otherwise cause BufferUnderflowException
   when copying from the buffer into the result array."
  ^bytes [^FileChannel channel ^long position ^long length]
  (let [buf (ByteBuffer/allocate (int length))]
    (.position channel position)
    (loop []
      (when (.hasRemaining buf)
        (let [n (.read channel buf)]
          (when (pos? n)
            (recur)))))
    (.flip buf)
    (let [remaining (.remaining buf)
          arr       (byte-array remaining)]
      (.get buf arr)
      arr)))

(defn truncate
  "Truncate the file to the given size. Returns the channel."
  [^FileChannel channel ^long size]
  (.truncate channel size)
  channel)
