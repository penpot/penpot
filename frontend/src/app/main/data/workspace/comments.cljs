;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.comments
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes-builder :as pcb]
   [app.common.schema :as sm]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.comments :as dcm]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.common :as dwco]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.repo :as rp]
   [app.main.streams :as ms]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(declare handle-interrupt)
(declare handle-comment-layer-click)

(defn initialize-comments
  [file-id]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::initialize-comments
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stoper (rx/filter #(= ::finalize %) stream)]
        (rx/merge
         (rx/of (dcm/retrieve-comment-threads file-id))
         (->> stream
              (rx/filter ms/mouse-click?)
              (rx/switch-map #(rx/take 1 ms/mouse-position))
              (rx/with-latest-from ms/keyboard-space)
              (rx/filter (fn [[_ space]] (not space)) )
              (rx/map first)
              (rx/map handle-comment-layer-click)
              (rx/take-until stoper))
         (->> stream
              (rx/filter dwco/interrupt?)
              (rx/map handle-interrupt)
              (rx/take-until stoper)))))))

(defn- handle-interrupt
  []
  (ptk/reify ::handle-interrupt
    ptk/WatchEvent
    (watch [_ state _]
      (let [local (:comments-local state)]
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
    (watch [_ state _]
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
  [{:keys [position] :as thread}]
  (dm/assert! (dcm/comment-thread? thread))
  (ptk/reify ::center-to-comment-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vbox zoom] :as local}]
                (let [pw (/ 160 zoom)
                      ph (/ 160 zoom)
                      nw (- (/ (:width vbox) 2) pw)
                      nh (- (/ (:height vbox) 2) ph)
                      nx (- (:x position) nw)
                      ny (- (:y position) nh)]
                  (update local :vbox assoc :x nx :y ny)))))))

(defn navigate
  [thread]
  (dm/assert! (dcm/comment-thread? thread))
  (ptk/reify ::open-comment-thread
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [pparams {:project-id (:project-id thread)
                     :file-id (:file-id thread)}
            qparams {:page-id (:page-id thread)}]
        (rx/merge
         (rx/of (rt/nav :workspace pparams qparams))
         (->> stream
              (rx/filter (ptk/type? ::dwv/initialize-viewport))
              (rx/take 1)
              (rx/mapcat #(rx/of (center-to-comment-thread thread)
                                 (dwd/select-for-drawing :comments)
                                 (dcm/open-thread thread)))))))))

(defn update-comment-thread-position
  ([thread  [new-x new-y]]
   (update-comment-thread-position thread  [new-x new-y] nil))

  ([thread  [new-x new-y] frame-id]
  (dm/assert! (dcm/comment-thread? thread))
  (ptk/reify ::update-comment-thread-position
    ptk/WatchEvent
    (watch [it state _]
      (let [thread-id (:id thread)
            page (wsh/lookup-page state)
            page-id (:id page)
            objects (wsh/lookup-page-objects state page-id)
            new-frame-id (if (nil? frame-id)
                           (ctst/frame-id-by-position objects (gpt/point new-x new-y))
                           (:frame-id thread))
            thread (assoc thread
                          :position (gpt/point new-x new-y)
                          :frame-id new-frame-id)

            changes
            (-> (pcb/empty-changes it)
                (pcb/with-page page)
                (pcb/update-page-option :comment-threads-position assoc thread-id (select-keys thread [:position :frame-id])))]

        (rx/merge
         (rx/of (dwc/commit-changes changes))
         (->> (rp/cmd! :update-comment-thread-position thread)
              (rx/catch #(rx/throw {:type :update-comment-thread-position}))
              (rx/ignore))))))))

;; Move comment threads that are inside a frame when that frame is moved"
(defmethod ptk/resolve ::move-frame-comment-threads
  [_ ids]
  (dm/assert! (sm/coll-of-uuid? ids))
  (ptk/reify ::move-frame-comment-threads
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)

            is-frame? (fn [id] (= :frame (get-in objects [id :type])))
            frame-ids? (into #{} (filter is-frame?) ids)

            object-modifiers  (:workspace-modifiers state)

            threads-position-map (:comment-threads-position (wsh/lookup-page-options state))

            build-move-event
            (fn [comment-thread]
              (let [frame (get objects (:frame-id comment-thread))
                    modifiers (get-in object-modifiers [(:frame-id comment-thread) :modifiers])
                    frame' (gsh/transform-shape frame modifiers)
                    moved (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                      (gpt/point (:x frame') (:y frame')))
                    position (get-in threads-position-map [(:id comment-thread) :position])
                    new-x (+ (:x position) (:x moved))
                    new-y (+ (:y position) (:y moved))]
                (update-comment-thread-position comment-thread [new-x new-y] (:id frame))))]

        (->> (:comment-threads state)
             (vals)
             (map #(assoc % :position (get-in threads-position-map [(:id %) :position])))
             (map #(assoc % :frame-id (get-in threads-position-map [(:id %) :frame-id])))
             (filter (comp frame-ids? :frame-id))
             (map build-move-event)
             (rx/from))))))
