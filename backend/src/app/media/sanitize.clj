;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.media.sanitize
  "Image EOF truncation helpers — strips trailing data after image EOF
   markers to prevent exfiltration of non-image bytes appended to
   valid image files."
  (:require
   [app.common.buffer :as buf]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.util.nio :as nio])
  (:import
   java.nio.ByteOrder
   java.nio.channels.FileChannel))

(set! *warn-on-reflection* true)

(defn- scan-backwards
  "Scan byte array `arr` backwards (from the end) for the byte pattern
   `marker`. Returns the index in `arr` where the marker starts, or -1
   if not found."
  [^bytes arr ^bytes marker]
  (let [arr-len    (alength arr)
        marker-len (alength marker)]
    (loop [i (- arr-len marker-len)]
      (if (< i 0)
        -1
        (if (loop [j 0]
              (if (>= j marker-len)
                true
                (if (= (aget arr (+ i j)) (aget marker j))
                  (recur (inc j))
                  false)))
          i
          (recur (dec i)))))))

(defn- find-last-png-iend
  "Find the byte offset of the end of the PNG IEND chunk (12 bytes:
   4-byte length + 4-byte 'IEND' + 4-byte CRC32). Returns the offset
   AFTER the CRC32, or nil if not found."
  [^FileChannel channel]
  (let [size (nio/channel-size channel)]
    (when (> size 8)
      (let [buf-size (min (int size) (* 1024 1024))
            marker   (byte-array [0x49 0x45 0x4E 0x44])] ;; "IEND"
        (loop [pos (max 0 (- size buf-size))]
          (when (< pos size)
            (let [arr (nio/read-at channel pos buf-size)
                  idx (scan-backwards arr marker)]
              (if (neg? idx)
                ;; Not found in this chunk, try earlier
                (let [next-pos (max 0 (- pos (- buf-size 4)))]
                  (when (< next-pos pos)
                    (recur next-pos)))
                ;; Found "IEND" at idx. Chunk starts 4 bytes before.
                (let [chunk-start (- (+ pos idx) 4)]
                  (when (>= chunk-start 0)
                    ;; PNG chunk length is big-endian (network byte order).
                    ;; buf/wrap defaults to little-endian, so set it to big-endian.
                    (let [len-arr (nio/read-at channel chunk-start 4)
                          len-buf (buf/set-order (buf/wrap len-arr) ByteOrder/BIG_ENDIAN)
                          chunk-len (buf/read-int len-buf 0)]
                      (when (zero? chunk-len)
                        (+ chunk-start 12)))))))))))))

(defn- find-last-jpeg-eoi
  "Find the byte offset of the last JPEG EOI marker (0xFF 0xD9).
   Returns the offset AFTER the marker, or nil if not found."
  [^FileChannel channel]
  (let [size (nio/channel-size channel)]
    (when (> size 2)
      (let [buf-size (min (int size) (* 1024 1024))
            marker   (byte-array [(unchecked-byte 0xFF) (unchecked-byte 0xD9)])]
        (loop [pos (max 0 (- size buf-size))]
          (when (< pos size)
            (let [arr (nio/read-at channel pos buf-size)
                  idx (scan-backwards arr marker)]
              (if (neg? idx)
                (let [next-pos (max 0 (- pos (- buf-size 2)))]
                  (when (< next-pos pos)
                    (recur next-pos)))
                (+ pos idx 2)))))))))

(defn- find-last-gif-trailer
  "Find the byte offset immediately after the last GIF trailer byte (0x3B).
   Scans backwards through the file so that appended data after the real
   trailer is truncated even when it ends with 0x3B.
   Returns the offset AFTER the trailer byte, or nil if 0x3B is not found."
  [^FileChannel channel]
  (let [size (nio/channel-size channel)]
    (when (pos? size)
      (let [buf-size (min (int size) (* 1024 1024))
            marker   (byte-array [(unchecked-byte 0x3B)])]
        (loop [pos (max 0 (- size buf-size))]
          (when (< pos size)
            (let [arr (nio/read-at channel pos buf-size)
                  idx (scan-backwards arr marker)]
              (if (neg? idx)
                (let [next-pos (max 0 (- pos (- buf-size 1)))]
                  (when (< next-pos pos)
                    (recur next-pos)))
                (+ pos idx 1)))))))))

(defn- find-webp-end
  "Parse the WebP RIFF header to find the declared file size.
   WebP format: 'RIFF' (4 bytes) + uint32 total-size (4 bytes, little-endian)
   + 'WEBP' (4 bytes). The total size is the offset of the end of the file.
   Returns nil if the RIFF or WEBP magic bytes are missing."
  [^FileChannel channel]
  (let [size (nio/channel-size channel)]
    (when (>= size 12)
      (let [^bytes arr (nio/read-at channel 0 12)
            buf  (buf/wrap arr)]
        ;; Check RIFF magic (bytes 0-3) AND WEBP FourCC (bytes 8-11)
        (when (and (= (aget arr 0) (byte 0x52))   ;; 'R'
                   (= (aget arr 1) (byte 0x49))   ;; 'I'
                   (= (aget arr 2) (byte 0x46))   ;; 'F'
                   (= (aget arr 3) (byte 0x46))   ;; 'F'
                   (= (aget arr 8)  (byte 0x57))  ;; 'W'
                   (= (aget arr 9)  (byte 0x45))  ;; 'E'
                   (= (aget arr 10) (byte 0x42))  ;; 'B'
                   (= (aget arr 11) (byte 0x50))) ;; 'P'
          (let [riff-size (bit-and (buf/read-int buf 4) 0xFFFFFFFF)]
            ;; RIFF size field is the size of the file minus 8 bytes
            (+ riff-size 8)))))))

(defn truncate-after-eof
  "Given a `java.nio.file.Path` to a freshly-downloaded media file and a
   declared MIME type, truncate the file in place to the position of the
   format's EOF marker:
     - image/png  → end of the IEND chunk (12 bytes: 4-byte length + 4-byte type + 4-byte CRC32)
     - image/jpeg → 2 bytes after FFD9
     - image/gif  → immediately after the last GIF trailer byte 0x3B
     - image/webp → end of RIFF chunk declared in bytes 4..8
     - image/svg+xml → no-op (text format; processed by SAX parser)
     - other → no-op (return path unchanged)
   Returns the new file size. Raises `:validation/:invalid-image` if no
   EOF marker is found within the file."
  [^java.nio.file.Path path ^String mtype]
  (try
    (with-open [channel (nio/open-channel path)]
      (let [size (nio/channel-size channel)]
        (if (zero? size)
          0
          (let [needs-eof-marker? (or (= mtype "image/png")
                                      (= mtype "image/jpeg")
                                      (= mtype "image/gif")
                                      (= mtype "image/webp"))

                eof-offset
                (cond
                  (= mtype "image/png")  (find-last-png-iend channel)
                  (= mtype "image/jpeg") (find-last-jpeg-eoi channel)
                  (= mtype "image/gif")  (find-last-gif-trailer channel)
                  (= mtype "image/webp") (find-webp-end channel)
                  :else                  nil)]

            (cond
              ;; No EOF marker applicable (SVG or other) — no-op
              (nil? eof-offset)
              (if needs-eof-marker?
                (ex/raise :type :validation
                          :code :invalid-image
                          :hint "image format EOF marker not found")
                size)

              ;; Truncate if needed
              (< eof-offset size)
              (do
                (l/dbg :hint "truncating trailing data"
                       :path (str path)
                       :mtype mtype
                       :original-size size
                       :truncated-to eof-offset)
                (nio/truncate channel eof-offset)
                eof-offset)

              ;; Already at correct size or marker at end
              :else
              eof-offset)))))
    (catch Exception e
      (if (ex/exception? e)
        (throw e)
        (ex/raise :type :validation
                  :code :invalid-image
                  :hint "failed to sanitize image"
                  :cause e)))))
