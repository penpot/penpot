;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.scripts.decode-file-data
  "Decode a file's `file_data` binary blob to `jq`-readable JSON.

  The content of `file_data.data` (and `file.data` on the `legacy-db`
  backend, or the `file-data` object on storage) is not text: it is a blob
  encoded with `app.util.blob` (versions 1, 3, 4 and 5; transit+json or
  fressian plus lz4/zstd). That is why it cannot be inspected with SQL
  directly.

  Two uses:

  1. Offline (no DB, just the blob): dump the `bytea` to a file with
     `psql` (`\\copy ...` or `encode(data, 'base64')`) and pass it with `--input`.
     Needs no system and no `file_data` read permissions.

     backend/scripts/decode-file-data --input /tmp/blob.bin --format summary | jq .
     backend/scripts/decode-file-data --input /tmp/blob.b64 --from-base64 --format pages | jq .

  2. Inside the penpot context (nREPL with the backend running, where
     `sys/system` already exists): call `get-file-json` or `get-snapshot-json`.
     They resolve the backend (`legacy-db`, `db`, `storage`), realize the
     pointer-maps and objects-maps and return JSON.

     (require '[app.scripts.decode-file-data :as dfd] :reload)
     (spit \"/tmp/maquette.json\"
       (dfd/get-file-json sys/system #uuid \"9e056cfe-f83f-81f8-8008-cc885732f00d\"
                      :format :full))
     (spit \"/tmp/snap-518.json\"
       (dfd/get-snapshot-json sys/system #uuid \"9e056cfe-f83f-81f8-8008-cc885732f00d\"
                          #uuid \"<snapshot-id>\" :format :summary))

  Then filter with `jq`: `.pages`, `.data[\"pages-index\"] | keys`,
  `[.data[\"pages-index\"][] | {id, name}]`, etc."
  (:require
   [app.binfile.common :as bfc]
   [app.common.json :as json]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap-legacy]
   [app.util.pointer-map :as pmap]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   java.util.Base64))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON sanitization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sanitize-key
  [k]
  (cond
    (keyword? k) (subs (str k) 1)
    (symbol? k)  (str k)
    (uuid? k)    (str k)
    :else        (str k)))

(declare sanitize)

(defn- sanitize-map
  [m]
  (persistent!
   (reduce-kv (fn [acc k v]
                (assoc! acc (sanitize-key k) (sanitize v)))
              (transient {})
              m)))

(defn sanitize
  "Convert a blob-decoded value to plain JSON-ready data.

  - Unrealized pointers become `{\"$pointer\": \"<uuid>\"}`.
  - `objects-map` instances are materialized to a plain map.
  - Bytes become `{\"$bytes\": \"<base64>\"}`.
  - UUIDs, instants, keywords and symbols become strings."
  [x]
  (cond
    (nil? x)     nil
    (string? x)  x
    (boolean? x) x

    (number? x)
    (if (ratio? x) (double x) x)

    (keyword? x) (subs (str x) 1)
    (symbol? x)  (str x)
    (uuid? x)    (str x)
    (inst? x)    (str x)

    (bytes? x)
    {"$bytes" (.encodeToString (Base64/getEncoder) ^bytes x)}

    (pmap/pointer-map? x)
    {"$pointer" (str (pmap/get-id x))}

    (omap-legacy/objects-map? x)
    (sanitize (into {} x))

    (map? x)
    (sanitize-map x)

    (set? x)
    (mapv sanitize x)

    (coll? x)
    (mapv sanitize x)

    :else
    (str x)))

(defn data->json
  "Encode sanitizable data to a JSON string."
  [data]
  (json/encode (sanitize data)))

(defn blob->json
  "Decode a `file_data` blob (`app.util.blob`) and return it as JSON."
  [^bytes blob-bytes]
  (data->json (blob/decode blob-bytes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; jq-oriented investigation summary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn summarize-data
  "Small, stable summary for `jq`: pages (in `:pages` order) with object
  counts, plus component, color and typography counters. Takes the already
  decoded `:data` (not the JSON)."
  [data]
  (let [pages       (:pages data)
        pages-index (:pages-index data)
        page-entry  (fn [pid]
                      {:id      (str pid)
                       :name    (get-in pages-index [pid :name])
                       :objects (count (get-in pages-index [pid :objects]))})]
    {:pages (mapv page-entry (or pages []))
     :stats {:pages        (count (or pages []))
             :components   (count (:components data))
             :colors       (count (:colors data))
             :typographies (count (:typographies data))}}))

(defn file-data-summary-json
  [data]
  (data->json (summarize-data data)))

(defn file-data-pages-json
  [data]
  (data->json (:pages (summarize-data data))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB access (for use from nREPL with the backend running)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- write-output
  [^String payload output]
  (if output
    (do (spit output payload) output)
    (print payload))
  payload)

(defn get-file-json
  "Return a file as JSON (realized). For nREPL.

  `format` can be `:full` (whole file), `:data` (`:data` only),
  `:summary` or `:pages`. With `:output` it also writes it to a file."
  [system file-id & {:keys [format output] :or {format :full}}]
  (let [file (db/run! system bfc/get-file file-id :realize? true)
        data (case format
               :full    file
               :data    (:data file)
               :summary (summarize-data (:data file))
               :pages   (:pages (summarize-data (:data file))))]
    (write-output (data->json data) output)))

(defn get-snapshot-json
  "Return a snapshot as JSON (realized). For nREPL.

  Takes the same `format` and `output` as `get-file-json`."
  [system file-id snapshot-id & {:keys [format output] :or {format :full}}]
  (let [snapshot (db/run! system
                          (fn [cfg]
                            (fsnap/get-snapshot cfg file-id snapshot-id)))
        data   (case format
                 :full    snapshot
                 :data    (:data snapshot)
                 :summary (summarize-data (:data snapshot))
                 :pages   (:pages (summarize-data (:data snapshot))))]
    (write-output (data->json data) output)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Offline CLI: decode a file holding the blob
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private cli-usage
  (str "Usage: decode-file-data --input PATH [--output PATH] [--format full|data|summary|pages]\n"
       "                       [--from-base64|--from-hex]\n"
       "\n"
       "Decode a file_data blob (app.util.blob) to JSON for inspection with jq.\n"
       "\n"
       "Examples:\n"
       "  psql -c \"\\\\copy (select data from file_data where file_id='...' and id='...') to '/tmp/blob.bin' with binary\"\n"
       "  decode-file-data --input /tmp/blob.bin --format summary | jq .\n"
       "  decode-file-data --input /tmp/blob.b64 --from-base64 --format pages | jq .\n"))

(defn- parse-args
  [args]
  (loop [args args
         opts {:format "full"}]
    (if (empty? args)
      opts
      (let [[a b & rest-args] args]
        (cond
          (contains? #{"-h" "--help"} a)
          (assoc opts :help true)

          (= a "--input")
          (recur rest-args (assoc opts :input b))

          (= a "--output")
          (recur rest-args (assoc opts :output b))

          (= a "--format")
          (recur rest-args (assoc opts :format b))

          (= a "--from-base64")
          (recur (cons b rest-args) (assoc opts :from :base64))

          (= a "--from-hex")
          (recur (cons b rest-args) (assoc opts :from :hex))

          :else
          (assoc opts :error (str "unknown argument: " a)))))))

(defn- hex->bytes
  [^String s]
  (let [s   (str/trim s)
        s   (cond
              (str/starts-with? s "\\x") (subs s 2)
              (str/starts-with? s "0x") (subs s 2)
              :else s)
        s   (str/replace s #"\s+" "")
        n   (/ (count s) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset-byte out i (unchecked-byte (Integer/parseInt (subs s (* i 2) (+ (* i 2) 2)) 16))))
    out))

(defn- read-input-bytes
  [{:keys [input from]}]
  (cond
    (nil? input)
    (throw (ex-info "missing --input" {}))

    (= from :base64)
    (.decode (Base64/getDecoder) ^String (str/trim (slurp (io/file input))))

    (= from :hex)
    (hex->bytes (slurp (io/file input)))

    :else
    (java.nio.file.Files/readAllBytes (.toPath (io/file input)))))

(defn -main
  [& args]
  (let [{:keys [help error input format output] :as opts} (parse-args (vec args))]
    (cond
      help
      (do (print cli-usage) nil)

      error
      (do (binding [*out* *err*] (println error) (println cli-usage)) (System/exit 2))

      (nil? input)
      (do (binding [*out* *err*] (println "missing --input") (println cli-usage)) (System/exit 2))

      (not (contains? #{"full" "data" "summary" "pages"} (or format "full")))
      (do (binding [*out* *err*] (println (str "unknown format: " format))) (System/exit 2))

      :else
      (let [blob-bytes (read-input-bytes opts)
            decoded    (blob/decode blob-bytes)
            payload    (case format
                         "full"    decoded
                         "data"    (:data decoded (:data decoded decoded))
                         "summary" (summarize-data (:data decoded decoded))
                         "pages"   (:pages (summarize-data (:data decoded decoded))))
            ;; NOTE: the file_data blob already IS the file's :data; but if
            ;; someone dumps the whole serialized row, tolerate both.
            out        (data->json payload)]
        (if output
          (spit output out)
          (println out))))))
