;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.util.svg
  (:require
   [app.common.uuid :as uuid]
   [app.common.data :as cd]
   [cuerdas.core :as str]))

(defonce replace-regex #"[^#]*#([^)\s]+).*")

(defn clean-attrs
  "Transforms attributes to their react equivalent"
  [attrs]
  (letfn [(transform-key [key]
            (-> (name key)
                (str/replace ":" "-")
                (str/camel)
                (keyword)))

          (format-styles [style-str]
            (->> (str/split style-str ";")
                 (map str/trim)
                 (map #(str/split % ":"))
                 (group-by first)
                 (map (fn [[key val]]
                        (vector
                         (transform-key key)
                         (second (first val)))))
                 (into {})))

          (map-fn [[key val]]
            (cond
              (= key :class) [:className val]
              (and (= key :style) (string? val)) [key (format-styles val)]
              :else (vector (transform-key key) val)))]

    (->> attrs
         (map map-fn)
         (into {}))))

(defn replace-attrs-ids
  "Replaces the ids inside a property"
  [attrs ids-mapping]
  (if (and ids-mapping (not (empty? ids-mapping)))
    (letfn [(replace-ids [key val]
              (cond
                (map? val)
                (cd/mapm replace-ids val)

                (and (= key :id) (contains? ids-mapping val))
                (get ids-mapping val)

                :else
                (let [[_ from-id] (re-matches replace-regex val)]
                      (if (and from-id (contains? ids-mapping from-id))
                        (str/replace val from-id (get ids-mapping from-id))
                        val))))]
      (cd/mapm replace-ids attrs))

    ;; Ids-mapping is null
    attrs))

(defn generate-id-mapping [content]
  (letfn [(visit-node [result node]
            (let [element-id (get-in node [:attrs :id])
                  result (cond-> result
                           element-id (assoc element-id (str (uuid/next))))]
              (reduce visit-node result (:content node))))]
    (visit-node {} content)))
