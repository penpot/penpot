;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.color-input
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn- get-clean-color
  [node]
  (-> (dom/get-value node)
      (cc/expand-hex)
      (cc/parse)
      (cc/prepend-hash)))

(mf/defc color-input*
  {::mf/forward-ref true}
  [{:keys [value on-change on-blur on-focus select-on-focus class aria-label] :rest props} external-ref]
  (let [select-on-focus? (d/nilv select-on-focus true)
        class            (d/nilv class "color-input")
        aria-label       (or aria-label (tr "inspect.attributes.color"))

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref        (mf/use-ref)
        ref              (or external-ref local-ref)

        ;; We need to store the handle-blur ref so we can call it on unmount
        dirty-ref        (mf/use-ref false)

        parse-value
        (mf/use-fn
         (fn []
           (let [input-node (mf/ref-val ref)]
             (try
               (let [value (get-clean-color input-node)]
                 (dom/set-validity! input-node "")
                 value)
               (catch :default _e
                 (dom/set-validity! input-node (tr "errors.invalid-color"))
                 nil)))))

        update-input
        (mf/use-fn
         (fn [new-value]
           (let [input-node (mf/ref-val ref)]
             (dom/set-value! input-node (cc/remove-hash new-value)))))

        apply-value
        (mf/use-fn
         (mf/deps on-change update-input)
         (fn [new-value]
           (mf/set-ref-val! dirty-ref false)
           (when (and new-value (not= (cc/remove-hash new-value) value))
             (when on-change (on-change new-value))
             (update-input new-value))))

        handle-key-down
        (mf/use-fn
         (mf/deps apply-value update-input)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [input-node (mf/ref-val ref)]
             (cond
               (kbd/enter? event)
               (let [value (parse-value)]
                 (update-input value)
                 (dom/prevent-default event)
                 (dom/blur! input-node))

               (kbd/esc? event)
               (do
                 (update-input value)
                 (dom/prevent-default event)
                 (dom/blur! input-node))))))

        on-click
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)
                 current (mf/ref-val ref)]
             (when (and (some? current) (not (.contains current target)))
               (dom/blur! current)))))

        on-mouse-up
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)))

        handle-focus
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)]
             (when on-focus (on-focus))

             (when select-on-focus?
               (-> event (dom/get-target) (.select))
               ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" on-mouse-up #js {:once true})))))

        handle-blur
        (mf/use-fn
         (mf/deps parse-value apply-value update-input)
         (fn [_]
           (let [new-value (parse-value)]
             (if new-value
               (apply-value new-value)
               (update-input value))
             (when on-blur
               (on-blur)))))

        handle-blur
        (hooks/use-ref-callback handle-blur)

        props
        (mf/spread-props props
                         {:class class
                          :type "text"
                          :ref ref
                          :aria-label aria-label
                          :default-value value
                          :on-key-down handle-key-down
                          :on-blur handle-blur
                          :on-focus handle-focus})]

    (mf/with-effect [value]
      (when-let [node (mf/ref-val ref)]
        (dom/set-value! node value)))

    (mf/with-layout-effect []
      ;; UNMOUNT: we use layout-effect because we still need the dom
      ;; node to be present on the refs, for properly execute the
      ;; on-blur event handler
      #(when (mf/ref-val dirty-ref) (handle-blur)))

    (mf/with-layout-effect []
      (let [key1 (events/listen globals/window "pointerdown" on-click)
            key2 (events/listen globals/window "click" on-click)]
        #(do
           (events/unlistenByKey key1)
           (events/unlistenByKey key2))))

    [:> :input props]))
