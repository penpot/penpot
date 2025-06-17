;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-input
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn clean-color
  [value]
  (-> value
      (cc/expand-hex)
      (cc/parse)
      (cc/prepend-hash)))

(mf/defc color-input*
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props external-ref]
  (let [value            (obj/get props "value")
        on-change        (obj/get props "onChange")
        on-blur          (obj/get props "onBlur")
        on-focus         (obj/get props "onFocus")
        select-on-focus? (d/nilv (unchecked-get props "selectOnFocus") true)
        class            (d/nilv (unchecked-get props "className") "color-input")
        aria-label       (d/nilv (unchecked-get props "aria-label") (tr "inspect.attributes.color"))

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref        (mf/use-ref)
        ref              (or external-ref local-ref)

        ;; We need to store the handle-blur ref so we can call it on unmount
        handle-blur-ref  (mf/use-ref nil)
        dirty-ref        (mf/use-ref false)

        parse-value
        (mf/use-fn
         (mf/deps ref)
         (fn []
           (let [input-node (mf/ref-val ref)]
             (try
               (let [new-value (clean-color (dom/get-value input-node))]
                 (dom/set-validity! input-node "")
                 new-value)
               (catch :default _e
                 (dom/set-validity! input-node (tr "errors.invalid-color"))
                 nil)))))

        update-input
        (mf/use-fn
         (mf/deps ref)
         (fn [new-value]
           (let [input-node (mf/ref-val ref)]
             (dom/set-value! input-node (cc/remove-hash new-value)))))

        apply-value
        (mf/use-fn
         (mf/deps on-change update-input)
         (fn [new-value]
           (mf/set-ref-val! dirty-ref false)
           (when (and new-value (not= (cc/remove-hash new-value) value))
             (when on-change
               (on-change new-value))
             (update-input new-value))))

        handle-key-down
        (mf/use-fn
         (mf/deps apply-value update-input)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 input-node (mf/ref-val ref)]
             (when enter?
               (dom/prevent-default event)
               (let [new-value (parse-value)]
                 (apply-value new-value)
                 (dom/blur! input-node)))
             (when esc?
               (dom/prevent-default event)
               (update-input value)
               (dom/blur! input-node)))))

        handle-blur
        (mf/use-fn
         (mf/deps parse-value apply-value update-input)
         (fn [_]
           (let [new-value (parse-value)]
             (when on-blur
               (on-blur))
             (if new-value
               (apply-value new-value)
               (update-input value)))))

        on-click
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)]
             (when (some? ref)
               (let [current (mf/ref-val ref)]
                 (when (and (some? current) (not (.contains current target)))
                   (dom/blur! current)))))))

        on-mouse-up
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)))

        handle-focus
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)]
             (when on-focus
               (on-focus event))

             (when select-on-focus?
               (-> event (dom/get-target) (.select))
                ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" on-mouse-up #js {"once" true})))))

        props (-> (obj/clone props)
                  (obj/unset! "selectOnFocus")
                  (obj/set! "value" mf/undefined)
                  (obj/set! "onChange" mf/undefined)
                  (obj/set! "className" class)
                  (obj/set! "type" "text")
                  (obj/set! "ref" ref)
                  (obj/set! "aria-label" aria-label)
                  ;; (obj/set! "list" list-id)
                  (obj/set! "defaultValue" value)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onBlur" handle-blur)
                  (obj/set! "onFocus" handle-focus))]

    (mf/use-effect
     (mf/deps value)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (dom/set-value! node value))))

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
     (fn []
       (let [keys [(events/listen globals/window "pointerdown" on-click)
                   (events/listen globals/window "click" on-click)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    [:*
     [:> :input props]
     ;; FIXME: this causes some weird interactions because of using apply-value
     ;; [:datalist {:id list-id}
     ;;  (for [color-name cc/color-names]
     ;;    [:option color-name])]
     ]))

