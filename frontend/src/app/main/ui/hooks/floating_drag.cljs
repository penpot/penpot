;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.hooks.floating-drag
  "Pointer drag hook for floating panels, mirroring the plugin modal drag
  handler in plugins-runtime."
  (:require
   [app.common.geom.point :as gpt]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn- parse-translate
  "Reads the current translate offset from an element's computed transform."
  [^js el]
  (if (and el (.-DOMMatrixReadOnly js/window))
    (let [cs (.getComputedStyle js/window el nil)
          matrix (js/DOMMatrixReadOnly. (.-transform cs))]
      {:x (.-m41 matrix)
       :y (.-m42 matrix)})
    {:x 0 :y 0}))

(defn- set-dragging-class!
  "Toggle `dragging-class` on `target` while a drag is active."
  [^js target dragging-class dragging?]
  (when (and target dragging-class)
    (if dragging?
      (dom/add-class! target dragging-class)
      (dom/remove-class! target dragging-class))))

(defn use-floating-drag
  "Returns pointer handlers to drag `target-ref` by its header.

  Optional `on-move` is called on pointer down (e.g. to raise z-index).
  Optional `dragging-class` is toggled on `target-ref` while dragging."
  ([target-ref]
   (use-floating-drag target-ref nil nil))
  ([target-ref on-move]
   (use-floating-drag target-ref on-move nil))
  ([target-ref on-move dragging-class]
   (let [dragging-ref          (mf/use-ref false)
         pointer-id-ref        (mf/use-ref nil)
         initial-translate-ref (mf/use-ref {:x 0 :y 0})
         initial-client-ref    (mf/use-ref (gpt/point 0 0))

         end-drag
         (mf/use-fn
          (mf/deps dragging-class)
          (fn [event]
            (when (mf/ref-val dragging-ref)
              (mf/set-ref-val! dragging-ref false)
              (mf/set-ref-val! pointer-id-ref nil)
              (set-dragging-class! (mf/ref-val target-ref) dragging-class false)
              (when event (dom/release-pointer event)))))

         handle-lost-pointer-capture
         (mf/use-fn
          (fn [event]
            (end-drag event)))

         handle-pointer-up
         (mf/use-fn
          (fn [event]
            (when (= (.-pointerId event) (mf/ref-val pointer-id-ref))
              (end-drag event))))

         handle-pointer-move
         (mf/use-fn
          (fn [event]
            (when (and (mf/ref-val dragging-ref)
                       (= (.-pointerId event) (mf/ref-val pointer-id-ref)))
              (let [target (mf/ref-val target-ref)
                    start  (mf/ref-val initial-client-ref)
                    pos    (dom/get-client-position event)
                    {:keys [x y]} (mf/ref-val initial-translate-ref)
                    delta-x (+ x (- (:x pos) (:x start)))
                    delta-y (+ y (- (:y pos) (:y start)))]
                (when target
                  (dom/set-css-property! target "transform"
                                         (str "translate(" delta-x "px, " delta-y "px)")))))))

         handle-pointer-down
         (mf/use-fn
          (mf/deps on-move dragging-class)
          (fn [event]
            (when (and (= (.-button event) 0)
                       (not (and (instance? js/Element (.-target event))
                                 (.closest (.-target event) "button"))))
              (dom/prevent-default event)
              (let [target (mf/ref-val target-ref)]
                (when target
                  (mf/set-ref-val! pointer-id-ref (.-pointerId event))
                  (mf/set-ref-val! initial-client-ref (dom/get-client-position event))
                  (mf/set-ref-val! initial-translate-ref (parse-translate target))
                  (mf/set-ref-val! dragging-ref true)
                  (set-dragging-class! target dragging-class true)
                  (dom/capture-pointer event)
                  (when on-move (on-move)))))))]

     {:on-pointer-down handle-pointer-down
      :on-pointer-move handle-pointer-move
      :on-pointer-up handle-pointer-up
      :on-lost-pointer-capture handle-lost-pointer-capture})))
