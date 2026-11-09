;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.index
  "Page index management within the worker."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes :as ch]
   [app.common.geom.rect :as grc]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.worker.impl :as impl]
   [app.worker.selection :as selection]
   [app.worker.snap :as snap]
   [app.worker.trim :as trim]
   [okulary.core :as l]))

(log/set-level! :info)

(defonce state (l/atom {:pages-index {}}))

;; Changes that edit what the indexes read. Replaying one on a trimmed page
;; needs the attributes it reads in `app.worker.trim`.
(def index-change-types
  #{:add-obj :mod-obj :del-obj :fix-obj :mov-objects :reg-objects
    :reorder-children :set-guide :del-page})

;; Changes that leave the indexes alone.
(def skipped-change-types
  #{:set-comment-thread-position :set-flow :set-default-grid
    :add-page :mod-page :mov-page :set-plugin-data
    :add-color :mod-color :del-color
    :add-media :mod-media :del-media
    :add-component :mod-component :del-component :restore-component :purge-component
    :add-typography :mod-typography :del-typography
    :set-tokens-lib :set-token :set-token-set :set-token-theme :set-tokens-status
    :set-own-tokens-status :rename-token-set-group :move-token-set
    :move-token-set-group :set-base-font-size :set-tokens-source})

(defn- index-change?
  [{:keys [type]}]
  (cond
    (contains? index-change-types type)
    true

    (contains? skipped-change-types type)
    false

    :else
    (do (log/wrn :hint "change type unknown to the worker index, skipped" :type type)
        false)))

(defn- changed-object?
  [old-object new-object]
  (and (not (identical? old-object new-object))
       (not= old-object new-object)))

(defmethod impl/handler :index/initialize
  [{:keys [page] :as message}]
  (let [tpoint (ct/tpoint-ms)
        page   (trim/trim-page page)]
    (try
      (swap! state update :pages-index assoc (:id page) page)
      (swap! state update ::selection selection/add-page page)
      (swap! state update ::snap snap/add-page page)

      (finally
        (let [elapsed (tpoint)]
          (log/dbg :hint "page indexed" :id (:id page) :elapsed elapsed ::log/sync? true))))
    nil))

(defmethod impl/handler :index/finalize
  [{:keys [page-id]}]
  (swap! state #(-> %
                    (update :pages-index dissoc page-id)
                    (update ::selection dissoc page-id)
                    (update ::snap dissoc page-id)))
  (t/clear-uuid-cache)
  (log/dbg :hint "page index dropped" :id page-id ::log/sync? true)
  nil)

(defmethod impl/handler :index/update
  [{:keys [page-id changes] :as message}]
  (let [tpoint (ct/tpoint-ms)]
    (try
      (if-let [old-page (dm/get-in @state [:pages-index page-id])]
        (let [changes  (filterv index-change? changes)
              new-page (-> state
                           (swap! ch/process-changes changes false)
                           (dm/get-in [:pages-index page-id]))]
          (if (nil? new-page)
            ;; The changes deleted the page
            (swap! state #(-> %
                              (update ::snap dissoc page-id)
                              (update ::selection dissoc page-id)))

            (let [old-objects (:objects old-page)
                  ids         (ch/get-candidate-object-ids old-objects (:objects new-page) page-id changes)
                  new-page    (update new-page :objects #(trim/retrim-objects old-objects % ids))
                  new-objects (:objects new-page)
                  ids         (into #{}
                                    (filter #(changed-object? (get old-objects %) (get new-objects %)))
                                    ids)
                  guides?     (not (identical? (:guides old-page) (:guides new-page)))]
              (swap! state #(cond-> (assoc-in % [:pages-index page-id] new-page)
                              (or (seq ids) guides?)
                              (update ::snap snap/update-page old-page new-page ids)

                              (seq ids)
                              (update ::selection selection/update-page old-page new-page ids))))))

        ;; A later `:index/initialize` sends the whole page again
        (log/dbg :hint "page not indexed, skipping update" :id page-id ::log/sync? true))
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
