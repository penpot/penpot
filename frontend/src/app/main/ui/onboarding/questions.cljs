;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.questions
  "External form for onboarding questions."
  (:require
   [app.main.data.events :as ev]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [goog.events :as gev]
   [potok.core :as ptk]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defn load-arengu-sdk
  [container-ref email form-id]
  (letfn [(on-arengu-loaded [resolve reject]
            (let [container (mf/ref-val container-ref)]
              (-> (.embed js/ArenguForms form-id container)
                  (p/then (fn [form]
                            (.setHiddenField ^js form "email" email)
                            (st/emit! (ptk/event ::ev/event {::ev/name "arengu-form-load-success"
                                                             ::ev/origin "onboarding-questions"
                                                             ::ev/type "fact"}))
                            (resolve)))
                  (p/catch reject))))

          (mark-as-answered []
            (st/emit! (du/mark-questions-as-answered)))

          (initialize [cleaners resolve reject]
            (let [script (dom/create-element "script")
                  head   (unchecked-get js/document "head")
                  lkey1  (gev/listen js/document "af-submitForm-success" mark-as-answered)
                  lkey2  (gev/listen js/document "af-getForm-error" reject)]

              (unchecked-set script "src" "https://sdk.arengu.com/forms.js")
              (unchecked-set script "onload" (partial on-arengu-loaded resolve reject))
              (dom/append-child! head script)

              (swap! cleaners conj
                     #(do (gev/unlistenByKey lkey1)
                          (gev/unlistenByKey lkey2)))

              (swap! cleaners conj
                     #(dom/remove-child! head script))))

          (on-error [_]
            (st/emit! (ptk/event ::ev/event {::ev/name "arengu-form-load-error"
                                             ::ev/origin "onboarding-questions"
                                             ::ev/type "fact"}))
            (mark-as-answered))]

    (let [cleaners (atom #{})]
      (-> (p/create (partial initialize cleaners))
          (p/timeout 5000)
          (p/catch on-error))
      (fn []
        (run! (fn [clean-fn] (clean-fn)) @cleaners)))))

(mf/defc questions
  [{:keys [profile form-id]}]
  (let [container (mf/use-ref)]
    (mf/use-effect (partial load-arengu-sdk container (:email profile) form-id))
    [:div.modal-wrapper.questions-form
     [:div.modal-overlay
      [:div.modal-container.onboarding.onboarding-v2 {:ref container}
       [:img.deco.left {:src "images/deco-left.png" :border 0}]
       [:img.deco.right {:src "images/deco-right.png" :border 0}]]]]))


