;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.schemas
  "Tokenscript schemas shipped with Penpot: the color spaces, functions
  and constants in `common/resources/tokenscript/schemas`.

  Every schema is a directory with a `schema.json` and the tokenscript
  files it references (`\"script\": \"./initializer.tokenscript\"`):

    types/<name>/       color spaces     -> /api/v1/core/<name>/0/
    functions/<name>/   functions        -> /api/v1/function/<name>/0/
    constants/<name>/   inline constants -> /api/v1/constants/<name>/0/

  `schemas` returns them as `{:uri :schema}` maps, with the script file
  references replaced by the script text. On the JVM they are read from
  the classpath on first use. In ClojureScript the `inline-json` macro
  reads and checks them at compile time and embeds them as minified
  JSON, decoded on first use. URIs stay path URIs: schemas are
  identified by them, never fetched."
  (:require
   #?@(:clj [[app.common.tokenscript.parser :as parser]
             [app.common.tokenscript.util :as u]
             [clojure.java.io :as io]
             [clojure.string :as str]])
   [app.common.json :as json])
  #?(:cljs (:require-macros [app.common.tokenscript.schemas :refer [inline-json]])))

;; Reading --------------------------------------------------------------------

#?(:clj
   (def ^:private root "tokenscript/schemas"))

#?(:clj
   (def ^:private categories
     [["types" "core"]
      ["functions" "function"]
      ["constants" "constants"]]))

#?(:clj
   (def ^:private ^:dynamic *on-read*
     "Called with the path and URL of every resource read, see
     `inline-json`."
     nil))

#?(:clj
   (defn- fail
     [message data]
     (throw (ex-info message data))))

#?(:clj
   (defn- resource
     [path]
     (let [url (or (io/resource path)
                   (fail (str "Missing tokenscript schema resource " path) {:path path}))]
       (when *on-read* (*on-read* path url))
       url)))

#?(:clj
   (defn- schema-names
     "Names of the schema directories of `category`, sorted. Works from
     a directory and from a jar on the classpath."
     [category]
     (let [dir (str root "/" category)
           url (resource dir)]
       (sort
        (case (.getProtocol ^java.net.URL url)
          "file"
          (for [^java.io.File f (.listFiles (io/file (.toURI ^java.net.URL url)))
                :when (.isFile (io/file f "schema.json"))]
            (.getName f))

          "jar"
          (let [pattern (re-pattern (str "\\Q" dir "/\\E([^/]+)/schema\\.json"))
                conn (doto ^java.net.JarURLConnection (.openConnection ^java.net.URL url)
                       (.setUseCaches false))]
            (with-open [jar (.getJarFile conn)]
              (into []
                    (keep #(second (re-matches pattern (.getName ^java.util.jar.JarEntry %))))
                    (enumeration-seq (.entries jar)))))

          (fail (str "Cannot list tokenscript schemas in " url) {:url (str url)}))))))

#?(:clj
   (defn- resolve-path
     "Resource path of `path` relative to the directory `dir`."
     [dir path]
     (-> (java.net.URI. (str dir "/"))
         (.resolve ^String path)
         (.normalize)
         (.getPath))))

#?(:clj
   (defn- inline-script
     "Replaces a script file reference (`./x.tokenscript`) by the script
     text."
     [dir {:keys [script] :as script-map}]
     (cond-> script-map
       (str/starts-with? script ".")
       (assoc :script (str/trim (slurp (resource (resolve-path dir script))))))))

#?(:clj
   (defn- inline-scripts
     [dir schema]
     (let [inline (fn [entries] (mapv #(update % :script (partial inline-script dir)) entries))]
       (case (:type schema)
         "color" (-> schema
                     (update :initializers inline)
                     (update :conversions inline))
         "function" (update schema :script (partial inline-script dir))
         schema))))

#?(:clj
   (defn- read-schema
     [category uri-category name]
     (let [dir (str root "/" category "/" name)
           schema (json/decode (slurp (resource (str dir "/schema.json"))) :key-fn keyword)]
       {:uri (str "/api/v1/" uri-category "/" name "/0/")
        :schema (inline-scripts dir schema)})))

#?(:clj
   (defn read-schemas
     "Schemas from the classpath, sorted by category and name."
     []
     (vec
      (for [[category uri-category] categories
            name (schema-names category)]
        (read-schema category uri-category name)))))

;; Checks ---------------------------------------------------------------------

#?(:clj
   (defn- scripts
     [{:keys [schema]}]
     (case (:type schema)
       "color" (map #(get-in % [:script :script]) (concat (:initializers schema) (:conversions schema)))
       "function" [(get-in schema [:script :script])]
       nil)))

#?(:clj
   (defn- referenced-uris
     [{:keys [schema]}]
     (concat
      (->> (:conversions schema)
           (mapcat (juxt :source :target))
           (remove #{"$self"}))
      (:requirements schema))))

#?(:clj
   (defn check-schemas!
     "Returns `schemas`. Throws when a script does not parse, a schema
     references a schema that is not shipped, or two functions share a
     keyword."
     [schemas]
     (let [uris (set (map :uri schemas))]
       (doseq [s schemas
               script (scripts s)]
         (try
           (parser/parse script)
           (catch Throwable e
             (fail (str "A script of " (:uri s) " does not parse: " (ex-message e)) {:uri (:uri s)}))))
       (doseq [s schemas
               uri (referenced-uris s)]
         (when-not (contains? uris uri)
           (fail (str (:uri s) " references " uri ", which is not shipped") {:uri (:uri s) :reference uri})))
       (doseq [[kw dups] (->> schemas
                              (filter #(= "function" (get-in % [:schema :type])))
                              (group-by #(u/name-key (get-in % [:schema :keyword]))))
               :when (> (count dups) 1)]
         (fail (str "Function keyword " kw " is defined by " (str/join ", " (map :uri dups))) {:keyword kw}))
       schemas)))

;; Embedding ------------------------------------------------------------------

#?(:clj
   (defmacro inline-json
     "The checked schemas as a minified JSON string literal. Under
     shadow-cljs every file read is recorded like `shadow.resource/inline`
     does, so editing a schema recompiles the namespace."
     []
     (let [ns-name (-> &env :ns :name)
           compiler (some-> (resolve 'cljs.env/*compiler*) deref)
           last-modified (some-> (resolve 'shadow.cljs.util/url-last-modified) deref)]
       (binding [*on-read* (when (and ns-name compiler last-modified)
                             (fn [path url]
                               (swap! compiler assoc-in
                                      [:cljs.analyzer/namespaces ns-name :shadow.resource/resource-refs path]
                                      (last-modified url))))]
         (-> (read-schemas)
             (check-schemas!)
             (json/encode :escape-slash false :escape-unicode false))))))

(def ^:private schemas*
  (delay
    #?(:clj (read-schemas)
       :cljs (json/decode (inline-json) :key-fn keyword))))

(defn schemas
  "The shipped schemas, `{:uri :schema}` maps."
  []
  @schemas*)
