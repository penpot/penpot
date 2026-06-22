;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.worker.index
  "Page index management within the worker."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as ch]
   [app.common.geom.rect :as grc]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.worker.impl :as impl]
   [app.worker.selection :as selection]
   [app.worker.snap :as snap]
   [okulary.core :as l]))

(log/set-level! :info)

(defonce state (l/atom {:pages-index {}}))

(defmethod impl/handler :index/initialize
  [{:keys [page] :as message}]
  (let [tpoint (ct/tpoint-ms)]
    (try
      (swap! state update :pages-index assoc (:id page) page)
      (swap! state update ::selection selection/add-page page)
      (swap! state update ::snap snap/add-page page)

      (finally
        (let [elapsed (tpoint)]
          (log/dbg :hint "page indexed" :id (:id page) :elapsed elapsed ::log/sync? true))))
    nil))

(defmethod impl/handler :index/update
  [{:keys [page-id changes] :as message}]
  (let [tpoint (ct/tpoint-ms)]
    (try
      (let [old-page (dm/get-in @state [:pages-index page-id])
            new-page (-> state
                         (swap! ch/process-changes changes false)
                         (dm/get-in [:pages-index page-id]))

            text-rects (dm/get-in @state [::text-rect page-id])

            ;; Update page objects with the text data
            new-page
            (reduce-kv
             (fn [page id data]
               (update-in page [:objects id] d/patch-object data))
             new-page
             text-rects)]

        (swap! state update ::snap snap/update-page old-page new-page)
        (swap! state update ::selection selection/update-page old-page new-page))
      (catch :default cause
        (log/error :hint "error updating page index" :id page-id :cause cause))
      (finally
        (let [elapsed (tpoint)]
          (log/dbg :hint "page index updated" :id page-id :elapsed elapsed ::log/sync? true))))
    nil))

(defn- run-query-snap
  [index page-id frame-id axis ranges bounds]
  (let [match-bounds?
        (fn [[_ data]]
          (some #(or (= :guide (:type %))
                     (= :layout (:type %))
                     (grc/contains-point? bounds (:pt %))) data))

        xform
        (comp (mapcat #(snap/query index page-id frame-id axis %))
              (distinct)
              (filter match-bounds?))]
    (into [] xform ranges)))

;; FIXME: schema
(defmethod impl/handler :index/query-snap
  [{:keys [page-id frame-id axis ranges bounds]}]
  (if-let [index (get @state ::snap)]
    (run-query-snap index page-id frame-id axis ranges bounds)
    []))

;; Single round-trip for X+Y snap used by `app.main.snap/closest-snap` (e.g. shape drag).
(defmethod impl/handler :index/query-snap-xy
  [{:keys [page-id frame-id bounds x-ranges y-ranges]}]
  (if-let [index (get @state ::snap)]
    {:x (run-query-snap index page-id frame-id :x x-ranges bounds)
     :y (run-query-snap index page-id frame-id :y y-ranges bounds)}
    {:x [] :y []}))

;; FIXME: schema

(defmethod impl/handler :index/query-selection
  [message]
  (if-let [index (get @state ::selection)]
    (selection/query index message)
    []))
