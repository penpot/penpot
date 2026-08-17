;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.exports
  (:require
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.util.object :as obj]))

(defn export-proxy
  [export-data on-change!]
  (let [state (atom export-data)]
    (obj/reify {:name "ExportProxy"}
      :type
      {:get (fn [] (format/format-key (:type @state)))
       :set (fn [v] (swap! state assoc :type (parser/parse-keyword v)) (on-change!))}

      :scale
      {:get (fn [] (:scale @state))
       :set (fn [v] (swap! state assoc :scale v) (on-change!))}

      :suffix
      {:get (fn [] (:suffix @state))
       :set (fn [v] (swap! state assoc :suffix v) (on-change!))}

      :skipChildren
      {:get (fn [] (:skip-children @state))
       :set (fn [v] (swap! state assoc :skip-children v) (on-change!))})))

(defn format-exports
  ([exports] (format-exports exports nil))
  ([exports commit-fn]
   (if (and (some? exports) (fn? commit-fn))
     (let [arr-ref    (atom nil)
           on-change! (fn [] (commit-fn @arr-ref))
           arr        (apply array (mapv #(export-proxy % on-change!) exports))]
       (reset! arr-ref arr)
       arr)
     (format/format-array format/format-export exports))))
