;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.zip
  "Helpers for make zip file."
  (:require
   ["@zip.js/zip.js" :as zip]
   [app.util.array :as array]
   [promesa.core :as p]))

(defn reader
  [blob]
  (cond
    (instance? js/Blob blob)
    (let [breader (new zip/BlobReader blob)]
      (new zip/ZipReader breader))

    (instance? js/Uint8Array blob)
    (let [breader (new zip/Uint8ArrayReader blob)
          zreader (new zip/ZipReader breader #js {:useWebWorkers false})]
      zreader)

    (instance? js/ArrayBuffer blob)
    (reader (js/Uint8Array. blob))

    :else
    (throw (ex-info "invalid arguments"
                    {:type :internal
                     :code :invalid-type}))))

(defn blob-writer
  [& {:keys [mtype]}]
  (new zip/BlobWriter (or mtype "application/octet-stream")))

(defn bytes-writer
  []
  (new zip/Uint8ArrayWriter))

(defn writer
  [stream-writer]
  (new zip/ZipWriter stream-writer))

(defn add
  [writer path content]
  (assert (instance? zip/ZipWriter writer))
  (cond
    (instance? js/Uint8Array content)
    (.add writer path (new zip/Uint8ArrayReader content))

    (instance? js/ArrayBuffer content)
    (.add writer path  (new zip/Uint8ArrayReader
                            (new js/Uint8Array content)))

    (instance? js/Blob content)
    (.add writer path  (new zip/BlobReader content))


    (string? content)
    (.add writer path (new zip/TextReader content))

    :else
    (throw (ex-info "invalid arguments"
                    {:type :internal
                     :code :invalid-type}))))


(defn get-entry
  [reader path]
  (assert (instance? zip/ZipReader reader))
  (->> (.getEntries ^zip/ZipReader reader)
       (p/fmap (fn [entries]
                 (array/find #(= (.-filename ^js %) path) entries)))))

(defn get-entries
  [reader]
  (assert (instance? zip/ZipReader reader))
  (.getEntries reader))


(defn read-as-text
  [entry]
  (let [writer (new zip/TextWriter)]
    (.getData entry writer)))

(defn close
  [closeable]
  (assert (or (instance? zip/ZipReader closeable)
              (instance? zip/ZipWriter closeable)))
  (.close ^js closeable))
