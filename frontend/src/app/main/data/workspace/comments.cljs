;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.comments
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.comments :as dcmt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.common :as dwco]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.selection :as dws]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare handle-interrupt)
(declare handle-comment-layer-click)

(defn initialize-comments
  [file-id]
  (ptk/reify ::initialize-comments
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (rx/filter #(= ::finalize %) stream)]
        (->> (rx/merge
              (rx/of (dcmt/retrieve-comment-threads file-id))
              (->> stream
                   (rx/filter mse/mouse-event?)
                   (rx/filter mse/mouse-click-event?)
                   (rx/switch-map #(rx/take 1 ms/mouse-position))
                   (rx/with-latest-from ms/keyboard-space)
                   (rx/filter (fn [[_ space]] (not space)))
                   (rx/map first)
                   (rx/map handle-comment-layer-click))
              (->> stream
                   (rx/filter dwco/interrupt?)
                   (rx/map handle-interrupt)))

             (rx/take-until stopper-s))))))

(defn- handle-interrupt
  []
  (ptk/reify ::handle-interrupt
    ptk/WatchEvent
    (watch [_ state _]
      (let [local (:comments-local state)]
        (cond
          (:draft local) (rx/of (dcmt/close-thread))
          (:open local)  (rx/of (dcmt/close-thread))
          :else          (rx/of (dwe/clear-edition-mode)
                                (dws/deselect-all true)))))))

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
          (rx/of (dcmt/close-thread))
          (let [page-id (:current-page-id state)
                file-id (:current-file-id state)
                params  {:position position
                         :page-id page-id
                         :file-id file-id}]
            (rx/of (dcmt/create-draft params))))))))

(defn center-to-comment-thread
  [{:keys [position] :as thread}]
  (dm/assert!
   "expected valid comment thread"
   (dcmt/check-comment-thread! thread))

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
  (dm/assert!
   "expected valid comment thread"
   (dcmt/check-comment-thread! thread))
  (ptk/reify ::open-comment-thread
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (dcm/go-to-workspace :file-id (:file-id thread)
                                   :page-id (:page-id thread)))

       (->> stream
            (rx/filter (ptk/type? ::dcmt/comment-threads-fetched))
            (rx/take 1)
            (rx/mapcat #(rx/of (center-to-comment-thread thread)
                               (dwd/select-for-drawing :comments)
                               (with-meta (dcmt/open-thread thread)
                                 {::ev/origin "workspace"}))))))))

(defn update-comment-thread-position
  ([thread [new-x new-y]]
   (update-comment-thread-position thread [new-x new-y] nil))

  ([thread [new-x new-y] frame-id]
   (dm/assert!
    "expected valid comment thread"
    (dcmt/check-comment-thread! thread))
   (ptk/reify ::update-comment-thread-position
     ptk/WatchEvent
     (watch [_ state _]
       (let [page      (dsh/lookup-page state)
             page-id   (:id page)
             objects   (dsh/lookup-page-objects state page-id)
             frame-id  (if (nil? frame-id)
                         (ctst/get-frame-id-by-position objects (gpt/point new-x new-y))
                         (:frame-id thread))
             thread     (-> thread
                            (assoc :position (gpt/point new-x new-y))
                            (assoc :frame-id frame-id))
             thread-id  (:id thread)]

         (rx/concat
          (rx/of #(update % :comment-threads assoc thread-id thread))
          (->> (rp/cmd! :update-comment-thread-position thread)
               (rx/catch #(rx/throw {:type :update-comment-thread-position}))
               (rx/ignore))))))))

;; Move comment threads that are inside a frame when that frame is moved"
(defmethod ptk/resolve ::move-frame-comment-threads
  [_ ids]
  (dm/assert!
   "expected a valid coll of uuid's"
   (sm/check-coll-of-uuid! ids))

  (ptk/reify ::move-frame-comment-threads
    ptk/WatchEvent
    (watch [_ state _]
      (let [page       (dsh/lookup-page state)
            objects    (get page :objects)

            is-frame?  (fn [id] (= :frame (get-in objects [id :type])))
            frame-ids? (into #{} (filter is-frame?) ids)

            threads-position-map
            (get page :comment-thread-positions)

            object-modifiers
            (:workspace-modifiers state)

            build-move-event
            (fn [comment-thread]
              (let [frame     (get objects (:frame-id comment-thread))
                    modifiers (get-in object-modifiers [(:frame-id comment-thread) :modifiers])
                    frame'    (gsh/transform-shape frame modifiers)
                    moved     (gpt/to-vec (gpt/point (:x frame) (:y frame))
                                          (gpt/point (:x frame') (:y frame')))
                    position  (get-in threads-position-map [(:id comment-thread) :position])
                    new-x     (+ (:x position) (:x moved))
                    new-y     (+ (:y position) (:y moved))]
                (update-comment-thread-position comment-thread [new-x new-y] (:id frame))))]

        (->> (:comment-threads state)
             (vals)
             (map #(assoc % :position (get-in threads-position-map [(:id %) :position])))
             (map #(assoc % :frame-id (get-in threads-position-map [(:id %) :frame-id])))
             (filter (comp frame-ids? :frame-id))
             (map build-move-event)
             (rx/from))))))

(defn navigate-to-comment
  [thread]
  (ptk/reify ::navigate-to-comment
    ptk/WatchEvent
    (watch [_ state _]
      (rx/concat
       (if (some? thread)
         (rx/of
          (rt/nav :workspace
                  (-> (rt/get-params state)
                      (assoc :page-id (:page-id thread))
                      (dissoc :comment-id))
                  {::rt/replace true}))
         (rx/empty))
       (->> (rx/of
             (dwd/select-for-drawing :comments)
             (center-to-comment-thread thread)
             (with-meta (dcmt/open-thread thread) {::ev/origin "workspace"}))
            (rx/observe-on :async))))))

(defn navigate-to-comment-id
  [thread-id]
  (ptk/reify ::navigate-to-comment-id
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-comment-threads {:file-id file-id})
             (rx/map #(d/seek (fn [{:keys [id]}] (= thread-id id)) %))
             (rx/map navigate-to-comment))))))
