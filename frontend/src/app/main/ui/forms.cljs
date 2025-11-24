;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.forms
  (:require
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.keyboard :as k]
   [rumext.v2 :as mf]))

(def context (mf/create-context nil))

(mf/defc form-input*
  [{:keys [name] :rest props}]

  (let [form       (mf/use-ctx context)
        input-name name

        touched?   (and (contains? (:data @form) input-name)
                        (get-in @form [:touched input-name]))
        error      (get-in @form [:errors input-name])

        value      (get-in @form [:data input-name] "")

        on-change
        (mf/use-fn
         (mf/deps input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value true))))


        props
        (mf/spread-props props {:on-change on-change
                                :default-value value})

        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    [:> input* props]))

(mf/defc form-submit*
  [{:keys [disabled on-submit] :rest props}]

  (let [form      (mf/use-ctx context)
        disabled? (or (and (some? form)
                           (or (not (:valid @form))
                               (seq (:external-errors @form))))
                      (true? disabled))
        handle-key-down-save
        (mf/use-fn
         (mf/deps on-submit form)
         (fn [e]
           (when (or (k/enter? e) (k/space? e))
             (dom/prevent-default e)
             (on-submit form e))))

        props
        (mf/spread-props props {:disabled disabled?
                                :on-key-down handle-key-down-save
                                :type "submit"})]

    [:> button* props]))

(mf/defc form*
  [{:keys [on-submit form children class]}]
  (let [on-submit' (mf/use-fn
                    (mf/deps on-submit)
                    (fn [event]
                      (dom/prevent-default event)
                      (when (fn? on-submit)
                        (on-submit form event))))]
    [:> (mf/provider context) {:value form}
     [:form {:class class :on-submit on-submit'} children]]))