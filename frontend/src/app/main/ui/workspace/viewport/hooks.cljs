; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.hooks
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as sfd]
   [app.main.ui.workspace.viewport.actions :as actions]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [debug :refer [debug?]]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn setup-dom-events [viewport-ref zoom disable-paste in-viewport? workspace-read-only?]
  (let [on-key-down       (actions/on-key-down)
        on-key-up         (actions/on-key-up)
        on-mouse-move     (actions/on-mouse-move viewport-ref zoom)
        on-mouse-wheel    (actions/on-mouse-wheel viewport-ref zoom)
        on-paste          (actions/on-paste disable-paste in-viewport? workspace-read-only?)]
    (mf/use-layout-effect
     (mf/deps on-key-down on-key-up on-mouse-move on-mouse-wheel on-paste workspace-read-only?)
     (fn []
       (let [node (mf/ref-val viewport-ref)
             keys [(events/listen js/document EventType.KEYDOWN on-key-down)
                   (events/listen js/document EventType.KEYUP on-key-up)
                   (events/listen node EventType.MOUSEMOVE on-mouse-move)
                   ;; bind with passive=false to allow the event to be cancelled
                   ;; https://stackoverflow.com/a/57582286/3219895
                   (events/listen js/window EventType.WHEEL on-mouse-wheel #js {:passive false})
                   (events/listen js/window EventType.PASTE on-paste)]]

         (fn []
           (doseq [key keys]
             (events/unlistenByKey key))))))))

(defn setup-viewport-size [viewport-ref]
  (mf/use-layout-effect
   (fn []
     (let [node (mf/ref-val viewport-ref)
           prnt (dom/get-parent node)
           size (dom/get-client-size prnt)]
       ;; We schedule the event so it fires after `initialize-page` event
       (timers/schedule #(st/emit! (dw/initialize-viewport size)))))))

(defn setup-cursor [cursor alt? mod? space? panning drawing-tool drawing-path? path-editing? workspace-read-only?]
  (mf/use-effect
   (mf/deps @cursor @alt? @mod? @space? panning drawing-tool drawing-path? path-editing? workspace-read-only?)
   (fn []
     (let [show-pen? (or (= drawing-tool :path)
                         (and drawing-path?
                              (not= drawing-tool :curve)))
           new-cursor
           (cond
             (and @mod? @space?)             (utils/get-cursor :zoom)
             (or panning @space?)            (utils/get-cursor :hand)
             (= drawing-tool :comments)      (utils/get-cursor :comments)
             (= drawing-tool :frame)         (utils/get-cursor :create-artboard)
             (= drawing-tool :rect)          (utils/get-cursor :create-rectangle)
             (= drawing-tool :circle)        (utils/get-cursor :create-ellipse)
             show-pen?                       (utils/get-cursor :pen)
             (= drawing-tool :curve)         (utils/get-cursor :pencil)
             drawing-tool                    (utils/get-cursor :create-shape)
             (and
              @alt?
              (not path-editing?)
              (not workspace-read-only?))    (utils/get-cursor :duplicate)
             :else                           (utils/get-cursor :pointer-inner))]

       (when (not= @cursor new-cursor)
         (reset! cursor new-cursor))))))

(defn setup-keyboard [alt? mod? space?]
  (hooks/use-stream ms/keyboard-alt #(reset! alt? %))
  (hooks/use-stream ms/keyboard-mod #(reset! mod? %))
  (hooks/use-stream ms/keyboard-space #(reset! space? %)))

(defn group-empty-space?
  "Given a group `group-id` check if `hover-ids` contains any of its children. If it doesn't means
  we're hovering over empty space for the group "
  [group-id objects hover-ids]

  (and (contains? #{:group :bool} (get-in objects [group-id :type]))

       ;; If there are no children in the hover-ids we're in the empty side
       (->> hover-ids
            (remove #(contains? #{:group :bool} (get-in objects [% :type])))
            (some #(cph/is-parent? objects % group-id))
            (not))))

(defn setup-hover-shapes
  [page-id move-stream objects transform selected mod? hover hover-ids hover-top-frame-id hover-disabled? focus zoom]
  (let [;; We use ref so we don't recreate the stream on a change
        zoom-ref (mf/use-ref zoom)
        mod-ref (mf/use-ref @mod?)
        transform-ref (mf/use-ref nil)
        selected-ref (mf/use-ref selected)
        hover-disabled-ref (mf/use-ref hover-disabled?)
        focus-ref (mf/use-ref focus)

        last-point-ref (mf/use-var nil)
        mod-str (mf/use-memo #(rx/subject))

        query-point
        (mf/use-callback
         (mf/deps page-id)
         (fn [point]
           (let [zoom (mf/ref-val zoom-ref)
                 mod? (mf/ref-val mod-ref)
                 rect (gsh/center->rect point (/ 5 zoom) (/ 5 zoom))]
             (if (mf/ref-val hover-disabled-ref)
               (rx/of nil)
               (->> (uw/ask-buffered!
                     {:cmd :selection/query
                      :page-id page-id
                      :rect rect
                      :include-frames? true
                      :clip-children? (not mod?)})
                    ;; When the ask-buffered is canceled returns null. We filter them
                    ;; to improve the behavior
                    (rx/filter some?))))))

        over-shapes-stream
        (mf/use-memo
          (fn []
            (rx/merge
             ;; This stream works to "refresh" the outlines when the control is pressed
             ;; but the mouse has not been moved from its position.
             (->> mod-str
                  (rx/observe-on :async)
                  (rx/map #(deref last-point-ref)))

             (->> move-stream
                  (rx/tap #(reset! last-point-ref %))
                  ;; When transforming shapes we stop querying the worker
                  (rx/merge-map query-point)
                  ))))]

    ;; Refresh the refs on a value change
    (mf/use-effect
     (mf/deps transform)
     #(mf/set-ref-val! transform-ref transform))

    (mf/use-effect
     (mf/deps zoom)
     #(mf/set-ref-val! zoom-ref zoom))

    (mf/use-effect
     (mf/deps @mod?)
     (fn []
       (rx/push! mod-str :update)
       (mf/set-ref-val! mod-ref @mod?)))

    (mf/use-effect
     (mf/deps selected)
     #(mf/set-ref-val! selected-ref selected))

    (mf/use-effect
     (mf/deps hover-disabled?)
     #(mf/set-ref-val! hover-disabled-ref hover-disabled?))

    (mf/use-effect
     (mf/deps focus)
     #(mf/set-ref-val! focus-ref focus))

    (hooks/use-stream
     over-shapes-stream
     (mf/deps page-id objects)
     (fn [ids]
       (let [selected (mf/ref-val selected-ref)
             focus (mf/ref-val focus-ref)
             mod? (mf/ref-val mod-ref)

             ids (into
                  (d/ordered-set)
                  (ctt/sort-z-index objects ids {:bottom-frames? mod?}))

             grouped? (fn [id] (contains? #{:group :bool} (get-in objects [id :type])))

             selected-with-parents
             (into #{} (mapcat #(cph/get-parent-ids objects %)) selected)

             root-frame-with-data?
             #(as-> (get objects %) obj
                (and (cph/root-frame? obj) (d/not-empty? (:shapes obj))))

             ;; Set with the elements to remove from the hover list
             remove-id?
             (cond-> selected-with-parents
               (not mod?)
               (into (filter #(or (root-frame-with-data? %)
                                  (group-empty-space? % objects ids)))
                     ids)

               mod?
               (into (filter grouped?) ids))

             hover-shape
             (->> ids
                  (remove remove-id?)
                  (filter #(or (empty? focus) (cp/is-in-focus? objects focus %)))
                  (first)
                  (get objects))]
         (reset! hover hover-shape)
         (reset! hover-ids ids)
         (reset! hover-top-frame-id (ctt/top-nested-frame objects (deref last-point-ref))))))))

(defn setup-viewport-modifiers
  [modifiers objects]
  (let [root-frame-ids
        (mf/use-memo
         (mf/deps objects)
         #(ctt/get-root-shapes-ids objects))
        modifiers (select-keys modifiers (conj root-frame-ids uuid/zero))]
    (sfd/use-dynamic-modifiers objects globals/document modifiers)))

(defn inside-vbox [vbox objects frame-id]
  (let [frame (get objects frame-id)]
    (and (some? frame) (gsh/overlaps? frame vbox))))

(defn setup-active-frames
  [objects hover-ids selected active-frames zoom transform vbox]

  (let [all-frames             (mf/use-memo (mf/deps objects) #(ctt/get-root-frames-ids objects))
        selected-frames        (mf/use-memo (mf/deps selected) #(->> all-frames (filter selected)))

        xf-selected-frame      (comp (remove cph/root-frame?)
                                     (map #(cph/get-shape-id-root-frame objects %)))

        selected-shapes-frames (mf/use-memo (mf/deps selected) #(into #{} xf-selected-frame selected))

        active-selection       (when (and (not= transform :move) (= (count selected-frames) 1)) (first selected-frames))
        last-hover-ids       (mf/use-var nil)]

    (mf/use-effect
     (mf/deps @hover-ids)
     (fn []
       (when (d/not-empty? @hover-ids)
         (reset! last-hover-ids (set @hover-ids)))))

    (mf/use-effect
     (mf/deps objects @hover-ids selected zoom transform vbox)
     (fn []

       ;; Rules for active frame:
       ;; - If zoom < 25% displays thumbnail except when selecting a single frame or a child
       ;; - We always active the current hovering frame for zoom > 25%
       ;; - When zoom > 130% we activate the frames that are inside the vbox
       ;; - If no hovering over any frames we keep the previous active one
       ;; - Check always that the active frames are inside the vbox

       (let [hover-ids? (set (->> @hover-ids (map #(cph/get-shape-id-root-frame objects %))))

             is-active-frame?
             (fn [id]
               (or
                ;; Zoom > 130% shows every frame
                (> zoom 1.3)

                ;; Zoom >= 25% will show frames hovering
                (and
                 (>= zoom 0.25)
                 (or (contains? hover-ids? id) (contains? @last-hover-ids id)))

                ;; Otherwise, if it's a selected frame
                (= id active-selection)

                ;; Or contains a selected shape
                (contains? selected-shapes-frames id)))

             new-active-frames
             (into #{}
                   (comp (filter is-active-frame?)

                         ;; We only allow active frames that are contained in the vbox
                         (filter (partial inside-vbox vbox objects)))
                   all-frames)

             ;; Debug only: Disable the thumbnails
             new-active-frames
             (if (debug? :disable-frame-thumbnails) (into #{} all-frames) new-active-frames)]

         (when (not= @active-frames new-active-frames)
           (reset! active-frames new-active-frames)))))))

;; NOTE: this is executed on each page change, maybe we need to move
;; this shortcuts outside the viewport?

(defn setup-shortcuts
  [path-editing? drawing-path?]
  (hooks/use-shortcuts ::workspace wsc/shortcuts)
  (mf/use-effect
   (mf/deps path-editing? drawing-path?)
   (fn []
     (when (or drawing-path? path-editing?)
       (st/emit! (dsc/push-shortcuts ::path psc/shortcuts))
       #(st/emit! (dsc/pop-shortcuts ::path))))))
