;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns tokenscript-bundle
  "Builds `app.common.tokenscript.bundle` from the schema sources in
  `resources/tokenscript/schemas`:

    clojure -M:dev -m tokenscript-bundle           ; writes the bundle
    clojure -M:dev -m tokenscript-bundle --check   ; fails when it is stale

  Every schema is a directory with a `schema.json` and the tokenscript
  files it references (`\"script\": \"./initializer.tokenscript\"`):

    types/<name>/       color spaces     -> /api/v1/core/<name>/0/
    functions/<name>/   functions        -> /api/v1/function/<name>/0/
    constants/<name>/   inline constants -> /api/v1/constants/<name>/0/

  Script references are replaced by the script text. URIs stay path
  URIs: schemas are identified by them, never fetched. The build fails
  when a script does not parse, a conversion or requirement names a
  schema that is not bundled, or two functions share a keyword."
  (:require
   [app.common.json :as json]
   [app.common.tokenscript.parser :as parser]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def schemas-dir "resources/tokenscript/schemas")
(def bundle-file "src/app/common/tokenscript/bundle.cljc")

(def ^:private categories
  [["types" "core"]
   ["functions" "function"]
   ["constants" "constants"]])

(defn- fail
  [message data]
  (throw (ex-info message data)))

;; Reading --------------------------------------------------------------------

(defn- read-script
  "Text of the script file `path`, relative to the schema directory."
  [dir path]
  (let [file (io/file dir path)]
    (when-not (.isFile file)
      (fail (str "Missing script " file) {:dir (str dir) :path path}))
    (str/trim (slurp file))))

(defn- inline-script
  "Replaces a script file reference (`./x.tokenscript`) in `script-map`
  by the script text, checking that it parses."
  [dir {:strs [script] :as script-map}]
  (let [text (if (str/starts-with? script ".") (read-script dir script) script)]
    (try
      (parser/parse text)
      (catch Throwable e
        (fail (str "Script " script " in " dir " does not parse: " (ex-message e)) {:dir (str dir)})))
    (assoc script-map "script" text)))

(defn- inline-scripts
  [dir schema]
  (case (get schema "type")
    "color" (-> schema
                (update "initializers" #(mapv (fn [i] (update i "script" (partial inline-script dir))) %))
                (update "conversions" #(mapv (fn [c] (update c "script" (partial inline-script dir))) %)))
    "function" (update schema "script" (partial inline-script dir))
    schema))

(defn- read-schema
  [category uri-category dir]
  (let [name (.getName ^java.io.File dir)
        schema (json/decode (slurp (io/file dir "schema.json")) :key-fn identity)]
    {:uri (str "/api/v1/" uri-category "/" name "/0/")
     :category category
     :schema (inline-scripts dir schema)}))

(defn read-schemas
  "Bundled schemas, `{:uri :schema}` maps sorted by category and name."
  ([] (read-schemas schemas-dir))
  ([root]
   (vec
    (for [[category uri-category] categories
          :let [category-dir (io/file root category)]
          :when (.isDirectory category-dir)
          dir (sort-by #(.getName ^java.io.File %) (.listFiles category-dir))
          :when (.isFile (io/file dir "schema.json"))]
      (read-schema category uri-category dir)))))

;; Checks ---------------------------------------------------------------------

(defn- referenced-uris
  [{:keys [schema]}]
  (concat
   (->> (get schema "conversions")
        (mapcat (fn [c] [(get c "source") (get c "target")]))
        (remove #{"$self"}))
   (get schema "requirements")))

(defn check-schemas!
  "Throws when a schema references a schema that is not bundled or two
  functions share a keyword."
  [schemas]
  (let [uris (set (map :uri schemas))]
    (doseq [s schemas
            uri (referenced-uris s)]
      (when-not (contains? uris uri)
        (fail (str (:uri s) " references " uri ", which is not bundled") {:uri (:uri s) :reference uri})))
    (doseq [[kw dups] (->> schemas
                           (filter #(= "function" (get-in % [:schema "type"])))
                           (group-by #(str/lower-case (get-in % [:schema "keyword"]))))
            :when (> (count dups) 1)]
      (fail (str "Function keyword " kw " is defined by " (str/join ", " (map :uri dups))) {:keyword kw}))
    schemas))

;; Rendering ------------------------------------------------------------------
;;
;; One entry per line, nested collections aligned one column after their
;; opening bracket, the layout cljfmt expects for data.

(def ^:private key-order
  ["uri" "schema" "name" "title" "type" "description" "keyword" "inline" "input"
   "properties" "required" "order" "additionalProperties" "initializers"
   "conversions" "source" "target" "lossless" "script" "requirements" "values"])

(def ^:private key-rank
  (into {} (map-indexed (fn [i k] [k i])) key-order))

(defn- sort-entries
  [m]
  (sort-by (fn [[k _]] [(get key-rank (name k) (count key-order)) (name k)]) m))

(defn- render-key
  [k]
  (let [k (name k)]
    (if (re-matches #"[A-Za-z_][\w$-]*" k)
      (str ":" k)
      (pr-str k))))

(defn- spaces [n] (apply str (repeat n " ")))

(defn- render
  [v col]
  (cond
    (map? v)
    (if (empty? v)
      "{}"
      (let [inner (inc col)]
        (str "{"
             (str/join (str "\n" (spaces inner))
                       (for [[k value] (sort-entries v)
                             :let [ks (render-key k)]]
                         (str ks " " (render value (+ inner (count ks) 1)))))
             "}")))

    (sequential? v)
    (if (empty? v)
      "[]"
      (let [inner (inc col)]
        (str "[" (str/join (str "\n" (spaces inner)) (map #(render % inner) v)) "]")))

    :else (pr-str v)))

(def ^:private header
  ";; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.bundle
  \"Tokenscript schemas shipped with Penpot: the color spaces,
  functions and constants in common/resources/tokenscript/schemas.

  GENERATED FILE by `clojure -M:dev -m tokenscript-bundle`
  (common/dev/tokenscript_bundle.clj); do not edit by hand.\")

(def schemas
  ")

(defn render-bundle
  "Source of the bundle namespace for `schemas`."
  [schemas]
  (str header
       (render (mapv #(select-keys % [:uri :schema]) schemas) 2)
       ")\n"))

(defn bundle-source
  "Source of the bundle namespace built from the schema sources."
  []
  (-> (read-schemas) (check-schemas!) (render-bundle)))

(defn -main
  [& args]
  (let [source (bundle-source)]
    (if (some #{"--check"} args)
      (if (= source (slurp bundle-file))
        (println "tokenscript bundle is up to date")
        (do (println (str bundle-file " is stale, run: clojure -M:dev -m tokenscript-bundle"))
            (System/exit 1)))
      (do (spit bundle-file source)
          (println "wrote" bundle-file)))
    (shutdown-agents)))
