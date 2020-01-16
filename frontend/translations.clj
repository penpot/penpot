(require '[clojure.pprint :as pp :refer [pprint]])
(require '[clojure.java.shell :as shell])
(require '[environ.core :refer [env]])

(require '[clojure.walk :as walk]
         '[clojure.edn :as edn]
         '[clojure.set :as set])

(require '[datoteka.core :as fs]
         '[jsonista.core :as json])
(require '[clojure.java.io :as io]
         '[clojure.tools.reader :as r]
         '[clojure.tools.reader.reader-types :as rt])

(import 'java.nio.file.Paths
        'java.nio.file.Path
        'java.nio.file.Files
        'java.nio.file.SimpleFileVisitor
        'java.nio.file.FileVisitResult)

(extend-protocol io/Coercions
  Path
  (as-file [it] (.toFile it))
  (as-url [it] (io/as-url (.toFile it))))

(defmulti task first)

(defn- find-translations-in-form
  [env form]
  (->> form
       (walk/postwalk
        (fn [fm]
          (cond
            (and (list? fm)
                 (= (first fm) 'tr)
                 (string? (second fm)))
            (let [m (meta (first fm))]
              (swap! env conj {:code (second fm)
                               :file (:file m)
                               :line (:line m)}))

            (and (list? fm)
                 (= (first fm) 't)
                 (symbol? (second fm)))
            (let [m (meta (first fm))
                  code (first (drop 2 fm))]
              (swap! env conj {:code code
                               :file (:file m)
                               :line (:line m)})))
          fm))))

(defn- find-translations-in-file
  [env file]
  (let [rdr (-> (io/as-file file)
                (io/reader)
                (rt/source-logging-push-back-reader 1 file))]
    (try
      (binding [r/*default-data-reader-fn* (constantly nil)
                r/*alias-map* {'dw (create-ns 'user)
                               'fm (create-ns 'user)
                               'us (create-ns 'user)
                               'dp (create-ns 'user)
                               'cp (create-ns 'user)}]
        (loop []
          (let [form (r/read {:eof ::end} rdr)]
            (when (not= ::end form)
              (find-translations-in-form env form)
              (recur)))))
      (catch Exception e
        ;; (.printStackTrace e)
        (println (str "ERROR: on procesing " file "; ignoring..."))))))

(defn- find-translations-in-directory
  [env file]
  (->> (proxy [SimpleFileVisitor] []
         (visitFile [path attrs]
           (when (= (fs/ext path) "cljs")
             (find-translations-in-file env path))
           FileVisitResult/CONTINUE)
         (postVisitDirectory [dir exc]
           FileVisitResult/CONTINUE))
       (Files/walkFileTree (fs/path file))))

(defn- collect-translations
  [path]
  (let [env (atom [])]
    (find-translations-in-directory env path)
    @env))

(defn- read-json-file
  [path]
  (when (fs/regular-file? path)
    (let [content (json/read-value (slurp (io/as-file path)))]
      (into (sorted-map) content))))

(defn- read-edn-file
  [path]
  (when (fs/regular-file? path)
    (let [content (edn/read-string (slurp (io/as-file path)))]
      (into (sorted-map) content))))


(defn- add-translation
  [data {:keys [code file line] :as translation}]
  (let [rpath (str file ":" line)]
    (if (contains? data code)
      (update data code (fn [state]
                          (if (get state "permanent")
                            state
                            (-> state
                                (dissoc "unused")
                                (update "used-in" conj rpath)))))
      (assoc data code {"translations" {"en" nil "fr" nil}
                        "used-in" [rpath]}))))

(defn- clean-removed-translations
  [data imported]
  (let [existing (into #{} (keys data))
        toremove (set/difference existing imported)]
    (reduce (fn [data code]
              (if (get-in data [code "permanent"])
                data
                (-> data
                    (update code dissoc "used-in")
                    (update code assoc "unused" true))))
            data
            toremove)))

(defn- initial-cleanup
  [data]
  (reduce-kv (fn [data k v]
               (if (string? v)
                 (assoc data k {"used-in" []
                                "translations" {:en v}})
                 (update data k assoc "used-in" [])))
             data
             data))

(defn- synchronize-translations
  [data translations]
  (loop [data (initial-cleanup data)
         imported #{}
         c (first translations)
         r (rest translations)]
    (if (nil? c)
      (clean-removed-translations data imported)
      (recur (add-translation data c)
             (conj imported (:code c))
             (first r)
             (rest r)))))

(defn- synchronize-legacy-translations
  [data legacy-data lang]
  (reduce-kv (fn [data k v]
               (if (contains? data k)
                 (update-in data [k "translations"] assoc lang v)
                 data))
             data
             legacy-data))

(defn- write-result!
  [data output-path]
  (binding [*out* (io/writer (fs/path output-path))]
    (let [mapper (json/object-mapper {:pretty true})]
      (println (json/write-value-as-string data mapper))
      (flush))))

(defn- update-translations
  [{:keys [find-directory output-path] :as props}]
  (let [data (read-json-file output-path)
        translations (collect-translations find-directory)
        data (synchronize-translations data translations)]
    (write-result! data output-path)))

(defmethod task "collectmessages"
  [[_ in-path out-path]]
  (update-translations {:find-directory in-path
                        :output-path out-path}))

(defmethod task "merge-with-legacy"
  [[_ path lang legacy-path]]
  (let [ldata (read-edn-file legacy-path)
        data (read-json-file path)
        data (synchronize-legacy-translations data ldata lang)]
    (write-result! data path)))

(task *command-line-args*)
