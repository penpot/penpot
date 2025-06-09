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
   [app.main.data.workspace.zoom :as dwz]
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

(defn- move-frame-comment-threads
  [ids transforms]
  (assert (sm/check-coll-of-uuid ids))

  (ptk/reify ::move-frame-comment-threads
    ptk/WatchEvent
    (watch [_ state _]
      (let [page       (dsh/lookup-page state)
            objects    (get page :objects)

            is-frame?  (fn [id] (= :frame (get-in objects [id :type])))
            frame-ids? (into #{} (filter is-frame?) ids)

            threads-position-map
            (get page :comment-thread-positions)

            object-modifiers (:workspace-modifiers state)

            build-move-event
            (fn [comment-thread]
              (let [frame-id (:frame-id comment-thread)
                    frame     (get objects frame-id)
                    modifiers (get-in object-modifiers [frame-id :modifiers])
                    transform (get transforms frame-id)

                    frame'
                    (cond-> frame
                      (some? modifiers)
                      (gsh/transform-shape modifiers)

                      (some? transform)
                      (gsh/apply-transform transform))

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

(defmethod ptk/resolve ::move-frame-comment-threads
  [_ ids-or-transforms]
  (when (d/not-empty? ids-or-transforms)
    (move-frame-comment-threads
     (if (map? ids-or-transforms) (keys ids-or-transforms) ids-or-transforms)
     (when (map? ids-or-transforms) ids-or-transforms))))

(defn overlap-bubbles?
  "Detect if two bubbles overlap"
  [zoom thread-1 thread-2]
  (let [distance         (gpt/distance (:position thread-1) (:position thread-2))
        distance-zoom    (* distance zoom)
        distance-overlap 32]
    (< distance-zoom distance-overlap)))

(defn- calculate-zoom-scale-to-ungroup-current-bubble
  "Calculate the minimum zoom scale needed to keep the current bubble ungrouped from the rest"
  [zoom thread threads]
  (let [threads-rest    (filterv #(not= (:id %) (:id thread)) threads)
        zoom-scale-step 1.75]
    (if (some #(overlap-bubbles? zoom thread %) threads-rest)
      (calculate-zoom-scale-to-ungroup-current-bubble (* zoom zoom-scale-step) thread threads)
      zoom)))

(defn set-zoom-to-separate-grouped-bubbles
  [thread]
  (dm/assert!
   "zoom-to-separate-bubbles"
   (dcmt/check-comment-thread! thread))
  (ptk/reify ::set-zoom-to-separate-grouped-bubbles
    ptk/WatchEvent
    (watch [_ state _]
      (let [local        (:workspace-local state)
            zoom         (:zoom local)
            page-id      (:page-id thread)

            threads-map  (:comment-threads state)
            threads-all  (vals threads-map)
            threads      (filterv #(= (:page-id %) page-id) threads-all)

            updated-zoom (calculate-zoom-scale-to-ungroup-current-bubble zoom thread threads)
            scale-zoom   (/ updated-zoom zoom)]

        (rx/of (dwz/set-zoom scale-zoom))))))

(defn navigate-to-comment-from-dashboard
  [thread]
  (dm/assert!
   "expected valid comment thread"
   (dcmt/check-comment-thread! thread))
  (ptk/reify ::navigate-to-comment-from-dashboard
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (dcm/go-to-workspace :file-id (:file-id thread)
                                   :page-id (:page-id thread)))

       (->> stream
            (rx/filter (ptk/type? :app.main.data.workspace/workspace-initialized))
            (rx/observe-on :async)
            (rx/take 1)
            (rx/mapcat #(rx/of (dwd/select-for-drawing :comments)
                               (set-zoom-to-separate-grouped-bubbles thread)
                               (center-to-comment-thread thread)
                               (with-meta (dcmt/open-thread thread)
                                 {::ev/origin "workspace"}))))))))

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
             (set-zoom-to-separate-grouped-bubbles thread)
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
