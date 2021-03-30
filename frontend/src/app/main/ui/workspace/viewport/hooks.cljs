; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.main.ui.workspace.viewport.hooks
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.data.workspace :as dw]
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
             prnt (dom/get-parent node)

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

(defn setup-cursor [cursor alt? panning drawing-tool drawing-path?]
  (mf/use-effect
   (mf/deps @cursor @alt? panning drawing-tool drawing-path?)
   (fn []
     (let [new-cursor
           (cond
             panning                     (utils/get-cursor :hand)
             (= drawing-tool :comments)  (utils/get-cursor :comments)
             (= drawing-tool :frame)     (utils/get-cursor :create-artboard)
             (= drawing-tool :rect)      (utils/get-cursor :create-rectangle)
             (= drawing-tool :circle)    (utils/get-cursor :create-ellipse)
             (or (= drawing-tool :path)
                 drawing-path?)          (utils/get-cursor :pen)
             (= drawing-tool :curve)     (utils/get-cursor :pencil)
             drawing-tool                (utils/get-cursor :create-shape)
             @alt?                       (utils/get-cursor :duplicate)
             :else                       (utils/get-cursor :pointer-inner))]

       (when (not= @cursor new-cursor)
         (reset! cursor new-cursor))))))

(defn setup-resize [layout viewport-ref]
  (let [on-resize (actions/on-resize viewport-ref)]
    (mf/use-layout-effect (mf/deps layout) on-resize)))

(defn setup-keyboard [alt? ctrl?]
  (hooks/use-stream ms/keyboard-alt #(reset! alt? %))
  (hooks/use-stream ms/keyboard-ctrl #(reset! ctrl? %)))

(defn setup-hover-shapes [page-id move-stream selected objects transform selected ctrl? hover hover-ids]
  (let [query-point
        (mf/use-callback
         (mf/deps page-id)
         (fn [point]
           (let [rect (gsh/center->rect point 8 8)]
             (uw/ask-buffered!
              {:cmd :selection/query
               :page-id page-id
               :rect rect
               :include-frames? true}))))

        ;; We use ref so we don't recreate the stream on a change
        transform-ref (mf/use-ref nil)

        over-shapes-stream
        (->> move-stream
             ;; When transforming shapes we stop querying the worker
             (rx/filter #(not (some? (mf/ref-val transform-ref))))
             (rx/switch-map query-point))

        roots (mf/use-memo
               (mf/deps selected objects)
               (fn []
                 (let [roots-ids (cp/clean-loops objects selected)]
                   (->> roots-ids (mapv #(get objects %))))))]

    (mf/use-effect
     (mf/deps transform)
     #(mf/set-ref-val! transform-ref transform))

    (hooks/use-stream
     over-shapes-stream
     (mf/deps page-id objects selected @ctrl?)
     (fn [ids]
       (let [remove-id? (into #{} (mapcat #(cp/get-parents % objects)) selected)
             remove-id? (if @ctrl?
                          (d/concat remove-id?
                                    (->> ids
                                         (filterv #(= :group (get-in objects [% :type])))))
                          remove-id?)
             ids (->> ids (filterv (comp not remove-id?)))]
         (reset! hover (get objects (first ids)))
         (reset! hover-ids ids))))))

(defn setup-viewport-modifiers [modifiers selected objects render-ref]
  (let [roots (mf/use-memo
               (mf/deps objects selected)
               (fn []
                 (let [roots-ids (cp/clean-loops objects selected)]
                   (->> roots-ids (mapv #(get objects %))))))]

    ;; Layout effect is important so the code is executed before the modifiers
    ;; are applied to the shape
    (mf/use-layout-effect
     (mf/deps modifiers roots)

     #(when-let [render-node (mf/ref-val render-ref)]
        (if modifiers
          (utils/update-transform render-node roots modifiers)
          (utils/remove-transform render-node roots))))))
