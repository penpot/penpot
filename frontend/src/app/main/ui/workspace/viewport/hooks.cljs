; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.hooks
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.actions :as actions]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [goog.events :as events]
   [rumext.alpha :as mf])
  (:import goog.events.EventType))

(defn setup-dom-events [viewport-ref zoom disable-paste in-viewport?]
  (let [on-key-down       (actions/on-key-down)
        on-key-up         (actions/on-key-up)
        on-mouse-move     (actions/on-mouse-move viewport-ref zoom)
        on-mouse-wheel    (actions/on-mouse-wheel viewport-ref zoom)
        on-resize         (actions/on-resize viewport-ref)
        on-paste          (actions/on-paste disable-paste in-viewport?)]
    (mf/use-layout-effect
     (mf/deps on-key-down on-key-up on-mouse-move on-mouse-wheel on-resize on-paste)
     (fn []
       (let [node (mf/ref-val viewport-ref)
             keys [(events/listen js/document EventType.KEYDOWN on-key-down)
                   (events/listen js/document EventType.KEYUP on-key-up)
                   (events/listen node EventType.MOUSEMOVE on-mouse-move)
                   ;; bind with passive=false to allow the event to be cancelled
                   ;; https://stackoverflow.com/a/57582286/3219895
                   (events/listen js/window EventType.WHEEL on-mouse-wheel #js {:passive false})
                   (events/listen js/window EventType.RESIZE on-resize)
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

(defn setup-cursor [cursor alt? panning drawing-tool drawing-path? path-editing?]
  (mf/use-effect
   (mf/deps @cursor @alt? panning drawing-tool drawing-path? path-editing?)
   (fn []
     (let [new-cursor
           (cond
             panning                         (utils/get-cursor :hand)
             (= drawing-tool :comments)      (utils/get-cursor :comments)
             (= drawing-tool :frame)         (utils/get-cursor :create-artboard)
             (= drawing-tool :rect)          (utils/get-cursor :create-rectangle)
             (= drawing-tool :circle)        (utils/get-cursor :create-ellipse)
             (or (= drawing-tool :path)
                 drawing-path?)              (utils/get-cursor :pen)
             (= drawing-tool :curve)         (utils/get-cursor :pencil)
             drawing-tool                    (utils/get-cursor :create-shape)
             (and @alt? (not path-editing?)) (utils/get-cursor :duplicate)
             :else                           (utils/get-cursor :pointer-inner))]

       (when (not= @cursor new-cursor)
         (reset! cursor new-cursor))))))

(defn setup-resize [layout viewport-ref]
  (let [on-resize (actions/on-resize viewport-ref)]
    (mf/use-layout-effect (mf/deps layout) on-resize)))

(defn setup-keyboard [alt? ctrl? space?]
  (hooks/use-stream ms/keyboard-alt #(reset! alt? %))
  (hooks/use-stream ms/keyboard-ctrl #(reset! ctrl? %))
  (hooks/use-stream ms/keyboard-space #(reset! space? %)))

(defn setup-hover-shapes [page-id move-stream objects transform selected ctrl? hover hover-ids hover-disabled? zoom]
  (let [;; We use ref so we don't recreate the stream on a change
        zoom-ref (mf/use-ref zoom)
        ctrl-ref (mf/use-ref @ctrl?)
        transform-ref (mf/use-ref nil)
        selected-ref (mf/use-ref selected)
        hover-disabled-ref (mf/use-ref hover-disabled?)

        query-point
        (mf/use-callback
         (mf/deps page-id)
         (fn [point]
           (let [zoom (mf/ref-val zoom-ref)
                 ctrl? (mf/ref-val ctrl-ref)
                 rect (gsh/center->rect point (/ 5 zoom) (/ 5 zoom))]
             (if (mf/ref-val hover-disabled-ref)
               (rx/of nil)
               (uw/ask-buffered!
                 {:cmd :selection/query
                  :page-id page-id
                  :rect rect
                  :include-frames? true
                  :clip-children? (not ctrl?)
                  :reverse? true}))))) ;; we want the topmost shape to be selected first

        over-shapes-stream
        (mf/use-memo
          (fn []
            (rx/merge
             (->> move-stream
                  ;; When transforming shapes we stop querying the worker
                  (rx/filter #(not (some? (mf/ref-val transform-ref))))
                  (rx/merge-map query-point))

             (->> move-stream
                  ;; When transforming shapes we stop querying the worker
                  (rx/filter #(some? (mf/ref-val transform-ref)))
                  (rx/map (constantly nil))))))]

    ;; Refresh the refs on a value change
    (mf/use-effect
     (mf/deps transform)
     #(mf/set-ref-val! transform-ref transform))

    (mf/use-effect
     (mf/deps zoom)
     #(mf/set-ref-val! zoom-ref zoom))

    (mf/use-effect
     (mf/deps @ctrl?)
     #(mf/set-ref-val! ctrl-ref @ctrl?))

    (mf/use-effect
     (mf/deps selected)
     #(mf/set-ref-val! selected-ref selected))

    (mf/use-effect
     (mf/deps hover-disabled?)
     #(mf/set-ref-val! hover-disabled-ref hover-disabled?))

    (hooks/use-stream
     over-shapes-stream
     (mf/deps page-id objects @ctrl?)
     (fn [ids]
       (let [is-group?
             (fn [id]
               (contains? #{:group :bool} (get-in objects [id :type])))

             selected (mf/ref-val selected-ref)

             remove-xfm (mapcat #(cp/get-parents % objects))
             remove-id? (cond-> (into #{} remove-xfm selected)
                          @ctrl?
                          (into (filter is-group?) ids))

             ids         (filterv (comp not remove-id?) ids)
             hover-shape (get objects (first ids))]

         (reset! hover hover-shape)
         (reset! hover-ids ids))))))

(defn setup-viewport-modifiers
  [modifiers objects]
  (let [transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           (d/mapm (fn [id {modifiers :modifiers}]
                     (let [center (gsh/center-shape (get objects id))]
                       (gsh/modifiers->transform center modifiers)))
                   modifiers)))

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           (->> (keys transforms)
                (mapv (d/getf objects)))))]

    ;; Layout effect is important so the code is executed before the modifiers
    ;; are applied to the shape
    (mf/use-layout-effect
     (mf/deps transforms)
     (fn []
       (utils/update-transform shapes transforms modifiers)
       #(utils/remove-transform shapes)))))

(defn inside-vbox [vbox objects frame-id]
  (let [frame (get objects frame-id)]

    (and (some? frame)
         (gsh/overlaps? frame vbox))))

(defn setup-active-frames
  [objects vbox hover active-frames]

  (mf/use-effect
   (mf/deps vbox)

   (fn []
     (swap! active-frames
            (fn [active-frames]
              (let [set-active-frames
                    (fn [active-frames id active?]
                      (cond-> active-frames
                        (and active? (inside-vbox vbox objects id))
                        (assoc id true)))]
                (reduce-kv set-active-frames {} active-frames))))))

  (mf/use-effect
   (mf/deps @hover @active-frames)
   (fn []
     (let [frame-id (if (= :frame (:type @hover))
                      (:id @hover)
                      (:frame-id @hover))]
       (when (not (contains? @active-frames frame-id))
         (swap! active-frames assoc frame-id true))))))

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
       (st/emitf (dsc/pop-shortcuts ::path))))))
