;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.numeric-input
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as h]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as smt]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(mf/defc numeric-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props external-ref]
  (let [value-str   (unchecked-get props "value")
        min-value   (unchecked-get props "min")
        max-value   (unchecked-get props "max")
        step-value  (unchecked-get props "step")
        wrap-value? (unchecked-get props "data-wrap")
        on-change   (unchecked-get props "onChange")
        on-blur     (unchecked-get props "onBlur")
        title       (unchecked-get props "title")
        default     (unchecked-get props "default")
        nillable?   (unchecked-get props "nillable")

        min-value   (d/parse-double min-value)
        max-value   (d/parse-double max-value)
        step-value  (d/parse-double step-value 1)
        default     (d/parse-double default 0)

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref   (mf/use-ref)
        ref         (or external-ref local-ref)

        ;; This `value` represents the previous value and is used as
        ;; initil value for the simple math expression evaluation.
        value       (d/parse-double value-str default)

        ;; We need to store the handle-blur ref so we can call it on unmount
        dirty-ref   (mf/use-ref false)

        parse-value
        (mf/use-fn
         (mf/deps min-value max-value value nillable? default)
         (fn []
           (when-let [node (mf/ref-val ref)]
             (let [new-value (-> (dom/get-value node)
                                 (str/strip-suffix ".")
                                 (smt/expr-eval value))]
               (cond
                 (d/num? new-value)
                 (-> new-value
                     (d/max (/ sm/min-safe-int 2))
                     (d/min (/ sm/max-safe-int 2))
                     (cond-> (d/num? min-value)
                       (d/max min-value))
                     (cond-> (d/num? max-value)
                       (d/min max-value)))

                 nillable?
                 default

                 :else value)))))

        update-input
        (mf/use-fn
         (fn [new-value]
           (when-let [node (mf/ref-val ref)]
             (dom/set-value! node (fmt/format-number new-value)))))

        apply-value
        (mf/use-fn
         (mf/deps on-change update-input value)
         (fn [event new-value]
           (mf/set-ref-val! dirty-ref false)
           (when (and (not= new-value value)
                      (fn? on-change))
             ;; FIXME: on-change very slow, makes the handler laggy
             (on-change new-value event))
           (update-input new-value)))

        set-delta
        (mf/use-fn
         (mf/deps wrap-value? min-value max-value parse-value apply-value)
         (fn [event up? down?]
           (let [current-value (parse-value)]
             (when current-value
               (let [increment (cond
                                 (kbd/shift? event)
                                 (if up? (* step-value 10) (* step-value -10))

                                 (kbd/alt? event)
                                 (if up? (* step-value 0.1) (* step-value -0.1))

                                 :else
                                 (if up? step-value (- step-value)))

                     new-value (+ current-value increment)
                     new-value (cond
                                 (and wrap-value? (d/num? max-value min-value)
                                      (> new-value max-value) up?)
                                 (-> new-value (- max-value) (+ min-value) (- step-value))

                                 (and wrap-value? (d/num? max-value min-value)
                                      (< new-value min-value) down?)
                                 (-> new-value (- min-value) (+ max-value) (+ step-value))

                                 (and (d/num? min-value) (< new-value min-value))
                                 min-value

                                 (and (d/num? max-value) (> new-value max-value))
                                 max-value

                                 :else new-value)]

                 (apply-value event new-value))))))

        handle-key-down
        (mf/use-fn
         (mf/deps set-delta apply-value update-input)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?    (kbd/up-arrow? event)
                 down?  (kbd/down-arrow? event)
                 enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (mf/ref-val ref)]
             (when (or up? down?)
               (set-delta event up? down?))
             (when enter?
               (dom/blur! node))
             (when esc?
               (update-input value-str)
               (dom/blur! node)))))

        ;; FIXME: test it
        handle-mouse-wheel
        (mf/use-fn
         (mf/deps set-delta)
         (fn [event]
           (when-let [node (mf/ref-val ref)]
             (when (dom/active? node)
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (let [{:keys [y]} (dom/get-delta-position event)]
                 (set-delta event (< y 0) (> y 0)))))))

        handle-blur
        (mf/use-fn
         (mf/deps parse-value apply-value update-input on-blur)
         (fn [event]
           (let [new-value (or (parse-value) default)]
             (if (or nillable? new-value)
               (apply-value event new-value)
               (update-input new-value)))
           (when (fn? on-blur)
             (on-blur event))))

        handle-unmount
        (h/use-ref-callback handle-blur)

        on-click
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)
                 node   (mf/ref-val ref)]
             (when (and (some? node) (not (dom/child? node target)))
               (dom/blur! node)))))

        props (-> (obj/clone props)
                  ;; NOTE: the fastest way to remove props is just
                  ;; assigning `nil` value
                  (obj/set! "value" mf/undefined)
                  (obj/set! "onChange" mf/undefined)
                  (obj/set! "nillable" mf/undefined)
                  (obj/set! "className" "input-text")
                  (obj/set! "type" "text")
                  (obj/set! "ref" ref)
                  (obj/set! "defaultValue" (fmt/format-number value))
                  (obj/set! "title" title)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onBlur" handle-blur))]

    (mf/with-effect [value]
      (when-let [input-node (mf/ref-val ref)]
        (dom/set-value! input-node (fmt/format-number value))))

    (mf/with-effect []
      (fn []
        (when (mf/ref-val dirty-ref)
          (handle-unmount))))

    (mf/with-layout-effect []
      (let [keys [(events/listen globals/window "pointerdown" on-click)
                  (events/listen globals/window "click" on-click)]]
        #(run! events/unlistenByKey keys)))

    (mf/with-layout-effect [handle-mouse-wheel]
      (when-let [node (mf/ref-val ref)]
        (let [key (events/listen node "wheel" handle-mouse-wheel #js {:passive false})]
          #(events/unlistenByKey key))))

    [:> :input props]))
