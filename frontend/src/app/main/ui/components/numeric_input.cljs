;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.numeric-input
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.keyboard :as kbd]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.common.math :as math]))

(mf/defc numeric-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [value     (obj/get props "value")
        on-change (obj/get props "onChange")
        min-val   (obj/get props "min")
        max-val   (obj/get props "max")
        wrap-value? (obj/get props "data-wrap")

        stored-val (mf/use-var value)
        local-ref (mf/use-ref nil)
        ref (or ref local-ref)

        min-val (cond-> min-val
                  (string? min-val) (d/parse-integer nil))

        max-val (cond-> max-val
                  (string? max-val) (d/parse-integer nil))


        num? (fn [value] (and (number? value)
                              (not (math/nan? value))
                              (math/finite? value)))

        parse-value (fn [event]
                      (let [value (-> (dom/get-target-val event) (d/parse-integer nil))]
                        (when (num? value)
                          (cond-> value
                            (num? min-val) (cljs.core/max min-val)
                            (num? max-val) (cljs.core/min max-val)))))
        handle-change
        (mf/use-callback
         (mf/deps on-change)
         (fn [event]
           (let [value (parse-value event)]
             (when (and on-change (num? value))
               (on-change value)))))

        set-delta
        (mf/use-callback
         (mf/deps on-change wrap-value? min-val max-val)
         (fn [event up? down?]
           (let [value (parse-value event)
                 increment (if up? 9 -9)]
             (when (and (or up? down?) (num? value))
               (cond
                 (kbd/shift? event)
                 (let [new-value (+ value increment)
                       new-value (cond
                                   (and wrap-value? (num? max-val) (num? min-val) (> new-value max-val) up?)
                                   (+ min-val (- max-val new-value))

                                   (and wrap-value? (num? min-val) (num? max-val) (< new-value min-val) down?)
                                   (- max-val (- new-value min-val))

                                   (and (num? min-val) (< new-value min-val)) min-val
                                   (and (num? max-val) (> new-value max-val)) max-val
                                   :else new-value)]
                   (dom/set-value! (dom/get-target event) new-value))

                 (and wrap-value? (num? max-val) (num? min-val) (= value max-val) up?)
                 (dom/set-value! (dom/get-target event) min-val)

                 (and wrap-value? (num? min-val) (num? max-val) (= value min-val) down?)
                 (dom/set-value! (dom/get-target event) max-val))))))

        handle-key-down
        (mf/use-callback
         (mf/deps set-delta)
         (fn [event]
           (set-delta event (kbd/up-arrow? event) (kbd/down-arrow? event))))

        handle-mouse-wheel
        (mf/use-callback
         (mf/deps set-delta)
         (fn [event]
           (set-delta event (< (.-deltaY event) 0) (> (.-deltaY event) 0))))

        handle-blur
        (fn [event]
          (when-let [input-node (and ref (mf/ref-val ref))]
            (dom/set-value! input-node @stored-val)))

        props (-> props
                  (obj/without ["value" "onChange"])
                  (obj/set! "className" "input-text")
                  (obj/set! "type" "number")
                  (obj/set! "ref" ref)
                  (obj/set! "defaultValue" value)
                  (obj/set! "onWheel" handle-mouse-wheel)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onChange" handle-change)
                  (obj/set! "onBlur" handle-blur))]

    (mf/use-effect
     (mf/deps value)
     (fn []
       (when-let [input-node (and ref (mf/ref-val ref))]
         (if-not (dom/active? input-node)
           (dom/set-value! input-node value)
           (reset! stored-val value)))))
    [:> :input props]))

