;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tracks
  (:require
   [app.common.schema :as sm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn track-proxy
  [plugin-id file-id page-id shape-id kind index]
  (let [prop   (case kind :row :layout-grid-rows :column :layout-grid-columns)
        locate (fn [] (-> (u/locate-shape file-id page-id shape-id) (get prop) (nth index nil)))]
    (obj/reify {:name "TrackProxy"}
      :type
      {:get (fn [] (-> (locate) :type format/format-key))
       :set
       (fn [value]
         (let [type (keyword value)]
           (cond
             (not (contains? ctl/grid-track-types type))
             (u/not-valid plugin-id :type value)

             (not (r/check-permission plugin-id "content:write"))
             (u/not-valid plugin-id :type "Plugin doesn't have 'content:write' permission")

             (not (u/page-active? page-id))
             (u/not-valid plugin-id :type "Cannot modify a page that is not currently active")

             :else
             (st/emit! (dwsl/change-layout-track #{shape-id} kind index {:type type})))))}

      :value
      {:get (fn [] (:value (locate)))
       :set
       (fn [value]
         (cond
           (not (sm/valid-safe-number? value))
           (u/not-valid plugin-id :value value)

           (not (r/check-permission plugin-id "content:write"))
           (u/not-valid plugin-id :value "Plugin doesn't have 'content:write' permission")

           (not (u/page-active? page-id))
           (u/not-valid plugin-id :value "Cannot modify a page that is not currently active")

           :else
           (st/emit! (dwsl/change-layout-track #{shape-id} kind index {:value value}))))})))

(defn format-tracks
  [plugin-id file-id page-id shape-id kind tracks]
  (apply array
         (map-indexed (fn [index _]
                        (track-proxy plugin-id file-id page-id shape-id kind index))
                      tracks)))
