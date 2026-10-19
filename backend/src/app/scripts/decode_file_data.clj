;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.scripts.decode-file-data
  "Investigación de `file_data`: decodifica el blob binario de un archivo
  a JSON legible por `jq`.

  El contenido de `file_data.data` (y `file.data` en el backend `legacy-db`,
  o el objeto `file-data` en storage) no es texto: es un blob codificado con
  `app.util.blob` (versiones 1, 3, 4 y 5; transit+json o fressian más
  lz4/zstd). Por eso no se puede inspeccionar con SQL directamente.

  Dos usos:

  1. Offline (sin DB, solo el blob): vuelca el `bytea` a un fichero con
     `psql` (`\\copy ...` o `encode(data, 'base64')`) y pásalo con `--input`.
     No necesita sistema ni permisos de lectura de `file_data`.

     backend/scripts/decode-file-data --input /tmp/blob.bin --format summary | jq .
     backend/scripts/decode-file-data --input /tmp/blob.b64 --from-base64 --format pages | jq .

  2. Dentro del contexto de penpot (nREPL con el backend arrancado, donde
     `sys/system` ya existe): llama a `get-file-json` o `get-snapshot-json`.
     Resuelven el backend (`legacy-db`, `db`, `storage`), realizan los
     pointer-maps y objects-maps y devuelven JSON.

     (require '[app.scripts.decode-file-data :as dfd] :reload)
     (spit \"/tmp/maquette.json\"
       (dfd/get-file-json sys/system #uuid \"9e056cfe-f83f-81f8-8008-cc885732f00d\"
                      :format :full))
     (spit \"/tmp/snap-518.json\"
       (dfd/get-snapshot-json sys/system #uuid \"9e056cfe-f83f-81f8-8008-cc885732f00d\"
                          #uuid \"<snapshot-id>\" :format :summary))

  Después filtra con `jq`: `.pages`, `.data[\\\"pages-index\\\"] | keys`,
  `[.data[\\\"pages-index\\\"][] | {id, name}]`, etc."
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
;; Sanitización a JSON
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
  "Convierte un valor decodificado del blob a datos planos aptos para JSON.

  - Los punteros sin realizar se representan como `{\"$pointer\": \"<uuid>\"}`.
  - Los `objects-map` se materializan a mapa plano.
  - Los bytes se representan como `{\"$bytes\": \"<base64>\"}`.
  - UUID, instantes, keywords y símbolos pasan a cadena."
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
  "Codifica datos ya sanitizables a cadena JSON."
  [data]
  (json/encode (sanitize data)))

(defn blob->json
  "Decodifica un blob `file_data` (`app.util.blob`) y lo devuelve como JSON."
  [^bytes blob-bytes]
  (data->json (blob/decode blob-bytes)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resumen orientado a investigación con jq
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn summarize-data
  "Resumen pequeño y estable para `jq`: páginas (en orden de `:pages`)
  con recuento de objetos, más contadores de componentes, colores y
  tipografías. Recibe el `:data` ya decodificado (no el JSON)."
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
;; Acceso con DB (para usar desde nREPL con el backend arrancado)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- write-output
  [^String payload output]
  (if output
    (do (spit output payload) output)
    (print payload))
  payload)

(defn get-file-json
  "Devuelve un archivo como JSON (realizado). Para nREPL.

  `format` puede ser `:full` (todo el file), `:data` (solo `:data`),
  `:summary` o `:pages`. Con `:output` además lo escribe a fichero."
  [system file-id & {:keys [format output] :or {format :full}}]
  (let [file (db/run! system bfc/get-file file-id :realize? true)
        data (case format
               :full    file
               :data    (:data file)
               :summary (summarize-data (:data file))
               :pages   (:pages (summarize-data (:data file))))]
    (write-output (data->json data) output)))

(defn get-snapshot-json
  "Devuelve un snapshot como JSON (realizado). Para nREPL.

  Acepta los mismos `format` y `output` que `get-file-json`."
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
;; CLI offline: decodifica un fichero con el blob
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private cli-usage
  (str "Uso: decode-file-data --input PATH [--output PATH] [--format full|data|summary|pages]\n"
       "                       [--from-base64|--from-hex]\n"
       "\n"
       "Decodifica un blob de file_data (app.util.blob) a JSON para inspeccionar con jq.\n"
       "\n"
       "Ejemplos:\n"
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
          (assoc opts :error (str "argumento desconocido: " a)))))))

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
    (throw (ex-info "falta --input" {}))

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
      (do (binding [*out* *err*] (println "falta --input") (println cli-usage)) (System/exit 2))

      (not (contains? #{"full" "data" "summary" "pages"} (or format "full")))
      (do (binding [*out* *err*] (println (str "formato desconocido: " format))) (System/exit 2))

      :else
      (let [blob-bytes (read-input-bytes opts)
            decoded    (blob/decode blob-bytes)
            payload    (case format
                         "full"    decoded
                         "data"    (:data decoded (:data decoded decoded))
                         "summary" (summarize-data (:data decoded decoded))
                         "pages"   (:pages (summarize-data (:data decoded decoded))))
            ;; NOTE: el blob de file_data ya ES el :data del archivo; pero si
            ;; alguien vuelca la fila completa serializada, toleramos ambos.
            out        (data->json payload)]
        (if output
          (spit output out)
          (println out))))))
