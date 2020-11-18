;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.comments
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.spec :as us]
   [app.main.constants :as c]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.comments :as dcm]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(declare handle-interrupt)
(declare handle-comment-layer-click)

(defn initialize-comments
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::initialize-comments
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(= ::finalize %) stream)]
        (rx/merge
         (rx/of (dcm/retrieve-comment-threads file-id))
         (->> stream
              (rx/filter ms/mouse-click?)
              (rx/switch-map #(rx/take 1 ms/mouse-position))
              (rx/map handle-comment-layer-click)
              (rx/take-until stoper))
         (->> stream
              (rx/filter dwc/interrupt?)
              (rx/map handle-interrupt)
              (rx/take-until stoper)))))))

(defn- handle-interrupt
  []
  (ptk/reify ::handle-interrupt
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local   (:comments-local state)]
        (cond
          (:draft local) (rx/of (dcm/close-thread))
          (:open local)  (rx/of (dcm/close-thread))
          :else          (rx/of #(dissoc % :workspace-drawing)))))))

;; Event responsible of the what should be executed when user clicked
;; on the comments layer. An option can be create a new draft thread,
;; an other option is close previously open thread or cancel the
;; latest opened thread draft.
(defn- handle-comment-layer-click
  [position]
  (ptk/reify ::handle-comment-layer-click
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:comments-local state)]
        (if (some? (:open local))
          (rx/of (dcm/close-thread))
          (let [page-id (:current-page-id state)
                file-id (:current-file-id state)
                params  {:position position
                         :page-id page-id
                         :file-id file-id}]
            (rx/of (dcm/create-draft params))))))))

(defn center-to-comment-thread
  [{:keys [id position] :as thread}]
  (us/assert ::dcm/comment-thread thread)
  (ptk/reify :center-to-comment-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vbox vport zoom] :as local}]
                (let [pw (/ 50 zoom)
                      ph (/ 200 zoom)
                      nw (mth/round (- (/ (:width vbox) 2) pw))
                      nh (mth/round (- (/ (:height vbox) 2) ph))
                      nx (- (:x position) nw)
                      ny (- (:y position) nh)]
                  (update local :vbox assoc :x nx :y ny)))))))


