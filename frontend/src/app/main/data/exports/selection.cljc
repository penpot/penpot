;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.exports.selection
  (:require
   [clojure.string :as str]))

(defn export-name
  [name object-id]
  (if (str/blank? name) (str object-id) name))

(defn plan-export
  "Plan an export using only the supplied selection and its saved presets.
  Each entry carries its base name and preset suffix separately."
  [shapes file-id page-id in-progress?]
  (cond
    in-progress?
    {:status :busy}

    (empty? shapes)
    {:status :empty-selection}

    :else
    (let [exports (into []
                        (mapcat (fn [shape]
                                  (let [name (export-name (:name shape) (:id shape))]
                                    (map (fn [export]
                                           (assoc export
                                                  :enabled true
                                                  :file-id file-id
                                                  :page-id page-id
                                                  :object-id (:id shape)
                                                  :shape (dissoc shape :exports)
                                                  :name name))
                                         (:exports shape)))))
                        shapes)]
      (if (empty? exports)
        {:status :missing-settings}
        {:status :ready
         :exports exports}))))
