(ns app.common.thumbnails
  (:require
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn fmt-object-id
  "Returns ids formatted as a string (object-id)"
  ([object]
   (fmt-object-id (:file-id object)
                  (:page-id object)
                  (:frame-id object)
                  (:tag object)))
  ([file-id page-id frame-id tag]
   (str/ffmt "%/%/%/%" file-id page-id frame-id tag)))

;; FIXME: rename to a proper name

(defn file-id?
  "Returns ids formatted as a string (file-id)"
  [object-id file-id]
  (str/starts-with? object-id (str/concat file-id "/")))

(defn parse-object-id
  [object-id]
  (let [[file-id page-id frame-id tag] (str/split object-id "/")]
    {:file-id (parse-uuid file-id)
     :page-id (parse-uuid page-id)
     :frame-id (parse-uuid frame-id)
     :tag tag}))

(defn- parse-uuid*
  "`parse-uuid` that answers nil for a value that is not a uuid: the JVM parser
  throws and the JS one answers nil, and the strict object id needs the same
  answer on both runtimes."
  [value]
  (try
    (parse-uuid value)
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- control-char?
  "True for a control character, which the tag cannot carry because it becomes
  one segment of the key of an exported package."
  [ch]
  #?(:clj  (Character/isISOControl (int ch))
     :cljs (let [n (.charCodeAt ch 0)]
             (or (<= 0 n 31) (= n 127)))))

(defn parse-object-id-strict
  "The `{:file-id :page-id :frame-id :tag}` of an object id that has the
  `file/page/frame/tag` shape, with three uuids and a tag that can be one
  segment of the key of an exported package. Answers nil otherwise, so the
  export can skip a legacy row and the creation command can refuse it.

  A blank tag, the separator and a control character are not a tag: the export
  writes the tag as the first segment of `tag/page-id/frame-id`. A trailing
  separator is refused explicitly: `clojure.string/split` drops the empty
  segment at the end on the JVM and keeps it on JS, so the count alone answers
  differently on each runtime and `.../tag/` would collapse into `.../tag`
  when the export rebuilds the key."
  [object-id]
  (when (and (string? object-id)
             (not (str/ends-with? object-id "/")))
    (let [[file-id page-id frame-id tag :as parts] (str/split object-id #"/")]
      (when (= 4 (count parts))
        (let [file-id  (parse-uuid* file-id)
              page-id  (parse-uuid* page-id)
              frame-id (parse-uuid* frame-id)]
          (when (and (some? file-id)
                     (some? page-id)
                     (some? frame-id)
                     (not (str/blank? tag))
                     (not-any? control-char? tag))
            {:file-id file-id
             :page-id page-id
             :frame-id frame-id
             :tag tag}))))))

(defn get-file-id
  [object-id]
  (uuid/uuid (str/slice object-id 0 (str/index-of object-id "/"))))
