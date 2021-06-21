;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.numeric-input
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.common.spec :as us]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as sm]
   [rumext.alpha :as mf]))

(mf/defc numeric-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props external-ref]
  (let [value-str   (obj/get props "value")
        min-val-str (obj/get props "min")
        max-val-str (obj/get props "max")
        wrap-value? (obj/get props "data-wrap")
        on-change   (obj/get props "onChange")

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref (mf/use-ref)
        ref (or external-ref local-ref)

        value (d/parse-integer value-str)

        min-val (when (string? min-val-str)
                  (d/parse-integer min-val-str))
        max-val (when (string? max-val-str)
                  (d/parse-integer max-val-str))

        num? (fn [val] (and (number? val)
                            (not (math/nan? val))
                            (math/finite? val)))

        parse-value
        (mf/use-callback
          (mf/deps ref min-val max-val value)
          (fn []
            (let [input-node (mf/ref-val ref)
                  new-value (-> (dom/get-value input-node)
                                (sm/expr-eval value))]
              (when (num? new-value)
                (-> new-value
                    (math/round)
                    (cljs.core/max us/min-safe-int)
                    (cljs.core/min us/max-safe-int)
                    (cond->
                      (num? min-val)
                      (cljs.core/max min-val)

                      (num? max-val)
                      (cljs.core/min max-val)))))))

        update-input
        (mf/use-callback
          (mf/deps ref)
          (fn [new-value]
            (let [input-node (mf/ref-val ref)]
              (dom/set-value! input-node (str new-value)))))

        apply-value
        (mf/use-callback
          (mf/deps on-change update-input value)
          (fn [new-value]
            (when (and (some? new-value) (not= new-value value) (some? on-change))
              (on-change new-value))
            (when (some? new-value)
              (update-input new-value))))

        set-delta
        (mf/use-callback
         (mf/deps wrap-value? min-val max-val parse-value apply-value)
         (fn [event up? down?]
           (let [current-value (parse-value)]
             (when current-value
               (let [increment (if (kbd/shift? event)
                                 (if up? 10 -10)
                                 (if up? 1 -1))

                     new-value (+ current-value increment)
                     new-value (cond
                                 (and wrap-value? (num? max-val) (num? min-val)
                                      (> new-value max-val) up?)
                                 (-> new-value (- max-val) (+ min-val) (- 1))

                                 (and wrap-value? (num? min-val) (num? max-val)
                                      (< new-value min-val) down?)
                                 (-> new-value (- min-val) (+ max-val) (+ 1))

                                 (and (num? min-val) (< new-value min-val))
                                 min-val

                                 (and (num? max-val) (> new-value max-val))
                                 max-val

                                 :else new-value)]

                 (apply-value new-value))))))

        handle-key-down
        (mf/use-callback
         (mf/deps set-delta apply-value update-input)
         (fn [event]
           (let [up?    (kbd/up-arrow? event)
                 down?  (kbd/down-arrow? event)
                 enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)]
             (when (or up? down?)
               (set-delta event up? down?))
             (when enter?
               (let [new-value (parse-value)]
                 (apply-value new-value)))
             (when esc?
               (update-input value-str)))))

        handle-mouse-wheel
        (mf/use-callback
         (mf/deps set-delta)
         (fn [event]
           (set-delta event (< (.-deltaY event) 0) (> (.-deltaY event) 0))))

        handle-blur
        (mf/use-callback
          (mf/deps parse-value apply-value update-input)
          (fn [_]
            (let [new-value (parse-value)]
              (if new-value
                (apply-value new-value)
                (update-input value-str)))))

        props (-> props
                  (obj/without ["value" "onChange"])
                  (obj/set! "className" "input-text")
                  (obj/set! "type" "text")
                  (obj/set! "ref" ref)
                  (obj/set! "defaultValue" value-str)
                  (obj/set! "onWheel" handle-mouse-wheel)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onBlur" handle-blur))]

    (mf/use-effect
     (mf/deps value-str)
     (fn []
       (when-let [input-node (mf/ref-val ref)]
         (when-not (dom/active? input-node)
           (dom/set-value! input-node value-str)))))

    [:> :input props]))

