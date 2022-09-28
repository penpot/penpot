;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.numeric-input
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.ui.formats :as fmt]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as sm]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc numeric-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props external-ref]
  (let [value-str    (obj/get props "value")
        min-val-str  (obj/get props "min")
        max-val-str  (obj/get props "max")
        step-val-str (obj/get props "step")
        wrap-value?  (obj/get props "data-wrap")
        on-change    (obj/get props "onChange")
        on-blur      (obj/get props "onBlur")
        title        (obj/get props "title")
        default-val  (obj/get props "default")
        nillable     (obj/get props "nillable")

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref  (mf/use-ref)
        ref        (or external-ref local-ref)

        ;; We need to store the handle-blur ref so we can call it on unmount
        handle-blur-ref (mf/use-ref nil)
        dirty-ref (mf/use-ref false)

        ;; This `value` represents the previous value and is used as
        ;; initil value for the simple math expression evaluation.
        value      (d/parse-double value-str default-val)

        min-val    (cond
                     (number? min-val-str)
                     min-val-str

                     (string? min-val-str)
                     (d/parse-double min-val-str))

        max-val    (cond
                     (number? max-val-str)
                     max-val-str

                     (string? max-val-str)
                     (d/parse-double max-val-str))

        step-val   (cond
                     (number? step-val-str)
                     step-val-str

                     (string? step-val-str)
                     (d/parse-double step-val-str)

                     :else 1)

        parse-value
        (mf/use-callback
         (mf/deps ref min-val max-val value nillable default-val)
         (fn []
           (let [input-node (mf/ref-val ref)
                 new-value (-> (dom/get-value input-node)
                               (str/strip-suffix ".")
                               (sm/expr-eval value))]
             (cond
               (d/num? new-value)
               (-> new-value
                   (cljs.core/max (/ us/min-safe-int 2))
                   (cljs.core/min (/ us/max-safe-int 2))
                   (cond->
                    (d/num? min-val)
                     (cljs.core/max min-val)

                     (d/num? max-val)
                     (cljs.core/min max-val)))

               nillable
               default-val

               :else value))))

        update-input
        (mf/use-callback
         (mf/deps ref)
         (fn [new-value]
           (let [input-node (mf/ref-val ref)]
             (dom/set-value! input-node (fmt/format-number new-value)))))

        apply-value
        (mf/use-callback
         (mf/deps on-change update-input value)
         (fn [new-value]
           (mf/set-ref-val! dirty-ref false)
           (when (and (not= new-value value) (some? on-change))
             (on-change new-value))
           (update-input new-value)))

        set-delta
        (mf/use-callback
         (mf/deps wrap-value? min-val max-val parse-value apply-value)
         (fn [event up? down?]
           (let [current-value (parse-value)]
             (when current-value
               (let [increment (cond
                                 (kbd/shift? event)
                                 (if up? (* step-val 10) (* step-val -10))

                                 (kbd/alt? event)
                                 (if up? (* step-val 0.1) (* step-val -0.1))

                                 :else
                                 (if up? step-val (- step-val)))

                     new-value (+ current-value increment)
                     new-value (cond
                                 (and wrap-value? (d/num? max-val min-val)
                                      (> new-value max-val) up?)
                                 (-> new-value (- max-val) (+ min-val) (- step-val))

                                 (and wrap-value? (d/num? max-val min-val)
                                      (< new-value min-val) down?)
                                 (-> new-value (- min-val) (+ max-val) (+ step-val))

                                 (and (d/num? min-val) (< new-value min-val))
                                 min-val

                                 (and (d/num? max-val) (> new-value max-val))
                                 max-val

                                 :else new-value)]

                 (apply-value new-value))))))

        handle-key-down
        (mf/use-callback
         (mf/deps set-delta apply-value update-input)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?    (kbd/up-arrow? event)
                 down?  (kbd/down-arrow? event)
                 enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 input-node (mf/ref-val ref)]
             (when (or up? down?)
               (set-delta event up? down?))
             (when enter?
               (dom/blur! input-node))
             (when esc?
               (update-input value-str)))))

        handle-mouse-wheel
        (mf/use-callback
         (mf/deps set-delta)
         (fn [event]
           (let [input-node (mf/ref-val ref)]
             (when (dom/active? input-node)
               (let [event (.getBrowserEvent ^js event)]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (set-delta event (< (.-deltaY event) 0) (> (.-deltaY event) 0)))))))

        handle-blur
        (mf/use-callback
         (mf/deps parse-value apply-value update-input on-blur)
         (fn [_]
           (let [new-value (or (parse-value) default-val)]
             (if (or nillable new-value)
               (apply-value new-value)
               (update-input new-value)))
           (when on-blur (on-blur))))

        on-click
        (mf/use-callback
         (fn [event]
           (let [target (dom/get-target event)]
             (when (some? ref)
               (let [current (mf/ref-val ref)]
                 (when (and (some? current) (not (.contains current target)))
                   (dom/blur! current)))))))

        props (-> props
                  (obj/without ["value" "onChange" "nillable"])
                  (obj/set! "className" "input-text")
                  (obj/set! "type" "text")
                  (obj/set! "ref" ref)
                  (obj/set! "defaultValue" (fmt/format-number value))
                  (obj/set! "title" title)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onBlur" handle-blur))]

    (mf/use-effect
     (mf/deps value)
     (fn []
       (when-let [input-node (mf/ref-val ref)]
         (dom/set-value! input-node (fmt/format-number value)))))

    (mf/use-effect
     (mf/deps handle-blur)
     (fn []
       (mf/set-ref-val! handle-blur-ref {:fn handle-blur})))

    (mf/use-layout-effect
     (fn []
       #(when (mf/ref-val dirty-ref)
          (let [handle-blur (:fn (mf/ref-val handle-blur-ref))]
            (handle-blur)))))

    (mf/use-layout-effect
     (mf/deps handle-mouse-wheel)
     (fn []
       (let [keys [(events/listen (mf/ref-val ref) EventType.WHEEL handle-mouse-wheel #js {:pasive false})]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    (mf/use-layout-effect
     (fn []
       (let [keys [(events/listen globals/window EventType.POINTERDOWN on-click)
                   (events/listen globals/window EventType.MOUSEDOWN on-click)
                   (events/listen globals/window EventType.CLICK on-click)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))
    
    (mf/use-layout-effect
     (mf/deps handle-mouse-wheel)
     (fn []
       (let [keys [(events/listen (mf/ref-val ref) EventType.WHEEL handle-mouse-wheel #js {:pasive false})]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))


    (mf/use-layout-effect
     (mf/deps handle-mouse-wheel)
     (fn []
       (let [keys [(events/listen (mf/ref-val ref) EventType.WHEEL handle-mouse-wheel #js {:pasive false})]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    [:> :input props]))
