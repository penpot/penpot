(require '[clojure.pprint :as pp :refer [pprint]])

(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.java.io :as io])

(require '[datoteka.core :as fs]
         '[jsonista.core :as json]
         '[parcera.core :as pa])

(import 'java.nio.file.Paths
        'java.nio.file.Path
        'java.nio.file.Files
        'java.nio.file.SimpleFileVisitor
        'java.nio.file.FileVisitResult)

(defmulti task first)

(defn- find-translations-in-form
  [form]
  (reduce (fn [messages node]
            (let [found (->> node
                             (filter #(and (seq? %) (= :string (first %))))
                             (map (fn [item]
                                    (let [mdata (meta item)]
                                      {:code (edn/read-string (second item))
                                       :line (get-in mdata [::pa/start :row])}))))]
              (into messages found)))
          []
          (->> (tree-seq seq? seq form)
               (filter #(and (seq? %)
                             (seq? (second %))
                             (= :list (first %))
                             (= :symbol (first (second %)))
                             (or (= "t" (second (second %)))
                                 (= "tr" (second (second %)))))))))

(defn- find-translations
  [path]
  (let [forms (pa/ast (slurp path))
        spath (str path)]
    (->> forms
         (filter #(and (seq? %) (= :list (first %))))
         (reduce (fn [messages form]
                   (->> (find-translations-in-form form)
                        (map #(assoc % :file spath))
                        (into messages))) []))))

(defn- collect-translations
  [path]
  (let [messages (atom [])]
    (->> (proxy [SimpleFileVisitor] []
           (visitFile [path attrs]
             (when (= (fs/ext path) "cljs")
               (swap! messages into (find-translations path)))
             FileVisitResult/CONTINUE)
           (postVisitDirectory [dir exc]
             FileVisitResult/CONTINUE))
         (Files/walkFileTree (fs/path path)))
    @messages))

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
  (let [
        data (read-json-file output-path)
        translations (collect-translations find-directory)
        data (synchronize-translations data translations)
        ]
    (write-result! data output-path)))

(defmethod task "collect"
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
