;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.style
  "A fonts loading macros."
  (:require
   [app.common.data :as d]
   [clojure.core :as c]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rumext.v2.util :as mfu]))

(def ^:dynamic *css-data* {})

(def ^:private xform-css
  (map (fn [k] (or (get *css-data* k) (name k)))))

(defmacro css*
  "Just coerces all params to strings and concats them with
  space. Used mainly to set a set of classes together."
  [& selectors]
  (->> selectors
       (map name)
       (interpose " ")
       (apply str)))

(defmacro css
  "Uses a css-modules defined data for real class lookup, then concat
  all classes with space in the same way as `css*`."
  [& selectors]
  (let [fname (-> *ns* meta :file)
        path  (str (subs fname 0 (- (count fname) 4)) "css.json")
        data  (-> (slurp (io/resource path))
                  (json/read-str :key-fn keyword))]
    (if (symbol? (first selectors))
      `(if ~(with-meta (first selectors) {:tag 'boolean})
         (css* ~@(binding [*css-data* data]
                   (into [] xform-css (rest selectors))))
         (css* ~@(rest selectors)))
      `(css* ~@(binding [*css-data* data]
                 (into [] xform-css selectors))))))

(defmacro styles
  []
  ;; Get the associated styles will be module.cljs => module.css.json
  (let [fname (-> *ns* meta :file)
        path  (str (subs fname 0 (- (count fname) 4)) "css.json")]
    (-> (slurp (io/resource path))
        (json/read-str :key-fn keyword))))

(def ^:private xform-css-case
  (comp
   (partition-all 2)
   (keep (fn [[k v]]
           (let [cls (cond
                       (keyword? k) (or (get *css-data* k) (name k))
                       (string? k)  k)]
             (when cls
               (cond
                 (true? v) cls
                 (false? v) nil
                 :else     `(if ~v ~cls ""))))))
   (interpose " ")))

(defmacro css-case
  [& params]
  (let [fname (-> *ns* meta :file)
        path  (str (subs fname 0 (- (count fname) 4)) "css.json")
        data  (-> (slurp (io/resource path))
                  (json/read-str :key-fn keyword))]

    (if (symbol? (first params))
      `(if ~(with-meta (first params) {:tag 'boolean})
         ~(binding [*css-data* data]
            (-> (into [] xform-css-case (rest params))
                (mfu/compile-concat  :safe? false)))
         ~(-> (into [] xform-css-case (rest params))
              (mfu/compile-concat  :safe? false)))
      `~(binding [*css-data* data]
            (-> (into [] xform-css-case params)
                (mfu/compile-concat  :safe? false))))))

(defmacro css-case*
  [& params]
  (-> (into [] xform-css-case params)
      (mfu/compile-concat  :safe? false)))
