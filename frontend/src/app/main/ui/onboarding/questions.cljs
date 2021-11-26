;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding.questions
  "External form for onboarding questions."
  (:require
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [goog.events :as ev]
   [promesa.core :as p]
   [rumext.alpha :as mf]))

(defn load-arengu-sdk
  [container-ref email form-id]
  (letfn [(on-init []
            (when-let [container (mf/ref-val container-ref)]
              (-> (.embed js/ArenguForms form-id container)
                  (p/then (fn [form] (.setHiddenField ^js form "email" email))))))

          (on-submit-success [_]
            (st/emit! (du/mark-questions-as-answered)))]

    (let [script (dom/create-element "script")
          head   (unchecked-get js/document "head")
          lkey1  (ev/listen js/document "af-submitForm-success" on-submit-success)]

      (unchecked-set script "src" "https://sdk.arengu.com/forms.js")
      (unchecked-set script "onload" on-init)
      (dom/append-child! head script)

      (fn []
        (ev/unlistenByKey lkey1)))))

(mf/defc questions
  [{:keys [profile form-id]}]
  (let [container (mf/use-ref)]
    (mf/use-effect (partial load-arengu-sdk container (:email profile) form-id))
    [:div.modal-wrapper.questions-form
     [:div.modal-overlay
      [:div.modal-container {:ref container}]]]))


