;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.color-input
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as sm]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc color-input
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [props external-ref]
  (let [value (obj/get props "value")
        on-change (obj/get props "onChange")

        ;; We need a ref pointing to the input dom element, but the user
        ;; of this component may provide one (that is forwarded here).
        ;; So we use the external ref if provided, and the local one if not.
        local-ref (mf/use-ref)
        ref (or external-ref local-ref)

        parse-value
        (mf/use-callback
          (mf/deps ref)
          (fn []
            (let [input-node (mf/ref-val ref)]
              (try
                (let [new-value (-> (dom/get-value input-node)
                                    (uc/expand-hex)
                                    (uc/parse-color)
                                    (uc/prepend-hash))]
                  (dom/set-validity! input-node "")
                  new-value)
              (catch :default _
                (dom/set-validity! input-node (tr "errors.invalid-color"))
                nil)))))

        update-input
        (mf/use-callback
          (mf/deps ref)
          (fn [new-value]
            (let [input-node (mf/ref-val ref)]
              (dom/set-value! input-node (uc/remove-hash new-value)))))

        apply-value
        (mf/use-callback
          (mf/deps on-change update-input)
          (fn [new-value]
            (when new-value
              (when on-change
                (on-change new-value))
              (update-input new-value))))

        handle-key-down
        (mf/use-callback
         (mf/deps apply-value update-input)
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)]
             (when enter?
               (dom/prevent-default event)
               (let [new-value (parse-value)]
                 (apply-value new-value)))
             (when esc?
               (dom/prevent-default event)
               (update-input value)))))

        handle-blur
        (mf/use-callback
          (mf/deps parse-value apply-value update-input)
          (fn [event]
            (let [new-value (parse-value)]
              (if new-value
                (apply-value new-value)
                (update-input value)))))

        ;; list-id (str "colors-" (uuid/next))

        props (-> props
                  (obj/without ["value" "onChange"])
                  (obj/set! "type" "text")
                  (obj/set! "ref" ref)
                  ;; (obj/set! "list" list-id)
                  (obj/set! "defaultValue" value)
                  (obj/set! "onKeyDown" handle-key-down)
                  (obj/set! "onBlur" handle-blur))]

    [:*
     [:> :input props]
     ;; FIXME: this causes some weird interactions because of using apply-value
     ;; [:datalist {:id list-id}
     ;;  (for [color-name uc/color-names]
     ;;    [:option color-name])]
     ]))

